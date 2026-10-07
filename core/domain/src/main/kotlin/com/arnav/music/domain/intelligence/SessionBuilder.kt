package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

data class BuiltSession(
    val constraints: SessionConstraints,
    val tracks: List<Track>,
    val reasons: Map<TrackId, Reason>,
    val totalMs: Long,
    val discoveredCount: Int,
)

/**
 * Turns constraints + real, resolved candidates into an ordered queue.
 * Pure and deterministic: same inputs → same session.
 */
class SessionBuilder(private val recommender: Recommender = Recommender()) {

    fun build(
        constraints: SessionConstraints,
        candidates: List<Track>,
        profile: TasteProfile,
        liked: Set<TrackId>,
        now: Long,
        defaultTrackMs: Long = 210_000L,
    ): BuiltSession {
        val c = constraints.sanitized()
        val targetMs = c.durationMinutes * 60_000L
        val ranked = recommender.rank(candidates, profile, now, liked, c.energyTarget, c.discoveryRatio)
        if (ranked.isEmpty()) return BuiltSession(c, emptyList(), emptyMap(), 0, 0)

        val familiarPool = ArrayDeque(ranked.filter { (profile.trackFamiliarity[it.track.id] ?: 0f) >= 0.15f })
        val newPool = ArrayDeque(ranked.filter { (profile.trackFamiliarity[it.track.id] ?: 0f) < 0.15f })

        val maxPerArtist = when {
            c.artistDiversity >= 0.8f -> 2
            c.artistDiversity >= 0.5f -> 3
            else -> 5
        }
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Scored>()
        var total = 0L
        var discovered = 0
        var guard = 0

        while (total < targetMs && (familiarPool.isNotEmpty() || newPool.isNotEmpty()) && guard++ < 500) {
            val wantNew = out.isEmpty() && c.familiarity < 0.4f ||
                (out.size > 0 && discovered.toFloat() / (out.size + 1) < c.discoveryRatio)
            val pool = when {
                wantNew && newPool.isNotEmpty() -> newPool
                familiarPool.isNotEmpty() -> familiarPool
                else -> newPool
            }
            val position = total.toFloat() / targetMs
            val desiredEnergy = energyAt(c, position)
            val pick = pickBest(pool, desiredEnergy, out.lastOrNull()?.track?.artistKey, perArtist, maxPerArtist) ?: run {
                // Pool exhausted by artist caps; relax by dropping one candidate.
                pool.removeFirst(); null
            } ?: continue
            pool.remove(pick)
            out += pick
            perArtist.merge(pick.track.artistKey, 1, Int::plus)
            if ((profile.trackFamiliarity[pick.track.id] ?: 0f) < 0.15f) discovered++
            total += pick.track.durationMs?.takeIf { it in 30_000L..1_200_000L } ?: defaultTrackMs
        }
        return BuiltSession(c, out.map { it.track }, out.associate { it.track.id to it.reason }, total, discovered)
    }

    private fun pickBest(
        pool: ArrayDeque<Scored>,
        desiredEnergy: Float,
        lastArtist: String?,
        perArtist: Map<String, Int>,
        maxPerArtist: Int,
    ): Scored? = pool.asSequence().take(40)
        .filter { it.track.artistKey != lastArtist && (perArtist[it.track.artistKey] ?: 0) < maxPerArtist }
        .maxByOrNull { s ->
            val energyFit = s.track.energy?.let { 1f - abs(it - desiredEnergy) } ?: 0.6f
            s.score + 0.8f * energyFit
        }

    companion object {
        fun energyAt(c: SessionConstraints, position: Float): Float {
            val p = position.coerceIn(0f, 1f)
            val base = c.energyTarget
            val e = when (c.energyCurve) {
                EnergyCurve.FLAT -> base
                EnergyCurve.RISING -> base - 0.25f + 0.5f * p
                EnergyCurve.FALLING -> base + 0.25f - 0.5f * p
                EnergyCurve.PEAK -> base - 0.2f + 0.4f * sin(PI * p).toFloat()
                EnergyCurve.WAVE -> base + 0.15f * sin(2 * PI * 2 * p).toFloat()
            }
            return e.coerceIn(0f, 1f)
        }
    }
}
