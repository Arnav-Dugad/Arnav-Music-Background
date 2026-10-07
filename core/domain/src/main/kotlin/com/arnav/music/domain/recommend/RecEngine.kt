package com.arnav.music.domain.recommend

import com.arnav.music.domain.audio.HarmonicMix
import com.arnav.music.domain.audio.MixEntry
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Candidate generation, scoring and re-ranking for every surface, on top of a [RecModel].
 *
 * Pipeline: hard filter the [pool] (singles only, explicit feedback) → raw scores from up to ten
 * sources ([RecSource]) → each normalised to 0..1 and blended with per-surface base weights times
 * the bandit's per-source [multipliers] → plus taste fit, familiarity/novelty dial, recency and
 * skip penalties → explanation = the strongest source that has honest evidence → de-duplicate
 * uploads → MMR with artist spacing (and energy arc / smooth transitions for queues).
 *
 * Deterministic: the same model, pool and multipliers always give the same lists.
 */
class RecEngine(
    val model: RecModel,
    pool: Collection<Track>,
    private val multipliers: Map<RecSource, Double> = emptyMap(),
    private val prefer: MediaVariant? = MediaVariant.SONG,
    /** Share one across refreshes: the single/compilation check is regex-heavy. */
    private val singles: SinglesCache = SinglesCache(),
    /** Share one across refreshes: the graph is rebuilt only when its inputs changed. */
    private val graphCache: GraphCache = GraphCache(),
) {
    private val taste = model.taste
    private val feedback = model.input.feedback

    private fun artistOf(t: Track): String = model.artistKey(t)

    /** Eligible candidates by id: the pool plus every known track, singles only, nothing rejected. */
    val candidates: Map<TrackId, Track> = LinkedHashMap<TrackId, Track>().also { m ->
        fun offer(t: Track) {
            if (m.containsKey(t.id)) return
            if (feedback.allows(t.id, artistOf(t)) && singles.isSingle(t)) m[t.id] = t
        }
        for (t in pool) offer(t)
        for (t in model.input.tracks.values) offer(t)
    }

    private val byArtist: Map<String, List<Track>> by lazy { candidates.values.groupBy { artistOf(it) } }
    private val unplayed: List<Track> by lazy { candidates.values.filter { !taste.played(it.id) && it.id !in taste.likes } }
    private val songKeys = HashMap<TrackId, String>()
    private val graph: WalkGraph by lazy {
        val key = listOf(model.index.committedEvents, model.index.tracks.sessionCount, candidates.keys.hashCode(), model.input.playlists.hashCode(), model.input.tracks.size)
        graphCache.get(key) { buildGraph() }
    }
    private val inUserPlaylist: Set<TrackId> by lazy { taste.playlistsOf.filterValues { l -> l.isNotEmpty() }.keys }

    // ------------------------------------------------------------------ surfaces

    /** Context-aware: this session, this time of day, this week's taste. */
    fun forYouNow(limit: Int = 20): List<Recommendation> {
        val session = model.sessionSeeds()
        val tasteSeeds = taste.topTracksShort(12).map { it to 0.4 + 0.6 * taste.trackShortNorm(it) } + moreLikeThisSeeds()
        val ctxArtists = taste.topContextArtists(model.context, 6)
            .map { it to taste.contextAffinity(it, model.context) }.filter { it.second >= 0.3 }.toMap()
        val anchors = (session.map { it.first } + taste.topTracksShort(6) + model.input.feedback.moreLikeThis.keys).distinct()
            .mapNotNull { model.track(it) }.take(12)
        val sessionEnergy = session.mapNotNull { (id, _) -> model.track(id)?.let { model.features(it).energy?.toDouble() } }.takeIf { it.isNotEmpty() }?.average()
        val plan = Plan(
            base = weights(SESSION to 0.9, COOC to 0.7, GRAPH to 0.6, ARTIST to 0.45, CONTENT to 0.45, CONTEXT to 0.5, REDISCOVER to 0.15, DISCOVERY to 0.25, INTENT to 0.6, SEED to 0.5),
            familiarity = 0.6, tasteWeight = 1.0,
            energyTarget = sessionEnergy ?: taste.energyTarget(model.context),
            recencyHours = 6.0,
            exclude = session.map { it.first }.toSet(),
            useContext = true, useIntent = true, useCold = true, rediscover = true,
        )
        val scored = score(Seeds(session = session, taste = tasteSeeds, content = anchors, artists = ctxArtists), plan)
        return finish(scored, limit, artistGap = 3, maxPerArtist = 2)
    }

    /** Radio from a song: its neighbours, its sound, its artist's orbit — personalised, never the seed itself. */
    fun radio(seed: Track, limit: Int = 25): List<Recommendation> {
        val second = model.index.tracks.neighbours(seed.id, 5).map { it.key to 0.5 * it.score.coerceAtMost(1.0) }
        val plan = Plan(
            base = weights(COOC to 0.9, GRAPH to 0.7, CONTENT to 0.8, ARTIST to 0.5, DISCOVERY to 0.3, CONTEXT to 0.1, INTENT to 0.1, SEED to 0.1),
            familiarity = 0.5, tasteWeight = 0.5,
            energyTarget = model.features(seed).energy?.toDouble(),
            recencyHours = 2.0, exclude = setOf(seed.id),
            seedGenres = model.features(seed).genres,
        )
        val seeds = Seeds(taste = listOf(seed.id to 1.0) + second, content = listOf(seed), artists = mapOf(artistOf(seed) to 1.0))
        return finish(score(seeds, plan), limit, artistGap = 3, maxPerArtist = 3, history = listOf(seed))
    }

    /** Radio from an artist: their best-loved songs anchor it, related artists fill it out. */
    fun artistRadio(artistKey: String, limit: Int = 25): List<Recommendation> {
        val own = byArtist[artistKey].orEmpty()
            .sortedWith(compareByDescending<Track> { taste.trackLongNorm(it.id) }.thenBy { it.id.value })
        val anchors = own.take(5)
        val plan = Plan(
            base = weights(COOC to 0.7, GRAPH to 0.7, CONTENT to 0.6, ARTIST to 0.9, DISCOVERY to 0.3),
            familiarity = 0.5, tasteWeight = 0.5, recencyHours = 2.0,
            seedGenres = anchors.flatMap { model.features(it).genres }.toSet(),
        )
        val seeds = Seeds(taste = anchors.map { it.id to 1.0 }, content = anchors, artists = mapOf(artistKey to 1.0))
        return finish(score(seeds, plan), limit, artistGap = 3, maxPerArtist = 4)
    }

    /**
     * Endless radio: what should follow [recent] (the queue so far, oldest first). Seeds from the
     * last few songs, a gentle energy arc from where the queue is, smooth key/tempo moves between
     * analysed songs, artist spacing continuing from the queue.
     */
    fun continuation(recent: List<Track>, limit: Int = 10, exclude: Set<TrackId> = emptySet()): List<Recommendation> {
        val tail = recent.takeLast(5).asReversed()
        val seeds = tail.mapIndexed { i, t -> t.id to Math.pow(0.8, i.toDouble()) }
        val lastEnergy = tail.firstNotNullOfOrNull { model.features(it).energy?.toDouble() } ?: taste.energyTarget(model.context) ?: 0.5
        val plan = Plan(
            base = weights(SESSION to 1.0, COOC to 0.6, GRAPH to 0.6, ARTIST to 0.45, CONTENT to 0.6, CONTEXT to 0.25, DISCOVERY to 0.3, INTENT to 0.2, SEED to 0.3),
            familiarity = 0.55, tasteWeight = 0.8, energyTarget = lastEnergy, recencyHours = 3.0,
            exclude = exclude + recent.map { it.id }, useContext = true, useCold = true,
        )
        val scored = score(Seeds(session = seeds, content = tail.take(3), artists = tail.associate { artistOf(it) to 0.4 }), plan)
        val arc = EnergyArc.radio(lastEnergy, limit)
        return finish(
            scored, limit, artistGap = 3, maxPerArtist = 2, history = recent,
            energyTarget = { i -> arc.getOrNull(i) }, transition = ::transitionBonus,
        )
    }

    /** Songs never played, liked or saved, that fit: cached search pages, unplayed files, fresh finds. */
    fun freshFinds(limit: Int = 20): List<Recommendation> {
        val anchors = (taste.topTracksShort(8) + taste.topTracksLong(8)).distinct().mapNotNull { model.track(it) }.take(10)
        val plan = Plan(
            base = weights(COOC to 0.5, GRAPH to 0.8, CONTENT to 0.6, ARTIST to 0.5, DISCOVERY to 1.0, INTENT to 0.7, SEED to 0.5, CONTEXT to 0.1),
            familiarity = 0.0, tasteWeight = 0.8, onlyNew = true, useIntent = true, useCold = true, useContext = true,
            energyTarget = taste.energyTarget(model.context),
        )
        val seeds = Seeds(
            taste = taste.topTracksShort(10).map { it to 0.5 + 0.5 * taste.trackShortNorm(it) },
            content = anchors,
            artists = taste.topArtists(8).associateWith { taste.artistAffinity(it) },
        )
        return finish(score(seeds, plan), limit, artistGap = 3, maxPerArtist = 2)
    }

    /** Loved long ago (plays or likes), not played for a month or more. */
    fun rediscover(limit: Int = 20, minDays: Int = 30): List<Recommendation> {
        val now = model.now
        val out = ArrayList<Recommendation>()
        val ids = (taste.trackLong.keys + taste.likes.keys).distinct()
        for (id in ids) {
            val t = candidates[id] ?: continue
            val last = taste.lastPlayed[id]
            val since = now - (last ?: taste.likes[id] ?: now)
            val days = since / DAY_MS
            if (days < minDays) continue
            val loved = (taste.positivePlays[id] ?: 0) >= 2 || id in taste.likes
            if (!loved) continue
            val long = taste.trackLongNorm(id)
            val score = (0.3 + long) * (1 - taste.trackShortNorm(id)) * minOf(1.0, days / 90.0) +
                (if (id in taste.likes) 0.25 else 0.0) - 0.4 * taste.artistEarlySkipRate.getOrDefault(artistOf(t), 0.0)
            val peak = model.peakMonth(id)
            val why = Explain.rediscover(peak?.second ?: 0, peak?.first, taste.likes[id], now, model.input.zone)
            out += Recommendation(t, score, RecSource.REDISCOVER, why)
        }
        return finish(out, limit, artistGap = 3, maxPerArtist = 2, lambda = 0.85)
    }

    /**
     * Up to [maxMixes] "Daily Mixes": the listener's top tracks clustered by their co-occurrence
     * embedding plus artist and genre, each mix = its familiar core with a few close new songs mixed
     * in, named after its top artists.
     */
    fun dailyMixes(maxMixes: Int = 6, perMix: Int = 25, seed: Int = 17): List<DailyMix> {
        val items = taste.topTracksLong(400).filter { it in candidates }
        if (items.size < 12) return emptyList()
        val vectors = mixVectors(items, seed)
        val k = when {
            items.size >= 90 -> minOf(maxMixes, items.size / 15)
            items.size >= 30 -> minOf(maxMixes, 3)
            else -> 2
        }.coerceAtLeast(1)
        val weights = DoubleArray(items.size) { 0.2 + taste.trackLongNorm(items[it]) }
        val assign = KMeans.cluster(vectors, k, seed = seed, weights = weights)
        val clusters = (0 until k).map { c -> items.indices.filter { assign[it] == c }.map { items[it] } }
            .filter { it.size >= 6 }
            .sortedWith(compareByDescending<List<TrackId>> { c -> c.sumOf { taste.trackLongNorm(it) } }.thenBy { it.first().value })
        val used = HashSet<TrackId>()
        val mixes = ArrayList<DailyMix>()
        for (cluster in clusters) {
            val core = cluster.sortedWith(compareByDescending<TrackId> { taste.trackLongNorm(it) }.thenBy { it.value })
            val artistWeight = HashMap<String, Double>()
            for (id in core) candidates[id]?.let { artistWeight.merge(artistOf(it), 0.2 + taste.trackLongNorm(id), Double::plus) }
            val topArtists = artistWeight.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).map { it.key }
            val names = topArtists.take(3).map { model.artistName(it) }
            val coreTracks = core.filter { it !in used }.mapNotNull { candidates[it] }
            val plan = Plan(
                base = weights(COOC to 0.8, GRAPH to 0.8, CONTENT to 0.5, ARTIST to 0.5, DISCOVERY to 0.4),
                familiarity = 0.2, tasteWeight = 0.6, recencyHours = 1.0, exclude = core.toSet() + used,
                seedGenres = coreTracks.take(10).flatMap { model.features(it).genres }.toSet(),
            )
            val fresh = score(
                Seeds(taste = core.take(15).map { it to 0.3 + taste.trackLongNorm(it) }, content = coreTracks.take(6), artists = topArtists.take(4).associateWith { 0.6 }),
                plan,
            ).filter { r -> taste.familiarity(r.track.id) < 0.5 && coreTracks.take(10).maxOfOrNull { model.similarity(it, r.track) }.let { it != null && it >= MIX_FIT } }
                .sortedByDescending { it.score }.take(perMix / 3)
            val coreRecs = coreTracks.map { t -> Recommendation(t, 0.5 + taste.trackLongNorm(t.id), RecSource.COOCCURRENCE, fallbackExplanation(t)) }
            val combined = interleave(coreRecs.take(perMix - fresh.size), fresh)
            val ranked = finish(combined, perMix, artistGap = 2, maxPerArtist = maxOf(4, perMix / 3), lambda = 0.9, keepOrder = true)
            if (ranked.size < 6) continue
            ranked.forEach { used += it.track.id }
            val title = "Daily Mix · " + names.take(2).joinToString(", ")
            val topGenre = coreTracks.flatMap { model.features(it).genres }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()
                ?.takeIf { it.value * 2 >= coreTracks.size }?.key
            val subtitle = buildString {
                append(names.joinToString(", "))
                if (topArtists.size > 3) append(" and more")
                if (topGenre != null) append(" · ").append(topGenre.replaceFirstChar { it.uppercase() })
            }
            mixes += DailyMix("mix${mixes.size}_" + topArtists.take(2).joinToString("_"), title, subtitle, ranked, names)
            if (mixes.size >= maxMixes) break
        }
        return mixes
    }

    // ------------------------------------------------------------------ scoring

    private class Seeds(
        val session: List<Pair<TrackId, Double>> = emptyList(),
        val taste: List<Pair<TrackId, Double>> = emptyList(),
        val content: List<Track> = emptyList(),
        val artists: Map<String, Double> = emptyMap(),
    )

    private class Plan(
        val base: Map<RecSource, Double>,
        val familiarity: Double,
        val tasteWeight: Double,
        val energyTarget: Double? = null,
        /** Penalise songs played within roughly this many hours (0 = no penalty). */
        val recencyHours: Double = 0.0,
        val exclude: Set<TrackId> = emptySet(),
        /** Only songs never played, liked or kept in a playlist. */
        val onlyNew: Boolean = false,
        val useContext: Boolean = false,
        val useIntent: Boolean = false,
        val useCold: Boolean = false,
        val rediscover: Boolean = false,
        val seedGenres: Set<String> = emptySet(),
    )

    private class Cand(val track: Track) {
        val raw = DoubleArray(SOURCES.size)
        val best = DoubleArray(SOURCES.size)
        val evidence = arrayOfNulls<Explanation>(SOURCES.size)

        fun add(s: RecSource, v: Double, why: Explanation?) {
            if (v <= 0) return
            raw[s.ordinal] += v
            if (why != null && v > best[s.ordinal]) { best[s.ordinal] = v; evidence[s.ordinal] = why }
        }

        fun max(s: RecSource, v: Double, why: Explanation?) {
            if (v <= raw[s.ordinal]) return
            raw[s.ordinal] = v
            if (why != null) evidence[s.ordinal] = why
        }
    }

    private fun score(seeds: Seeds, plan: Plan): List<Recommendation> {
        val cands = HashMap<TrackId, Cand>()
        fun allowed(t: Track): Boolean {
            if (t.id in plan.exclude) return false
            if (plan.onlyNew && (taste.played(t.id) || t.id in taste.likes || t.id in inUserPlaylist)) return false
            return true
        }
        fun cand(id: TrackId): Cand? {
            cands[id]?.let { return it }
            val t = candidates[id] ?: return null
            if (!allowed(t)) return null
            return Cand(t).also { cands[id] = it }
        }

        // Session and co-occurrence neighbours.
        fun neighbours(source: RecSource, from: List<Pair<TrackId, Double>>) {
            for ((s, w) in from) {
                val title = model.track(s)?.title
                for (n in model.index.tracks.neighbours(s, 40)) {
                    val c = cand(n.key) ?: continue
                    val why = title?.let { if (n.markov >= 0.25 && n.markov >= n.cosine) Explain.follows(it, s) else Explain.coPlayed(it, s) }
                    c.add(source, w * n.score, why)
                }
            }
        }
        if (plan.base.containsKey(RecSource.SESSION)) neighbours(RecSource.SESSION, seeds.session)
        if (plan.base.containsKey(RecSource.COOCCURRENCE)) neighbours(RecSource.COOCCURRENCE, seeds.taste)

        // Personalised PageRank on the heterogeneous graph (the strongest few hundred tracks).
        if (plan.base.containsKey(RecSource.GRAPH)) {
            val restart = HashMap<String, Double>()
            for ((id, w) in seeds.session) restart.merge(WalkGraph.track(id.value), 0.35 * w, Double::plus)
            for ((id, w) in seeds.taste) restart.merge(WalkGraph.track(id.value), 0.35 * w, Double::plus)
            for ((a, w) in seeds.artists) restart.merge(WalkGraph.artist(a), 0.2 * w, Double::plus)
            for (g in plan.seedGenres) restart.merge(WalkGraph.genre(g), 0.1, Double::plus)
            if (restart.isNotEmpty()) {
                val top = graph.pushRank(restart, "t:").entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                var taken = 0
                for ((key, v) in top) {
                    if (taken >= GRAPH_TOP) break
                    val c = cand(TrackId(key)) ?: continue
                    c.add(RecSource.GRAPH, v, null)
                    taken++
                }
            }
        }

        // Artist affinity, explicit artist seeds and related artists (artist-level co-occurrence).
        if (plan.base.containsKey(RecSource.ARTIST)) {
            val related = HashMap<String, Pair<Double, String>>()
            val tasteArtists = taste.topArtists(20)
            for (a in (seeds.artists.keys + tasteArtists.take(10)).distinct()) {
                val wa = maxOf(seeds.artists[a] ?: 0.0, taste.artistAffinity(a))
                for (n in model.index.artists.neighbours(a, 10)) {
                    val v = wa * n.score
                    if (v > (related[n.key]?.first ?: 0.0)) related[n.key] = v to a
                }
            }
            val top5 = tasteArtists.take(5).toSet()
            for (a in (seeds.artists.keys + tasteArtists + related.keys).distinct()) {
                val seedW = seeds.artists[a] ?: 0.0
                val aff = taste.artistAffinity(a) * plan.tasteWeight
                val rel = related[a]
                if (seedW <= 0 && aff <= 0.05 && rel == null) continue
                val direct = maxOf(seedW, aff)
                for (t in byArtist[a].orEmpty()) {
                    val c = cand(t.id) ?: continue
                    if (direct > 0) c.max(RecSource.ARTIST, direct, Explain.sameArtist(model.artistName(a), a in top5))
                    if (rel != null) c.max(RecSource.ARTIST, 0.8 * rel.first, Explain.relatedArtist(model.artistName(rel.second)))
                }
            }
        }

        // Context: artists this listener plays at this time of day / week.
        val ctx = model.context
        if (plan.useContext && taste.contextTotals[ctx.index] > 0) {
            for (a in taste.topContextArtists(ctx, 15)) {
                val v = taste.contextAffinity(a, ctx)
                if (v <= 0.05) continue
                val why = if (taste.contextClaimHolds(a, ctx)) Explain.context(model.artistName(a), ctx) else null
                for (t in byArtist[a].orEmpty()) cand(t.id)?.max(RecSource.CONTEXT, v, why)
            }
        }
        // Recent searches.
        if (plan.useIntent && taste.intents.isNotEmpty()) {
            for (t in candidates.values) taste.intentMatch(t, artistOf(t))?.let { (intent, v) -> cand(t.id)?.max(RecSource.INTENT, v, Explain.search(intent.display)) }
        }
        // Onboarding picks while history is thin.
        if (plan.useCold && taste.coldArtists.isNotEmpty() && taste.coldWeight > 0.05) {
            for (a in taste.coldArtists) for (t in byArtist[a].orEmpty()) cand(t.id)?.max(RecSource.SEED, taste.coldWeight, Explain.seed(model.artistName(a)))
        }
        // Discovery: never-played songs that fit. Only the best-fitting few hundred join as new candidates.
        if (plan.base.containsKey(RecSource.DISCOVERY)) {
            val fits = ArrayList<Pair<Track, Double>>()
            for (t in unplayed) {
                if (!allowed(t)) continue
                val f = model.features(t)
                var fit = 0.5 * taste.artistAffinity(f.artistKey) + 0.3 * taste.genreAffinity(f.genres) + 0.2 * taste.decadeAffinity(f.year)
                if (plan.seedGenres.isNotEmpty() && f.genres.any { it in plan.seedGenres }) fit += 0.3
                val existing = cands[t.id]
                if (existing != null) existing.max(RecSource.DISCOVERY, 0.15 + fit, null) else if (fit > 0.1) fits += t to fit
            }
            fits.sortWith(compareByDescending<Pair<Track, Double>> { it.second }.thenBy { it.first.id.value })
            for ((t, fit) in fits.take(DISCOVERY_TOP)) cand(t.id)?.max(RecSource.DISCOVERY, 0.15 + fit, null)
        }
        // Rediscovery: long-term favourites gone quiet.
        if (plan.rediscover) {
            val old = ArrayList<Pair<TrackId, Double>>()
            for ((id, last) in taste.lastPlayed) {
                if (model.now - last <= 30 * DAY_MS.toLong()) continue
                val v = taste.trackLongNorm(id) * (1 - taste.trackShortNorm(id)) * minOf(1.0, (model.now - last) / DAY_MS / 90.0)
                if (v > 0.05) old += id to v
            }
            old.sortWith(compareByDescending<Pair<TrackId, Double>> { it.second }.thenBy { it.first.value })
            for ((id, v) in old.take(REDISCOVER_TOP)) cand(id)?.max(RecSource.REDISCOVER, v, null)
        }

        // Content similarity to the anchors — for a shortlist (cheap sources first), analysed files and same-genre songs.
        if (plan.base.containsKey(RecSource.CONTENT) && seeds.content.isNotEmpty()) {
            val prelim = cands.values.map { c -> c to c.raw.sum() }.sortedByDescending { it.second }.take(CONTENT_SHORTLIST).map { it.first.track }
            val analysed = candidates.values.asSequence().filter { model.input.traits.containsKey(it.id) && allowed(it) }.take(CONTENT_SHORTLIST).toList()
            val sameGenre = if (plan.seedGenres.isEmpty()) emptyList() else
                candidates.values.asSequence().filter { allowed(it) && model.features(it).genres.any { g -> g in plan.seedGenres } }.take(CONTENT_SHORTLIST).toList()
            val anchorFeatures = seeds.content.map { it to model.features(it) }
            val seen = HashSet<TrackId>()
            for (t in prelim + analysed + sameGenre) {
                if (!seen.add(t.id)) continue
                val f = model.features(t)
                var best = 0.0
                var why: Explanation? = null
                for ((anchor, af) in anchorFeatures) {
                    if (anchor.id == t.id) continue
                    val s = ContentSimilarity.similarity(af, f)
                    if (s.score > best) {
                        best = s.score
                        why = Explain.soundAlike(s.aspect, anchor.title, anchor.artist, af.key >= 0 && af.key == f.key, anchor.id)
                    }
                }
                if (best > 0.2) cand(t.id)?.max(RecSource.CONTENT, best, why)
            }
        }

        if (cands.isEmpty()) return emptyList()
        // Normalise accumulating sources to 0..1.
        val maxRaw = DoubleArray(SOURCES.size)
        for (c in cands.values) for (i in SOURCES.indices) if (c.raw[i] > maxRaw[i]) maxRaw[i] = c.raw[i]
        val accumulate = intArrayOf(RecSource.SESSION.ordinal, RecSource.COOCCURRENCE.ordinal, RecSource.GRAPH.ordinal)
        val baseArr = DoubleArray(SOURCES.size) { plan.base[SOURCES[it]] ?: Double.NaN }
        val multArr = DoubleArray(SOURCES.size) { multipliers[SOURCES[it]] ?: 1.0 }

        val out = ArrayList<Recommendation>(cands.size)
        for (c in cands.values) {
            val t = c.track
            var src = 0.0
            var domIdx = -1
            var domV = 0.0
            var domEvIdx = -1
            var domEvV = 0.0
            for (i in SOURCES.indices) {
                var v = c.raw[i]
                if (v <= 0) continue
                val baseW = baseArr[i]
                if (baseW.isNaN()) continue
                if (i in accumulate && maxRaw[i] > 0) v /= maxRaw[i]
                val contribution = baseW * multArr[i] * v.coerceAtMost(1.0)
                src += contribution
                if (contribution > domV) { domV = contribution; domIdx = i }
                if (c.evidence[i] != null && contribution > domEvV) { domEvV = contribution; domEvIdx = i }
            }
            val id = t.id
            val f = model.features(t)
            val energyFit = if (plan.energyTarget != null && f.energy != null) 1 - minOf(1.0, abs(f.energy - plan.energyTarget) * 2) else 0.5
            val tasteScore = plan.tasteWeight * (0.30 * taste.artistAffinity(f.artistKey) + 0.15 * taste.genreAffinity(f.genres) +
                0.05 * taste.decadeAffinity(f.year) + 0.10 * energyFit)
            val fam = taste.familiarity(id)
            val dial = 0.25 * (plan.familiarity * fam + (1 - plan.familiarity) * (1 - fam))
            val recency = if (plan.recencyHours > 0) taste.lastPlayed[id]?.let { last ->
                1.2 * exp(-max(0L, model.now - last) / 3_600_000.0 / plan.recencyHours)
            } ?: 0.0 else 0.0
            val skips = (taste.trackEarlySkips[id] ?: 0) - (taste.positivePlays[id] ?: 0) / 2.0
            val penalty = recency + 0.5 * (taste.artistEarlySkipRate[f.artistKey] ?: 0.0) + 0.3 * minOf(3.0, max(0.0, skips))
            val score = src + tasteScore + dial - penalty

            // Explanation: the strongest source with real evidence, unless it's much weaker than the top source.
            val explanation = when {
                domEvIdx >= 0 && domEvV >= 0.5 * domV -> c.evidence[domEvIdx]!!
                else -> fallbackExplanation(t, if (domIdx >= 0) SOURCES[domIdx] else null)
            }
            val credited = when {
                domEvIdx >= 0 && domEvV >= 0.5 * domV -> SOURCES[domEvIdx]
                domIdx >= 0 -> SOURCES[domIdx]
                else -> RecSource.GRAPH
            }
            out += Recommendation(t, score, credited, explanation)
        }
        return out
    }

    /** Copy when no source left specific evidence: the most concrete true statement available. */
    private fun fallbackExplanation(t: Track, source: RecSource? = null): Explanation {
        val id = t.id
        val userPlaylist = taste.playlistsOf[id]?.firstOrNull { it.userMade }
        return when {
            source == RecSource.REDISCOVER -> model.peakMonth(id).let { p -> Explain.rediscover(p?.second ?: 0, p?.first, taste.likes[id], model.now, model.input.zone) }
            source == RecSource.DISCOVERY && !taste.played(id) && taste.artistAffinity(artistOf(t)) >= 0.25 -> Explain.newFromArtist(t.artist)
            source == RecSource.DISCOVERY && !taste.played(id) -> Explain.newNear(nearestKnownArtist(t))
            id in taste.likes -> Explain.liked()
            userPlaylist != null -> Explain.playlist(userPlaylist.name)
            taste.trackShortNorm(id) >= 0.6 -> Explain.heavyRotation()
            !taste.played(id) && taste.artistAffinity(artistOf(t)) >= 0.25 -> Explain.newFromArtist(t.artist)
            taste.artistAffinity(artistOf(t)) >= 0.5 -> Explain.sameArtist(t.artist, true)
            else -> {
                val g = model.features(t).genres.maxByOrNull { taste.genreAffinity(listOf(it)) }
                val genreFits = g != null && taste.genreAffinity(listOf(g)) >= 0.5
                when {
                    !taste.played(id) && genreFits -> Explain.newInGenre(g!!)
                    !taste.played(id) -> Explain.newNear(nearestKnownArtist(t))
                    genreFits -> Explain.genre(g!!)
                    else -> Explain.forYou()
                }
            }
        }
    }

    /** A related artist the listener actually plays (from artist co-occurrence or shared genres), for "close to …". */
    private fun nearestKnownArtist(t: Track): String? {
        val key = artistOf(t)
        model.index.artists.neighbours(key, 3).firstOrNull { taste.artistAffinity(it.key) > 0.2 }?.let { return model.artistName(it.key) }
        val g = model.features(t).genres
        if (g.isEmpty()) return null
        return taste.topArtists(15).firstOrNull { a -> a != key && byArtist[a].orEmpty().any { x -> model.features(x).genres.any { it in g } } }
            ?.let { model.artistName(it) }
    }

    private fun finish(
        items: List<Recommendation>,
        limit: Int,
        artistGap: Int,
        maxPerArtist: Int,
        lambda: Double = 0.75,
        history: List<Track> = emptyList(),
        energyTarget: ((Int) -> Double?)? = null,
        transition: ((Track, Track) -> Double)? = null,
        keepOrder: Boolean = false,
    ): List<Recommendation> {
        val plays = { id: TrackId -> taste.positivePlays[id] ?: 0 }
        // Only the head can make the list: de-duplicate that, not the whole pool (song keys are regex work).
        val head = if (keepOrder) items else items.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.track.id.value }).take(DEDUPE_HEAD)
        val deduped = Reranker.dedupe(head, prefer, plays) { t -> songKeys.getOrPut(t.id) { Reranker.songKey(t) } }
        // keepOrder: relevance = position in the given list (MMR and artist spacing still apply).
        val ranked = if (keepOrder) deduped.mapIndexed { i, r -> r.copy(score = (deduped.size - i).toDouble()) } else deduped
        return Reranker.rerank(
            ranked, limit, model::similarity, lambda = lambda, artistGap = artistGap, maxPerArtist = maxPerArtist,
            history = history, energyTarget = energyTarget, energyOf = { model.features(it).energy?.toDouble() }, transition = transition,
            artistOf = ::artistOf,
        )
    }

    /** Small bonus for a smooth DJ move between two analysed songs (Camelot key + tempo). */
    private fun transitionBonus(a: Track, b: Track): Double {
        val fa = model.features(a); val fb = model.features(b)
        if (fa.bpm <= 0f || fb.bpm <= 0f || fa.key < 0 || fb.key < 0) return 0.0
        val cost = HarmonicMix.transitionCost(
            MixEntry(a, fa.bpm, fa.key, fa.energy ?: Float.NaN), MixEntry(b, fb.bpm, fb.key, fb.energy ?: Float.NaN),
        )
        return 0.15 * (1 - minOf(1.0, cost / 8.0))
    }

    private fun moreLikeThisSeeds(): List<Pair<TrackId, Double>> = model.input.feedback.moreLikeThis.entries
        .sortedByDescending { it.value }.take(5).map { it.key to 1.2 * Decay.Short.factor(model.now - it.value).coerceAtLeast(0.3) }

    private fun <T> interleave(core: List<T>, fresh: List<T>): List<T> {
        val out = ArrayList<T>(core.size + fresh.size)
        var f = 0
        for ((i, c) in core.withIndex()) {
            out += c
            if (i % 3 == 2 && f < fresh.size) out += fresh[f++]
        }
        while (f < fresh.size) out += fresh[f++]
        return out
    }

    private fun mixVectors(items: List<TrackId>, seed: Int): List<DoubleArray> {
        val emb = SpectralEmbedding.embed(SpectralEmbedding.ppmiMatrix(model.index.tracks, items), dim = 16, seed = seed)
        return items.mapIndexed { i, id ->
            val t = candidates[id]
            val f = t?.let { model.features(it) }
            val e = VectorMath.normalized(emb[i])
            val v = DoubleArray(e.size + ARTIST_DIMS + GENRE_DIMS + 1)
            for (k in e.indices) v[k] = e[k]
            if (t != null) {
                v[e.size + Math.floorMod(f!!.artistKey.hashCode(), ARTIST_DIMS)] += 0.9
                val gs = f.genres
                for (g in gs) v[e.size + ARTIST_DIMS + Math.floorMod(g.hashCode(), GENRE_DIMS)] += 0.5 / gs.size
                v[v.size - 1] = 0.3 * (f.energy?.toDouble() ?: 0.5)
            }
            v
        }
    }

    private fun buildGraph(): WalkGraph {
        val b = WalkGraph.Builder()
        val nodeOf = HashMap<TrackId, Int>()
        val artistNode = HashMap<String, Int>()
        val genreNode = HashMap<String, Int>()
        val eraNode = HashMap<Int, Int>()
        fun add(t: Track) {
            if (nodeOf.containsKey(t.id)) return
            val node = b.node(WalkGraph.track(t.id.value))
            nodeOf[t.id] = node
            val f = model.features(t)
            b.edge(node, artistNode.getOrPut(f.artistKey) { b.node(WalkGraph.artist(f.artistKey)) }, 1.0)
            for (g in f.genres) b.edge(node, genreNode.getOrPut(g) { b.node(WalkGraph.genre(g)) }, 0.6 / f.genres.size)
            f.decade?.let { d -> b.edge(node, eraNode.getOrPut(d) { b.node(WalkGraph.era(d)) }, 0.15) }
        }
        for (t in candidates.values) add(t)
        for (t in model.input.tracks.values) add(t)
        for (p in model.input.playlists) {
            val w = if (p.userMade) 1.0 else 0.35
            val pn = b.node(WalkGraph.playlist(p.id))
            for ((id, _) in p.tracks) nodeOf[id]?.let { b.edge(it, pn, w) }
        }
        // Session co-occurrence: cosine-weighted track–track edges.
        model.index.tracks.forEachPair { x, y, cos ->
            if (cos >= 0.05) {
                val i = nodeOf[x]; val j = nodeOf[y]
                if (i != null && j != null) b.edge(i, j, 2.0 * cos)
            }
        }
        return b.build()
    }

    private fun weights(vararg p: Pair<RecSource, Double>): Map<RecSource, Double> = mapOf(*p)

    private companion object {
        val SOURCES = RecSource.entries.toTypedArray()
        const val DAY_MS = 86_400_000.0
        const val CONTENT_SHORTLIST = 800
        const val GRAPH_TOP = 600
        const val DISCOVERY_TOP = 300
        const val DEDUPE_HEAD = 600
        const val REDISCOVER_TOP = 200
        /** A new song joins a daily mix only when it sounds like the mix's core (content similarity). */
        const val MIX_FIT = 0.35
        const val ARTIST_DIMS = 24
        const val GENRE_DIMS = 12
        val SESSION = RecSource.SESSION
        val COOC = RecSource.COOCCURRENCE
        val GRAPH = RecSource.GRAPH
        val ARTIST = RecSource.ARTIST
        val CONTENT = RecSource.CONTENT
        val CONTEXT = RecSource.CONTEXT
        val REDISCOVER = RecSource.REDISCOVER
        val DISCOVERY = RecSource.DISCOVERY
        val INTENT = RecSource.INTENT
        val SEED = RecSource.SEED
    }
}

/** Keeps the last walk graph while the pool, playlists and committed sessions are unchanged. */
class GraphCache {
    private var key: Any? = null
    private var graph: WalkGraph? = null

    @Synchronized
    fun get(key: Any, build: () -> WalkGraph): WalkGraph {
        val g = graph
        if (g != null && key == this.key) return g
        return build().also { graph = it; this.key = key }
    }
}
