package com.arnav.music.domain.recommend

import com.arnav.music.domain.catalog.isSingle
import com.arnav.music.domain.library.Duplicates
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The last step before anything is shown: hard filters, de-duplication across uploads, then a
 * greedy maximal-marginal-relevance pass with artist spacing, an optional energy arc and optional
 * transition smoothness.
 */
object Reranker {

    /** Only real singles the listener hasn't rejected. */
    fun eligible(track: Track, feedback: Feedback): Boolean = feedback.allows(track) && track.isSingle()

    /** Same song uploaded several times (or on the device too): one stable key per song. */
    fun songKey(t: Track): String = Duplicates.artistKey(t.artist) + "|" + Duplicates.titleKey(t.title, t.artist)

    /**
     * Keeps one version per song: the one the listener has played most, else the [prefer]red upload
     * type, else unknown type, else the other; the group keeps its best score.
     */
    fun dedupe(
        items: List<Recommendation>,
        prefer: MediaVariant?,
        plays: (TrackId) -> Int = { 0 },
        keyOf: (Track) -> String = ::songKey,
    ): List<Recommendation> {
        val groups = LinkedHashMap<String, MutableList<Recommendation>>()
        for (r in items) groups.getOrPut(keyOf(r.track)) { ArrayList() } += r
        return groups.values.map { g ->
            if (g.size == 1) return@map g[0]
            val best = g.maxByOrNull { it.score }!!
            val keep = g.minWithOrNull(
                compareByDescending<Recommendation> { plays(it.track.id) }
                    .thenBy { when (it.track.variant) { prefer -> 0; null -> 1; else -> 2 } }
                    .thenByDescending { it.score }
                    .thenBy { it.track.id.value },
            )!!
            if (keep === best) keep else keep.copy(score = best.score, source = best.source, explanation = best.explanation)
        }
    }

    /**
     * Greedy MMR: each slot takes the candidate maximising
     * λ·relevance − (1−λ)·max-similarity-to-already-picked (+ energy-arc fit, + smooth transition),
     * subject to: no artist appears twice within any [artistGap] consecutive songs (counting
     * [history], the songs already playing/queued before these) and at most [maxPerArtist] per artist.
     * Constraints are never relaxed: when nothing satisfies them the list simply ends early.
     */
    fun rerank(
        items: List<Recommendation>,
        limit: Int,
        similarity: (Track, Track) -> Double,
        lambda: Double = 0.75,
        artistGap: Int = 3,
        maxPerArtist: Int = 3,
        history: List<Track> = emptyList(),
        energyTarget: ((Int) -> Double?)? = null,
        energyOf: (Track) -> Double? = { it.energy?.toDouble() },
        transition: ((Track, Track) -> Double)? = null,
        shortlist: Int = 250,
        artistOf: (Track) -> String = { it.artistKey },
    ): List<Recommendation> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        val pool = items.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.track.id.value })
            .take(shortlist).toMutableList()
        val artists = pool.map { artistOf(it.track) }.toMutableList()
        val hi = pool.first().score
        val lo = pool.last().score
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val picked = ArrayList<Recommendation>()
        val perArtist = HashMap<String, Int>()
        val recent = ArrayDeque<String>()
        for (t in history.takeLast(maxOf(0, artistGap - 1))) recent.addLast(artistOf(t))
        val maxSim = DoubleArray(pool.size)
        var prev: Track? = history.lastOrNull()
        while (picked.size < limit && pool.isNotEmpty()) {
            val target = energyTarget?.invoke(picked.size)
            var bestI = -1
            var bestV = Double.NEGATIVE_INFINITY
            for (i in pool.indices) {
                val r = pool[i]
                val a = artists[i]
                if (artistGap > 1 && a in recent) continue
                if ((perArtist[a] ?: 0) >= maxPerArtist) continue
                var v = lambda * (r.score - lo) / span - (1 - lambda) * maxSim[i]
                if (target != null) energyOf(r.track)?.let { e -> v -= 0.35 * abs(e - target) }
                val p = prev
                if (transition != null && p != null) v += transition(p, r.track)
                if (v > bestV + 1e-12) { bestV = v; bestI = i }
            }
            if (bestI < 0) break
            val chosen = pool.removeAt(bestI)
            val chosenArtist = artists.removeAt(bestI)
            // Keep maxSim aligned with the shrunken pool.
            for (i in bestI until pool.size) maxSim[i] = maxSim[i + 1]
            picked += chosen
            perArtist.merge(chosenArtist, 1, Int::plus)
            if (artistGap > 1) {
                recent.addLast(chosenArtist)
                while (recent.size > artistGap - 1) recent.removeFirst()
            }
            prev = chosen.track
            for (i in pool.indices) {
                val s = similarity(chosen.track, pool[i].track)
                if (s > maxSim[i]) maxSim[i] = s
            }
        }
        return picked
    }
}

/** Energy targets per position for a queue of songs. */
object EnergyArc {
    /** Gentle drift around [start] for endless radio: never a jarring jump, a slow wave over ~10 songs. */
    fun radio(start: Double, count: Int): DoubleArray =
        DoubleArray(count) { i -> (start + 0.08 * sin(2 * PI * (i + 1) / 10.0)).coerceIn(0.05, 0.95) }

    /** Session arc: settle in, build to a peak around 70 %, ease off at the end. */
    fun session(start: Double, count: Int): DoubleArray = DoubleArray(count) { i ->
        val p = if (count <= 1) 0.0 else i.toDouble() / (count - 1)
        val shape = if (p <= 0.7) p / 0.7 else 1 - (p - 0.7) / 0.3 * 0.6
        (start - 0.08 + 0.2 * shape).coerceIn(0.05, 0.95)
    }
}
