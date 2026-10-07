package com.arnav.music.domain.recommend

import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Mood
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** A recent search, normalised for matching against tracks. */
data class Intent(val display: String, val tokens: Set<String>, val artistKey: String, val weight: Double)

/**
 * The listener's taste at two time scales plus context, built from every local signal:
 * plays (weighted by [Engagement], decayed by [Decay.Short] and [Decay.Long]), likes, playlist
 * membership (user-made > imported), recent searches, onboarding picks and explicit feedback.
 */
class TasteModel(
    val trackLong: Map<TrackId, Double>,
    val trackShort: Map<TrackId, Double>,
    val artistLong: Map<String, Double>,
    val artistShort: Map<String, Double>,
    val genre: Map<String, Double>,
    val decade: Map<Int, Double>,
    val energyMean: Double?,
    val energySd: Double?,
    /** Per [ListeningContext.index]: artist → decayed positive listening weight. */
    val contextArtists: Array<Map<String, Double>>,
    /** Per context: plays per artist (undecayed), for honest "you play X on …" copy. */
    val contextArtistPlays: Array<Map<String, Int>>,
    val contextTotals: DoubleArray,
    val contextEnergy: Array<Double?>,
    val artistEarlySkipRate: Map<String, Double>,
    val trackEarlySkips: Map<TrackId, Int>,
    val positivePlays: Map<TrackId, Int>,
    val lastPlayed: Map<TrackId, Long>,
    val firstPlayed: Map<TrackId, Long>,
    val likes: Map<TrackId, Long>,
    val playlistsOf: Map<TrackId, List<PlaylistSignal>>,
    val intents: List<Intent>,
    val coldArtists: Set<String>,
    val coldEnergy: Double?,
    val artistNames: Map<String, String>,
    val eventCount: Int,
) {
    private val maxTrackLong = trackLong.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val maxTrackShort = trackShort.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val maxArtistLong = artistLong.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val maxArtistShort = artistShort.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val maxGenre = genre.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val maxDecade = decade.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val globalArtist: Map<String, Double> by lazy {
        val m = HashMap<String, Double>()
        for (c in contextArtists) for ((k, v) in c) m.merge(k, v, Double::plus)
        m
    }
    private val globalTotal: Double get() = contextTotals.sum()

    /** How much the listener has kept going back to this artist: 0..1 (long-term 60 %, this week 40 %). */
    fun artistAffinity(key: String): Double =
        0.6 * max(0.0, artistLong[key] ?: 0.0) / maxArtistLong + 0.4 * max(0.0, artistShort[key] ?: 0.0) / maxArtistShort

    fun trackLongNorm(id: TrackId): Double = max(0.0, trackLong[id] ?: 0.0) / maxTrackLong
    fun trackShortNorm(id: TrackId): Double = max(0.0, trackShort[id] ?: 0.0) / maxTrackShort

    fun genreAffinity(genres: Collection<String>): Double =
        genres.maxOfOrNull { max(0.0, genre[it.lowercase()] ?: 0.0) / maxGenre } ?: 0.0

    fun decadeAffinity(year: Int?): Double = year?.let { max(0.0, decade[it / 10 * 10] ?: 0.0) / maxDecade } ?: 0.0

    /** 0 for never heard, approaching 1 with repeated full listens (likes count as two). */
    fun familiarity(id: TrackId): Double {
        val n = (positivePlays[id] ?: 0) + if (id in likes) 2 else 0
        return 1 - exp(-n / 3.0)
    }

    fun played(id: TrackId): Boolean = lastPlayed.containsKey(id)

    /** Share of this context's listening that went to [artist], scaled by confidence in the context. */
    private val contextTop = DoubleArray(ListeningContext.COUNT) { c -> contextArtists[c].values.maxOrNull() ?: 0.0 }

    fun contextAffinity(artist: String, ctx: ListeningContext): Double {
        val total = contextTotals[ctx.index]
        val top = contextTop[ctx.index]
        if (total <= 0 || top <= 0) return 0.0
        return ((contextArtists[ctx.index][artist] ?: 0.0) / top) * (total / (total + 3.0))
    }

    /** The [n] artists most played in [ctx], strongest first. */
    fun topContextArtists(ctx: ListeningContext, n: Int): List<String> =
        contextArtists[ctx.index].entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(n).map { it.key }

    /** How much more this artist is played in [ctx] than overall (1 = no different). */
    fun contextLift(artist: String, ctx: ListeningContext): Double {
        val total = contextTotals[ctx.index]
        val g = globalArtist[artist] ?: return 0.0
        if (total <= 0 || g <= 0) return 0.0
        return ((contextArtists[ctx.index][artist] ?: 0.0) / total) / (g / globalTotal)
    }

    /**
     * True when "Because you play [artist] on [ctx]" is honest: at least 3 good plays in that context,
     * a real share of it, and more than the artist's usual share.
     */
    fun contextClaimHolds(artist: String, ctx: ListeningContext): Boolean {
        val plays = contextArtistPlays[ctx.index][artist] ?: 0
        val total = contextTotals[ctx.index]
        if (plays < 3 || total <= 0) return false
        val share = (contextArtists[ctx.index][artist] ?: 0.0) / total
        return share >= 0.08 && contextLift(artist, ctx) >= 1.1
    }

    /** Preferred energy in [ctx], falling back to overall and then to onboarding moods. */
    fun energyTarget(ctx: ListeningContext?): Double? = ctx?.let { contextEnergy[it.index] } ?: energyMean ?: coldEnergy

    private val artistsRanked: List<String> by lazy {
        (artistLong.keys + artistShort.keys).distinct().map { it to artistAffinity(it) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).map { it.first }
    }
    private val shortRanked: List<TrackId> by lazy { ranked(trackShort) }
    private val longRanked: List<TrackId> by lazy { ranked(trackLong) }

    private fun ranked(m: Map<TrackId, Double>): List<TrackId> =
        m.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<TrackId, Double>> { it.value }.thenBy { it.key.value }).map { it.key }

    fun topArtists(n: Int): List<String> = artistsRanked.take(n)
    fun topTracksShort(n: Int): List<TrackId> = shortRanked.take(n)
    fun topTracksLong(n: Int): List<TrackId> = longRanked.take(n)

    /** 1 with no history, fading to ~0 after ~100 listens: how much onboarding picks should matter. */
    val coldWeight: Double get() = exp(-eventCount / 40.0)

    /**
     * The recent search [track] answers best, with a 0..1 strength (recency-weighted), or null.
     * [artistKey] is the track's artist key (passed in because computing it isn't free).
     */
    fun intentMatch(track: Track, artistKey: String): Pair<Intent, Double>? {
        if (intents.isEmpty()) return null
        val text = (track.title + " " + track.artist).lowercase()
        var words: Set<String>? = null
        var best: Pair<Intent, Double>? = null
        for (i in intents) {
            val byArtist = i.artistKey.isNotEmpty() && i.artistKey == artistKey
            // Cheap substring pre-check before splitting the title into words.
            if (!byArtist && i.tokens.none { text.contains(it) }) continue
            val w = words ?: TasteModelBuilder.tokens(text).also { words = it }
            val s = when {
                w.containsAll(i.tokens) -> 1.0
                byArtist -> 0.8
                i.tokens.size >= 2 && i.tokens.count { it in w } >= i.tokens.size - 1 -> 0.5
                else -> 0.0
            } * i.weight
            if (s > 0 && (best == null || s > best.second)) best = i to s
        }
        return best
    }
}

