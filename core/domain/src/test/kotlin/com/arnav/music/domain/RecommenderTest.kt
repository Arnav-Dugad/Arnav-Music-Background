package com.arnav.music.domain

import com.arnav.music.domain.intelligence.SmartPlaylist
import com.arnav.music.domain.intelligence.SmartPlaylistEngine
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.recommend.Aspect
import com.arnav.music.domain.recommend.AudioTraits
import com.arnav.music.domain.recommend.CoOccurrence
import com.arnav.music.domain.recommend.ColdStart
import com.arnav.music.domain.recommend.ContentFeatures
import com.arnav.music.domain.recommend.ContentSimilarity
import com.arnav.music.domain.recommend.DiscoveryLedger
import com.arnav.music.domain.recommend.DiscoveryPolicy
import com.arnav.music.domain.recommend.Engagement
import com.arnav.music.domain.recommend.Explain
import com.arnav.music.domain.recommend.ExplanationKind
import com.arnav.music.domain.recommend.Feedback
import com.arnav.music.domain.recommend.FeedbackKind
import com.arnav.music.domain.recommend.FeedbackRow
import com.arnav.music.domain.recommend.GraphCache
import com.arnav.music.domain.recommend.Impression
import com.arnav.music.domain.recommend.KMeans
import com.arnav.music.domain.recommend.ListeningContext
import com.arnav.music.domain.recommend.PlaylistSignal
import com.arnav.music.domain.recommend.RecEngine
import com.arnav.music.domain.recommend.RecInput
import com.arnav.music.domain.recommend.RecModel
import com.arnav.music.domain.recommend.RecSource
import com.arnav.music.domain.recommend.Recommendation
import com.arnav.music.domain.recommend.Reranker
import com.arnav.music.domain.recommend.RewardModel
import com.arnav.music.domain.recommend.SearchIntent
import com.arnav.music.domain.recommend.SessionIndex
import com.arnav.music.domain.recommend.SinglesCache
import com.arnav.music.domain.recommend.Sessionizer
import com.arnav.music.domain.recommend.SourceBandit
import com.arnav.music.domain.recommend.SpectralEmbedding
import com.arnav.music.domain.recommend.TasteModelBuilder
import com.arnav.music.domain.recommend.VectorMath
import com.arnav.music.domain.recommend.WalkGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class RecommenderTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val min = 60_000L

    private fun song(id: String, artist: String, genres: List<String> = listOf("pop"), energy: Float? = 0.5f, year: Int? = 2018, title: String = "Song $id", variant: MediaVariant? = null, durationMs: Long = 200_000) =
        Track(TrackId.youtube(id), title, artist, durationMs = durationMs, playbackRef = id, genres = genres, energy = energy, year = year, variant = variant)

    private fun listen(t: Track, at: Long, listenedMs: Long = 200_000, completed: Boolean = true, skipped: Boolean = false) =
        PlayEvent(t.id, t.artistKey, at, listenedMs, 200_000, completed, skipped)

    /** Plays [tracks] back to back starting at [start] (each 200 s long). */
    private fun session(tracks: List<Track>, start: Long): List<PlayEvent> = tracks.mapIndexed { i, t -> listen(t, start + i * 200_000L) }

    private fun input(events: List<PlayEvent>, tracks: Collection<Track>, zone: ZoneId = utc, likes: Map<TrackId, Long> = emptyMap(), feedback: Feedback = Feedback.None, playlists: List<PlaylistSignal> = emptyList(), searches: List<SearchIntent> = emptyList(), traits: Map<TrackId, AudioTraits> = emptyMap(), cold: ColdStart = ColdStart()) =
        RecInput(events, tracks.associateBy { it.id }, likes, playlists, searches, traits, feedback, cold, zone)

    // ------------------------------------------------------------------ signals

    @Test fun `engagement - early skip is strongly negative, late skip weakly, replay strongly positive`() {
        val t = song("a", "A")
        val early = Engagement.weight(listen(t, NOW, 10_000, completed = false, skipped = true))
        val late = Engagement.weight(listen(t, NOW, 120_000, completed = false, skipped = true))
        val full = Engagement.weight(listen(t, NOW))
        val replay = Engagement.weight(listen(t, NOW), replay = true)
        assertTrue(early < late && late < 0 && full > 0 && replay > full)
        assertEquals(-1.0, early, 1e-9)
        val evs = listOf(listen(t, NOW), listen(t, NOW + 300_000), listen(t, NOW + 10 * 3_600_000))
        assertEquals(listOf(false, true, false), Engagement.replays(evs).toList())
    }

    @Test fun `sessions split on 30 minute gaps measured from the end of the previous play`() {
        val t = song("a", "A")
        val evs = listOf(listen(t, 0), listen(t, 200_000 + 29 * min), listen(t, 200_000 + 29 * min + 200_000 + 31 * min))
        val s = Sessionizer.split(evs.shuffled(Random(3)))
        assertEquals(listOf(2, 1), s.map { it.events.size })
        assertTrue(Sessionizer.isOpen(s.last(), s.last().end + 10 * min))
        assertFalse(Sessionizer.isOpen(s.last(), s.last().end + 31 * min))
    }

    @Test fun `context buckets - Friday late night counts as weekend, Monday 1 am as Sunday night`() {
        fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("weekday evenings", ListeningContext.at(at(2025, 10, 8, 19), utc).label) // Wednesday
        assertEquals("weekend late nights", ListeningContext.at(at(2025, 10, 10, 23), utc).label) // Friday 11 pm
        assertEquals("weekend late nights", ListeningContext.at(at(2025, 10, 13, 1), utc).label) // Monday 1 am → Sunday night
        assertEquals("weekend mornings", ListeningContext.at(at(2025, 10, 11, 9), utc).label)
    }

    // ------------------------------------------------------------------ co-occurrence

    @Test fun `co-occurrence - PMI, cosine and Markov match hand computation`() {
        val co = CoOccurrence<String>(window = 4, alpha = 1.0)
        co.addSession(listOf("A", "B"))
        co.addSession(listOf("A", "B"))
        co.addSession(listOf("A", "C"))
        co.addSession(listOf("D", "E"))
        // c(A,B)=2, c(A,C)=1, c(D,E)=1; marginals A=3 B=2 C=1 D=1 E=1; total T = 2·(2+1+1) = 8.
        assertEquals(2.0, co.pair("A", "B"), 1e-9)
        assertEquals(ln(2.0 * 8 / (3 * 2)), co.pmi("A", "B"), 1e-9)
        assertEquals(ln(1.0 * 8 / (3 * 1)), co.pmi("A", "C"), 1e-9)
        assertEquals(0.0, co.pmi("A", "D"), 1e-9)
        assertEquals(2 / sqrt(3.0 * 2.0), co.cosine("A", "B"), 1e-9)
        assertEquals(2.0 / 3, co.markov("A", "B"), 1e-9)
        assertEquals(0.0, co.markov("B", "A"), 1e-9)
        assertEquals("B", co.neighbours("A").first().key)
    }

    @Test fun `co-occurrence - window weights by distance and keeps the best weight per session`() {
        val co = CoOccurrence<String>(window = 2, alpha = 1.0)
        co.addSession(listOf("X", "Y", "Z", "W"))
        assertEquals(1.0, co.pair("X", "Y"), 1e-9)
        assertEquals(0.5, co.pair("X", "Z"), 1e-9)
        assertEquals(0.0, co.pair("X", "W"), 1e-9) // outside the window
        co.addSession(listOf("P", "Q", "P", "Q"))
        assertEquals(1.0, co.pair("P", "Q"), 1e-9) // once per session, strongest weight
        assertEquals(co.pair("Q", "P"), co.pair("P", "Q"), 1e-12)
    }

    @Test fun `incremental session index equals a full rebuild and never commits the open session`() {
        val ts = (1..12).map { song("s$it", "Ar${it % 4}") }
        val evs = (0 until 30).flatMap { d -> session(listOf(ts[d % 12], ts[(d + 1) % 12], ts[(d + 3) % 12]), NOW - (40 - d) * DAY) }
        val full = SessionIndex().also { it.update(evs, NOW) }
        val inc = SessionIndex()
        val cut = evs.size / 2
        inc.update(evs.take(cut), evs[cut - 1].startedAt + 1)
        inc.update(evs, NOW)
        for (a in ts) for (b in ts) assertEquals(full.tracks.pair(a.id, b.id), inc.tracks.pair(a.id, b.id), 1e-9)
        val live = SessionIndex()
        val open = live.update(evs + session(listOf(ts[0], ts[5]), NOW - 5 * min), NOW)
        assertNotNull(open)
        assertEquals(full.tracks.pair(ts[0].id, ts[5].id), live.tracks.pair(ts[0].id, ts[5].id), 1e-9)
    }

    // ------------------------------------------------------------------ graph + embedding

    @Test fun `personalised PageRank ranks songs from the same sessions above others`() {
        val b = WalkGraph.Builder()
        val songs = (1..6).map { "t:s$it" }
        for (s in songs) b.edge(s, WalkGraph.genre("pop"), 0.6)
        b.edge("t:s1", "t:s2", 2.0); b.edge("t:s2", "t:s3", 2.0); b.edge("t:s1", "t:s3", 1.0)
        b.edge("t:s4", "t:s5", 2.0); b.edge("t:s5", "t:s6", 2.0)
        val g = b.build()
        val r = g.personalizedPageRank(mapOf("t:s1" to 1.0))
        assertEquals(1.0, r.sum(), 1e-6)
        val score = g.scores(r, "t:")
        for (near in listOf("s2", "s3")) for (far in listOf("s4", "s5", "s6")) assertTrue("$near vs $far", score[near]!! > score[far]!!)
        // Forward push approximates the exact walk closely and keeps the same order.
        val push = g.pushRank(mapOf("t:s1" to 1.0), "t:", epsilon = 1e-7)
        for ((k, v) in score) assertEquals(k, v, push[k] ?: 0.0, 1e-4)
        for (near in listOf("s2", "s3")) for (far in listOf("s4", "s5", "s6")) assertTrue(push[near]!! > push[far]!!)
    }

    @Test fun `PPMI embedding separates two listening worlds and k-means recovers them`() {
        val co = CoOccurrence<String>()
        val rng = Random(5)
        val left = (0 until 8).map { "L$it" }
        val right = (0 until 8).map { "R$it" }
        repeat(60) { co.addSession(left.shuffled(rng).take(4)); co.addSession(right.shuffled(rng).take(4)) }
        val items = left + right
        val emb = SpectralEmbedding.embed(SpectralEmbedding.ppmiMatrix(co, items), dim = 4, seed = 1)
        val within = VectorMath.cosine(emb[0], emb[1])
        val across = VectorMath.cosine(emb[0], emb[8])
        assertTrue("within=$within across=$across", within > across + 0.3)
        val assign = KMeans.cluster(emb.toList(), 2, seed = 3)
        assertTrue((0 until 8).all { assign[it] == assign[0] })
        assertTrue((8 until 16).all { assign[it] == assign[8] })
        assertTrue(assign[0] != assign[8])
    }

    // ------------------------------------------------------------------ content

    @Test fun `content similarity - half-time tempo and Camelot neighbours count as close`() {
        val a = ContentFeatures("x", setOf("pop"), 2019, 0.6f, 120f, 9 + 12, null, null) // A minor (8A)
        val b = ContentFeatures("y", setOf("pop"), 2019, 0.62f, 60f, 0, null, null) // C major (8B), half time
        val c = ContentFeatures("z", setOf("metal"), 1975, 0.95f, 171f, 6, null, null) // F♯ major, far away
        val ab = ContentSimilarity.similarity(a, b)
        assertTrue(ab.score > ContentSimilarity.similarity(a, c).score)
        assertEquals(Aspect.KEY_AND_TEMPO, ab.aspect)
        assertEquals(1.0, ContentSimilarity.tempo(120f, 60f)!!, 0.1)
        assertTrue(ContentSimilarity.key(21, 0)!! >= 0.85)
        assertEquals(null, ContentSimilarity.tempo(0f, 120f))
    }

    // ------------------------------------------------------------------ taste

    @Test fun `taste - short-term reflects this week, long-term remembers, skips hurt`() {
        val old = song("o", "Old Fav")
        val new = song("n", "New Crush")
        val skipped = song("k", "Skipped")
        val evs = (0 until 20).map { listen(old, NOW - (150 + it) * DAY) } +
            (0 until 5).map { listen(new, NOW - it * DAY) } +
            (0 until 5).map { listen(skipped, NOW - it * DAY, 5_000, completed = false, skipped = true) }
        val m = TasteModelBuilder.build(input(evs, listOf(old, new, skipped)), NOW)
        assertTrue(m.trackShortNorm(new.id) > m.trackShortNorm(old.id))
        assertTrue(m.trackLong[old.id]!! > 0)
        assertTrue(m.artistShort["newcrush"]!! > (m.artistShort["oldfav"] ?: 0.0))
        assertTrue(m.trackLong[skipped.id]!! < 0)
        assertTrue(m.artistEarlySkipRate["skipped"]!! > 0.5)
        // Decay: the same plays a month older weigh less.
        val older = TasteModelBuilder.build(input(evs.map { it.copy(startedAt = it.startedAt - 30 * DAY) }, listOf(old, new, skipped)), NOW)
        assertTrue(older.trackLong[new.id]!! < m.trackLong[new.id]!!)
        assertTrue(older.trackShort[new.id]!! < m.trackShort[new.id]!! / 10)
    }

    @Test fun `taste - user playlists weigh more than imports and likes are durable`() {
        val a = song("a", "A"); val b = song("b", "B"); val c = song("c", "C")
        val m = TasteModelBuilder.build(
            input(
                emptyList(), listOf(a, b, c), likes = mapOf(c.id to NOW - 400 * DAY),
                playlists = listOf(PlaylistSignal("u", "Mine", true, listOf(a.id to NOW)), PlaylistSignal("i", "Import", false, listOf(b.id to NOW))),
            ),
            NOW,
        )
        assertTrue(m.trackLong[a.id]!! > m.trackLong[b.id]!!)
        assertTrue(m.trackLong[c.id]!! > m.trackLong[a.id]!!)
        assertTrue(m.familiarity(c.id) > 0.4)
    }

    // ------------------------------------------------------------------ bandit

    @Test fun `bandit - rewards move the arm up, skips move it down, encoding round-trips`() {
        var b = SourceBandit()
        val m0 = b.stats(RecSource.GRAPH).mean
        b = b.update(RecSource.GRAPH, 1.0)
        val m1 = b.stats(RecSource.GRAPH).mean
        b = b.update(RecSource.GRAPH, 1.0)
        assertTrue(m1 > m0 && b.stats(RecSource.GRAPH).mean > m1)
        val down = SourceBandit().update(RecSource.CONTENT, 0.0)
        assertTrue(down.stats(RecSource.CONTENT).mean < m0)
        // Monotone in the reward.
        val means = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { SourceBandit().update(RecSource.ARTIST, it).stats(RecSource.ARTIST).mean }
        assertEquals(means.sorted(), means)
        val decoded = SourceBandit.decode(b.encode())
        assertEquals(b.stats(RecSource.GRAPH).alpha, decoded.stats(RecSource.GRAPH).alpha, 1e-9)
        assertEquals(2.0, SourceBandit.decode("garbage").stats(RecSource.GRAPH).alpha, 1e-9)
        // Thompson samples are deterministic per seed; means without a generator.
        assertEquals(b.multipliers(Random(9)), b.multipliers(Random(9)))
        assertTrue(b.multipliers()[RecSource.GRAPH]!! > b.multipliers()[RecSource.CONTENT]!!)
        // A well-rewarded arm is sampled above a punished one most of the time.
        var good = SourceBandit()
        repeat(30) { good = good.update(RecSource.SESSION, 1.0).update(RecSource.DISCOVERY, 0.0) }
        val rng = Random(1)
        val wins = (0 until 200).count { good.multipliers(rng).let { m -> m[RecSource.SESSION]!! > m[RecSource.DISCOVERY]!! } }
        assertTrue(wins > 190)
    }

    @Test fun `rewards settle on the first play after serving, expire unplayed`() {
        val t = song("a", "A"); val u = song("b", "B"); val v = song("c", "C")
        val imps = listOf(
            Impression(t.id, RecSource.GRAPH, NOW - DAY, "home"),
            Impression(u.id, RecSource.CONTENT, NOW - DAY, "radio"),
            Impression(v.id, RecSource.SESSION, NOW - 5 * DAY, "radio"),
            Impression(v.id, RecSource.SESSION, NOW - 1000, "home"),
        )
        val evs = listOf(listen(t, NOW - DAY + 1000), listen(u, NOW - DAY + 5000, 8_000, completed = false, skipped = true))
        val s = RewardModel.settle(imps, evs, NOW, penaliseUnplayed = setOf("radio"))
        assertEquals(listOf(RecSource.GRAPH to 1.0, RecSource.CONTENT to 0.0, RecSource.SESSION to 0.2), s.outcomes)
        assertEquals(1, s.pending.size)
        assertEquals(imps[0], Impression.decode(imps[0].encode()))
    }

    // ------------------------------------------------------------------ re-ranking

    @Test fun `MMR keeps artists apart, caps per artist and ends early rather than break the rule`() {
        val recs = (0 until 40).map { i ->
            val artist = if (i < 30) "Dominant" else "Other ${i % 3}"
            Recommendation(song("m$i", artist), 100.0 - i, RecSource.GRAPH, Explain.forYou())
        }
        val out = Reranker.rerank(recs, 20, { a, b -> if (a.artistKey == b.artistKey) 1.0 else 0.0 }, artistGap = 3, maxPerArtist = 3)
        for (i in out.indices) for (j in i + 1 until minOf(out.size, i + 3)) assertTrue(out[i].track.artistKey != out[j].track.artistKey)
        assertTrue(out.groupingBy { it.track.artistKey }.eachCount().values.all { it <= 3 })
        assertTrue(out.size <= 12) // 4 artists × 3
        // History continues the spacing across the queue boundary.
        val history = listOf(song("h", "Other 0"))
        val next = Reranker.rerank(recs, 3, { _, _ -> 0.0 }, artistGap = 3, maxPerArtist = 3, history = history)
        assertTrue(next.take(2).none { it.track.artistKey == "other0" })
    }

    @Test fun `dedupe keeps one upload per song, preferring what was played then the preferred type`() {
        val video = song("v1", "Arijit Singh", title = "Kesariya (Official Video)", variant = MediaVariant.VIDEO)
        val audio = song("v2", "Arijit Singh", title = "Kesariya", variant = MediaVariant.SONG)
        val recs = listOf(Recommendation(video, 2.0, RecSource.GRAPH, Explain.forYou()), Recommendation(audio, 1.0, RecSource.CONTENT, Explain.forYou()))
        val kept = Reranker.dedupe(recs, MediaVariant.SONG)
        assertEquals(listOf(audio.id), kept.map { it.track.id })
        assertEquals(2.0, kept[0].score, 1e-9)
        assertEquals(listOf(video.id), Reranker.dedupe(recs, MediaVariant.SONG, plays = { if (it == video.id) 3 else 0 }).map { it.track.id })
    }

    // ------------------------------------------------------------------ end to end

    /** A small world: two taste clusters (Bollywood and Rock), sessions that stay within one. */
    private inner class World(feedback: Feedback = Feedback.None, extraPool: List<Track> = emptyList(), now: Long = NOW) {
        val bolly = listOf("Arijit Singh", "Pritam", "Shreya Ghoshal").flatMap { a -> (1..6).map { song("${a.take(3)}$it", a, listOf("bollywood"), 0.45f, 2019) } }
        val rock = listOf("Foo Fighters", "Muse", "Arctic Monkeys").flatMap { a -> (1..6).map { song("${a.take(3)}$it", a, listOf("rock"), 0.8f, 2008) } }
        val fresh = listOf(
            song("newb1", "Sachet Tandon", listOf("bollywood"), 0.45f, 2023),
            song("newr1", "Royal Blood", listOf("rock"), 0.8f, 2017),
            song("mix1", "DJ", listOf("bollywood"), 0.5f, 2022, title = "Bollywood Nonstop Mashup 2024", durationMs = 3_600_000),
        ) + extraPool
        val events: List<PlayEvent> = buildList {
            val rng = Random(42)
            for (d in 0 until 60) {
                val pool = if (d % 2 == 0) bolly else rock
                val start = now - (60 - d) * DAY + 19 * 3_600_000L
                addAll(session(pool.shuffled(rng).take(6), start))
            }
        }
        val all = bolly + rock + fresh
        val model = RecModel.build(input(events, all, feedback = feedback), now)
        val engine = RecEngine(model, all)
    }

    @Test fun `radio stays in the seed's world and explains itself`() {
        val w = World()
        val seed = w.bolly.first()
        val radio = w.engine.radio(seed, 12)
        assertTrue(radio.isNotEmpty())
        assertTrue(radio.none { it.track.id == seed.id })
        val bollyIds = (w.bolly + w.fresh).map { it.id }.toSet()
        val inWorld = radio.take(8).count { it.track.id in bollyIds }
        assertTrue("in-world $inWorld of ${radio.take(8).map { it.track.artist }}", inWorld >= 7)
        assertTrue(radio.all { it.explanation.text.isNotBlank() })
        assertTrue(radio.any { it.explanation.kind == ExplanationKind.CO_PLAYED || it.explanation.kind == ExplanationKind.SESSION_FOLLOWS })
    }

    @Test fun `explicit feedback and compilations never come back on any surface`() {
        val blocked = "pritam"
        val noThanks = TrackId.youtube("Ari1")
        val fb = Feedback.from(listOf(FeedbackRow(blocked, FeedbackKind.ARTIST_BLOCKED, NOW), FeedbackRow(noThanks.value, FeedbackKind.NOT_INTERESTED, NOW)))
        val w = World(fb)
        val all = w.engine.forYouNow(30) + w.engine.radio(w.bolly[1], 30) + w.engine.freshFinds(30) + w.engine.rediscover(30) +
            w.engine.continuation(w.bolly.take(3), 20) + w.engine.dailyMixes().flatMap { it.tracks } + w.engine.artistRadio("arijitsingh", 30)
        assertTrue(all.isNotEmpty())
        assertTrue(all.none { it.track.artistKey == blocked })
        assertTrue(all.none { it.track.id == noThanks })
        assertTrue(all.none { it.track.title.contains("Mashup") })
    }

    @Test fun `fresh finds are never-played songs, rediscover is old love`() {
        val w = World()
        val finds = w.engine.freshFinds(10)
        val played = w.events.map { it.trackId }.toSet()
        assertTrue(finds.isNotEmpty())
        assertTrue(finds.none { it.track.id in played })
        assertTrue(finds.all { it.explanation.text.startsWith("New to you") || it.explanation.kind == ExplanationKind.SOUND_ALIKE || it.explanation.kind == ExplanationKind.CO_PLAYED })

        // Played a lot in spring, nothing since.
        val old = song("old1", "Lata Mangeshkar", listOf("bollywood"), 0.3f, 1975)
        val oldEvents = (0 until 12).map { listen(old, NOW - 200 * DAY + it * DAY) }
        val m = RecModel.build(input(w.events + oldEvents, w.all + old), NOW)
        val re = RecEngine(m, w.all + old).rediscover(10)
        val hit = re.firstOrNull { it.track.id == old.id }
        assertNotNull(hit)
        assertEquals(ExplanationKind.REDISCOVER, hit!!.explanation.kind)
        // 12 plays over 12 days spanning a month end: the copy names the busier month and its count.
        assertTrue(hit.explanation.text, Regex("""You played this (\d+) times in \w+.*""").matches(hit.explanation.text))
        assertTrue(Regex("""\d+""").find(hit.explanation.text)!!.value.toInt() >= 6)
    }

    @Test fun `daily mixes follow taste clusters and are named after their artists`() {
        val w = World()
        val mixes = w.engine.dailyMixes()
        assertTrue("got ${mixes.map { it.title }}", mixes.size >= 2)
        for (mix in mixes) {
            assertTrue(mix.title.startsWith("Daily Mix · "))
            val genres = mix.tracks.map { it.track.genres.first() }.toSet()
            assertEquals("mix ${mix.title} mixes genres $genres", 1, genres.size)
            for (i in 1 until mix.tracks.size) assertTrue(mix.tracks[i].track.artistKey != mix.tracks[i - 1].track.artistKey)
        }
        assertEquals(mixes.flatMap { m -> m.tracks.map { it.track.id } }.size, mixes.flatMap { m -> m.tracks.map { it.track.id } }.toSet().size)
        // Deterministic.
        assertEquals(mixes, World().engine.dailyMixes())
    }

    @Test fun `for you right now follows the open session and context`() {
        val w = World()
        val liveStart = NOW - 10 * min
        val live = session(w.rock.take(2), liveStart)
        val m = RecModel.build(input(w.events + live, w.all), NOW)
        assertNotNull(m.openSession)
        val recs = RecEngine(m, w.all).forYouNow(10)
        assertTrue(recs.none { it.track.id in w.rock.take(2).map { t -> t.id } })
        val rockIds = (w.rock + w.fresh).map { it.id }.toSet()
        assertTrue(recs.take(5).count { it.track.id in rockIds } >= 4)
        for (i in recs.indices) for (j in i + 1 until minOf(recs.size, i + 3)) assertTrue(recs[i].track.artistKey != recs[j].track.artistKey)
    }

    @Test fun `endless continuation skips what is queued and keeps spacing from the queue`() {
        val w = World()
        val queue = w.rock.take(4)
        val next = w.engine.continuation(queue, 10, exclude = queue.map { it.id }.toSet())
        assertTrue(next.isNotEmpty())
        assertTrue(next.none { it.track.id in queue.map { t -> t.id } })
        assertTrue(next.take(2).none { it.track.artistKey == queue.last().artistKey })
    }

    @Test fun `explanations - context claim needs evidence, sound-alike names the measured aspect`() {
        val w = World()
        val ctx = ListeningContext.at(NOW - 3 * DAY + 19 * 3_600_000L, utc)
        val holds = w.model.taste.contextClaimHolds("arijitsingh", ctx)
        assertTrue(holds == (w.model.taste.contextLift("arijitsingh", ctx) >= 1.1))
        assertFalse(w.model.taste.contextClaimHolds("nobody", ctx))
        val e = Explain.soundAlike(Aspect.KEY_AND_TEMPO, "Kesariya", "Arijit Singh", sameKey = true, anchor = TrackId("yt:k"))
        assertEquals("Same key and tempo as Kesariya", e.text)
        assertEquals("Compatible key and tempo with Kesariya", Explain.soundAlike(Aspect.KEY_AND_TEMPO, "Kesariya", "x", false, TrackId("yt:k")).text)
        val r = Explain.rediscover(14, LocalDateTime.of(2025, 3, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), null, NOW, utc, Locale.ENGLISH)
        assertEquals("You played this 14 times in March — not lately", r.text)
    }

    @Test fun `analysed local songs explain key and tempo matches`() {
        val a = song("loc1", "Local A", listOf("house"), 0.7f, 2020)
        val b = song("loc2", "Local B", listOf("house"), 0.7f, 2020)
        val c = song("loc3", "Local C", listOf("house"), 0.7f, 2020)
        val traits = mapOf(a.id to AudioTraits(124f, 21, 0.7f), b.id to AudioTraits(124f, 21, 0.7f), c.id to AudioTraits(90f, 6, 0.7f))
        val m = RecModel.build(input(listOf(listen(a, NOW - DAY)), listOf(a, b, c), traits = traits), NOW)
        val radio = RecEngine(m, listOf(a, b, c)).radio(a, 5)
        assertEquals(b.id, radio.first().track.id)
        assertEquals("Same key and tempo as ${a.title}", radio.first().explanation.text)
    }

    @Test fun `search intent and cold-start picks surface with honest reasons`() {
        val t = song("int1", "Diljit Dosanjh", listOf("punjabi"), title = "Lover")
        val s = song("seed1", "AP Dhillon", listOf("punjabi"), title = "Brown Munde")
        val m = RecModel.build(
            input(emptyList(), listOf(t, s), searches = listOf(SearchIntent("diljit lover", NOW - 3_600_000)), cold = ColdStart(listOf("AP Dhillon"))),
            NOW,
        )
        val recs = RecEngine(m, listOf(t, s)).forYouNow(5)
        assertEquals("Because you searched for “diljit lover”", recs.first { it.track.id == t.id }.explanation.text)
        assertEquals("Because you picked AP Dhillon when you started", recs.first { it.track.id == s.id }.explanation.text)
    }

    // ------------------------------------------------------------------ top songs this month

    @Test fun `top songs this month uses the calendar month in the listener's time zone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        fun at(d: Int, m: Int, h: Int, mi: Int) = LocalDateTime.of(2025, m, d, h, mi).atZone(kolkata).toInstant().toEpochMilli()
        val now = at(15, 10, 12, 0)
        val a = song("a", "A"); val b = song("b", "B"); val c = song("c", "C"); val d = song("d", "D"); val e = song("e", "E")
        val evs = listOf(
            listen(a, at(30, 9, 23, 50)), listen(a, at(1, 10, 0, 5)), listen(a, at(2, 10, 8, 0)), // only 2 count (Sep 30 local is out)
            listen(b, at(1, 10, 0, 1), 300_000), listen(b, at(3, 10, 9, 0), 300_000), // 600 s
            listen(c, at(4, 10, 9, 0)), listen(c, at(5, 10, 9, 0)), listen(c, at(6, 10, 9, 0)), // 600 s, 3 plays → beats b on tiebreak
            listen(d, at(7, 10, 9, 0)), listen(d, at(8, 10, 9, 0), 20_000), // second play too short → only 1 counts
            listen(e, at(9, 10, 9, 0)), // single play
            listen(e, at(16, 10, 9, 0)), // in the future
        )
        val top = SmartPlaylistEngine.topThisMonth(evs, now, kolkata)
        assertEquals(listOf(c.id, b.id, a.id), top)
        // In UTC, Oct 1 00:01–00:05 IST is still September: a drops to one play, b to one.
        assertEquals(listOf(c.id), SmartPlaylistEngine.topThisMonth(evs, now, utc))
        assertEquals(top, SmartPlaylistEngine.compute(SmartPlaylist.TOP_THIS_MONTH, evs, emptySet(), now, kolkata))
        assertEquals(SmartPlaylist.TOP_THIS_MONTH, SmartPlaylist.entries.first())
    }

    // ------------------------------------------------------------------ quota

    @Test fun `discovery spends at most two searches a day, spaced, only on NORMAL quota and online`() {
        var ledger = DiscoveryLedger()
        assertFalse(DiscoveryPolicy.mayQuery(ledger, QuotaState.CONSERVE, true, NOW, utc))
        assertFalse(DiscoveryPolicy.mayQuery(ledger, QuotaState.EXHAUSTED, true, NOW, utc))
        assertFalse(DiscoveryPolicy.mayQuery(ledger, QuotaState.NORMAL, false, NOW, utc))
        assertTrue(DiscoveryPolicy.mayQuery(ledger, QuotaState.NORMAL, true, NOW, utc))
        ledger = DiscoveryPolicy.record(ledger, NOW, utc)
        assertFalse(DiscoveryPolicy.mayQuery(ledger, QuotaState.NORMAL, true, NOW + 3_600_000, utc))
        ledger = DiscoveryPolicy.record(ledger, NOW + 4 * 3_600_000, utc)
        val sameDayLater = NOW + 8 * 3_600_000
        if (DiscoveryPolicy.dayKey(sameDayLater, utc) == ledger.day) assertFalse(DiscoveryPolicy.mayQuery(ledger, QuotaState.NORMAL, true, sameDayLater, utc))
        assertTrue(DiscoveryPolicy.mayQuery(ledger, QuotaState.NORMAL, true, NOW + 2 * DAY, utc))
        assertEquals(ledger, DiscoveryLedger.decode(ledger.encode()))
        val w = World()
        val qs = DiscoveryPolicy.queries(w.model)
        assertTrue(qs.isNotEmpty() && qs.size <= 4)
        assertEquals(qs, DiscoveryPolicy.queries(w.model))
    }

    // ------------------------------------------------------------------ performance

    @Test fun `full refresh on 20k events stays fast`() {
        val rng = Random(1)
        val artists = (0 until 300).map { "Artist $it" }
        val genres = listOf("pop", "rock", "bollywood", "hip hop", "edm", "indie", "jazz", "punjabi")
        val catalogue = (0 until 6000).map { i -> song("p$i", artists[i % artists.size], listOf(genres[(i / 7) % genres.size]), (i % 10) / 10f, 1990 + i % 35) }
        val events = ArrayList<PlayEvent>(20_000)
        var at = NOW - 365 * DAY
        while (events.size < 20_000) {
            val home = rng.nextInt(catalogue.size)
            repeat(rng.nextInt(4, 16)) {
                val t = catalogue[(home + rng.nextInt(60)) % catalogue.size]
                val skip = rng.nextInt(10) == 0
                events += listen(t, at, if (skip) 8_000 else 200_000, completed = !skip, skipped = skip)
                at += 200_000
            }
            at += rng.nextLong(40 * min, 20 * 3_600_000L)
        }
        val inp = input(events.take(20_000), catalogue)
        fun refresh(): Long {
            val t0 = System.nanoTime()
            val model = RecModel.build(inp, NOW)
            val engine = RecEngine(model, catalogue)
            engine.forYouNow(20); engine.freshFinds(20); engine.rediscover(20); engine.dailyMixes(); engine.radio(catalogue[0], 25)
            return (System.nanoTime() - t0) / 1_000_000
        }
        refresh() // warm-up (JIT)
        val ms = (0 until 3).minOf { refresh() }
        // Steady state, as the app runs it: session index, graph and single-check caches carried over
        // between refreshes; Home's three per-refresh surfaces (daily mixes are cached for hours).
        val index = SessionIndex(); val graphs = GraphCache(); val singles = SinglesCache()
        fun steady(): Long {
            val t0 = System.nanoTime()
            val engine = RecEngine(RecModel.build(inp, NOW, index), catalogue, singles = singles, graphCache = graphs)
            engine.forYouNow(20); engine.freshFinds(20); engine.rediscover(20)
            return (System.nanoTime() - t0) / 1_000_000
        }
        repeat(3) { steady() }
        val steadyMs = (0 until 5).minOf { steady() }
        println("Cold refresh (20k events, 6k catalogue, 5 surfaces, no caches): $ms ms; steady refresh: $steadyMs ms")
        assertTrue("took $ms ms", ms < 3_000)
        assertTrue("steady took $steadyMs ms", steadyMs < 1_000)
    }
}