object TasteModelBuilder {
    private const val DAY = 86_400_000L
    private const val INTENT_WINDOW_MS = 14 * DAY
    private val tokenSplit = Regex("""[^\p{L}\p{N}]+""")
    private val stop = setOf("the", "song", "songs", "official", "video", "audio", "lyrics", "lyric", "full", "new", "hd", "4k", "ft", "feat", "and", "of", "by")

    fun tokens(s: String): Set<String> = s.lowercase().split(tokenSplit).filter { it.length >= 2 && it !in stop }.toSet()

    fun build(input: RecInput, now: Long, artistKeys: ArtistKeyCache = ArtistKeyCache()): TasteModel {
        val events = input.events.sortedBy { it.startedAt }
        val trackLong = HashMap<TrackId, Double>()
        val trackShort = HashMap<TrackId, Double>()
        val artistLong = HashMap<String, Double>()
        val artistShort = HashMap<String, Double>()
        val genre = HashMap<String, Double>()
        val decade = HashMap<Int, Double>()
        val ctxArtists = Array(ListeningContext.COUNT) { HashMap<String, Double>() }
        val ctxPlays = Array(ListeningContext.COUNT) { HashMap<String, Int>() }
        val ctxTotals = DoubleArray(ListeningContext.COUNT)
        val ctxEnergySum = DoubleArray(ListeningContext.COUNT)
        val ctxEnergyW = DoubleArray(ListeningContext.COUNT)
        val artistPlays = HashMap<String, Int>()
        val artistEarly = HashMap<String, Int>()
        val earlySkips = HashMap<TrackId, Int>()
        val positive = HashMap<TrackId, Int>()
        val last = HashMap<TrackId, Long>()
        val first = HashMap<TrackId, Long>()
        val names = HashMap<String, String>()
        var eSum = 0.0; var eSq = 0.0; var eW = 0.0

        // Pass 1: per-event work goes into flat arrays indexed by track (one hash lookup per play).
        val index = HashMap<TrackId, Int>()
        val ids = ArrayList<TrackId>()
        val artistOfTrack = ArrayList<String>()
        val n = events.size
        val tl = DoubleArray(n); val ts = DoubleArray(n); val posW = DoubleArray(n)
        val plays = IntArray(n); val early = IntArray(n); val pos = IntArray(n)
        val lastAt = LongArray(n); val firstAt = LongArray(n); val lastEnd = LongArray(n)
        val ctxW = DoubleArray(n * ListeningContext.COUNT)
        val ctxN = IntArray(n * ListeningContext.COUNT)
        for (e in events) {
            val k = index.getOrPut(e.trackId) {
                ids += e.trackId; artistOfTrack += e.artistKey
                firstAt[ids.size - 1] = e.startedAt
                ids.size - 1
            }
            // Replay: the same song again within the hour of finishing it (see Engagement.replays).
            val replay = plays[k] > 0 && e.startedAt - lastEnd[k] <= Engagement.REPLAY_WINDOW_MS
            lastEnd[k] = e.startedAt + e.listenedMs
            val w = Engagement.weight(e, replay)
            val age = now - e.startedAt
            val dl = Decay.Long.factor(age)
            tl[k] += w * dl
            ts[k] += w * Decay.Short.factor(age)
            lastAt[k] = e.startedAt
            plays[k]++
            if (Engagement.isEarlySkip(e)) early[k]++ else if (w > 0) pos[k]++
            if (w > 0) {
                val c = ListeningContext.at(e.startedAt, input.zone).index
                posW[k] += w * dl
                ctxW[k * ListeningContext.COUNT + c] += w * dl
                ctxN[k * ListeningContext.COUNT + c]++
            }
        }
        // Pass 2: fold tracks into artist, genre, era and context aggregates.
        for (k in ids.indices) {
            val id = ids[k]
            val a = artistOfTrack[k]
            trackLong[id] = tl[k]; trackShort[id] = ts[k]
            last[id] = lastAt[k]; first[id] = firstAt[k]
            if (pos[k] > 0) positive[id] = pos[k]
            if (early[k] > 0) { earlySkips[id] = early[k]; artistEarly.merge(a, early[k], Int::plus) }
            artistLong.merge(a, tl[k], Double::plus)
            artistShort.merge(a, ts[k], Double::plus)
            artistPlays.merge(a, plays[k], Int::plus)
            val t = input.tracks[id]
            if (t != null) {
                names.putIfAbsent(a, t.artist)
                for (g in t.genres) genre.merge(g.lowercase(), tl[k], Double::plus)
                t.year?.takeIf { it in 1900..2100 }?.let { decade.merge(it / 10 * 10, tl[k], Double::plus) }
            }
            val en = input.traits[id]?.energy?.takeIf { it.isFinite() }?.toDouble() ?: t?.energy?.toDouble()
            if (en != null && posW[k] > 0) { eSum += en * posW[k]; eSq += en * en * posW[k]; eW += posW[k] }
            for (c in 0 until ListeningContext.COUNT) {
                val cw = ctxW[k * ListeningContext.COUNT + c]
                if (cw <= 0) continue
                ctxArtists[c].merge(a, cw, Double::plus)
                ctxPlays[c].merge(a, ctxN[k * ListeningContext.COUNT + c], Int::plus)
                ctxTotals[c] += cw
                if (en != null) { ctxEnergySum[c] += en * cw; ctxEnergyW[c] += cw }
            }
        }

        // Likes: durable and strong. Recent likes also count as short-term taste.
        for ((id, at) in input.likes) {
            if (id in input.feedback.notInterested) continue
            val t = input.tracks[id]
            val ak = t?.let { artistKeys.of(it.artist) }
            trackLong.merge(id, 2.0, Double::plus)
            trackShort.merge(id, 1.5 * Decay.Short.factor(now - at), Double::plus)
            if (t != null && ak != null) {
                artistLong.merge(ak, 1.5, Double::plus)
                artistShort.merge(ak, 1.0 * Decay.Short.factor(now - at), Double::plus)
                names.putIfAbsent(ak, t.artist)
                for (g in t.genres) genre.merge(g.lowercase(), 0.8, Double::plus)
                t.year?.takeIf { it in 1900..2100 }?.let { decade.merge(it / 10 * 10, 0.5, Double::plus) }
            }
        }

        // Playlist membership: a user-made playlist is a deliberate choice; an import much less so.
        val playlistsOf = HashMap<TrackId, MutableList<PlaylistSignal>>()
        for (p in input.playlists) {
            val w = if (p.userMade) 0.8 else 0.25
            for ((id, addedAt) in p.tracks) {
                playlistsOf.getOrPut(id) { ArrayList() } += p
                if (id in input.feedback.notInterested) continue
                trackLong.merge(id, w, Double::plus)
                trackShort.merge(id, w * Decay.Short.factor(now - addedAt), Double::plus)
                val t = input.tracks[id] ?: continue
                val ak = artistKeys.of(t.artist)
                names.putIfAbsent(ak, t.artist)
                artistLong.merge(ak, w * 0.5, Double::plus)
                for (g in t.genres) genre.merge(g.lowercase(), w * 0.4, Double::plus)
            }
        }
        for (list in playlistsOf.values) list.sortByDescending { it.userMade }

        // "More like this" works like an extra-strong recent like.
        for ((id, at) in input.feedback.moreLikeThis) {
            trackShort.merge(id, 2.0 * Decay.Short.factor(now - at), Double::plus)
            trackLong.merge(id, 1.0, Double::plus)
            input.tracks[id]?.let { t -> artistShort.merge(artistKeys.of(t.artist), 1.5 * Decay.Short.factor(now - at), Double::plus) }
        }

        val intents = input.searches.asSequence()
            .filter { now - it.at in 0..INTENT_WINDOW_MS }
            .sortedByDescending { it.at }
            .distinctBy { it.query.lowercase().trim() }
            .take(12)
            .map { s ->
                Intent(s.query.trim(), tokens(s.query), ArtistKey.of(s.query).takeIf { it.length >= 3 }.orEmpty(), exp(-(now - s.at) / (3.0 * DAY)))
            }.filter { it.tokens.isNotEmpty() }.toList()

        val skipRate = artistPlays.mapValues { (k, n) -> (artistEarly[k] ?: 0).toDouble() / (n + 2.0) }.filterValues { it > 0 }
        val moods = input.coldStart.moods.mapNotNull { m -> Mood.entries.firstOrNull { it.name.equals(m, true) || it.label.equals(m, true) } }
        val energyMean = if (eW > 0) eSum / eW else null
        val energySd = if (eW > 0 && energyMean != null) sqrt(max(0.0, eSq / eW - energyMean * energyMean)) else null
        for (s in input.coldStart.seedArtists) names.putIfAbsent(ArtistKey.of(s), s)

        return TasteModel(
            trackLong = trackLong, trackShort = trackShort, artistLong = artistLong, artistShort = artistShort,
            genre = genre, decade = decade, energyMean = energyMean, energySd = energySd,
            contextArtists = Array(ListeningContext.COUNT) { ctxArtists[it] },
            contextArtistPlays = Array(ListeningContext.COUNT) { ctxPlays[it] },
            contextTotals = ctxTotals,
            contextEnergy = Array(ListeningContext.COUNT) { if (ctxEnergyW[it] > 1.0) ctxEnergySum[it] / ctxEnergyW[it] else null },
            artistEarlySkipRate = skipRate, trackEarlySkips = earlySkips, positivePlays = positive,
            lastPlayed = last, firstPlayed = first, likes = input.likes, playlistsOf = playlistsOf, intents = intents,
            coldArtists = input.coldStart.artistKeys,
            coldEnergy = moods.takeIf { it.isNotEmpty() }?.map { it.energy.toDouble() }?.average(),
            artistNames = names, eventCount = events.size,
        )
    }
}
