package com.arnav.music.domain.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * One track to place in a harmonic mix. [bpm] ≤ 0, [key] −1 or a non-finite [energy] mean
 * "not measured"; [analysed] = false sends the track to the end untouched.
 */
data class MixEntry<out T>(
    val value: T,
    val bpm: Float = 0f,
    val key: Int = KeyNames.UNKNOWN,
    val energy: Float = Float.NaN,
    val analysed: Boolean = true,
)

/**
 * DJ-style ordering: Camelot-compatible neighbours first, then small tempo changes (half/double
 * time counts as close), then a gentle energy arc (start moderate, build to a peak around 70 %,
 * ease off at the end). A small beam search over transition costs keeps it from painting itself
 * into a corner the way a plain greedy walk does.
 */
object HarmonicMix {
    private const val KEY_WEIGHT = 2.0
    private const val TEMPO_WEIGHT = 1.0
    private const val JUMP_WEIGHT = 1.5
    private const val ARC_WEIGHT = 1.0
    /** A 4 % tempo change costs as much as a compatible key move. */
    private const val TEMPO_STEP = 0.04
    private const val MAX_TEMPO_COST = 4.0
    private const val OCTAVE_PENALTY = 0.3
    private const val UNKNOWN_KEY_COST = 1.6
    private const val UNKNOWN_TEMPO_COST = 1.5
    /** Above this many tracks the search falls back to greedy (beam of 1). */
    private const val BEAM_LIMIT = 300
    private val LN2 = ln(2.0)

    /**
     * Returns the values of [tracks] in mix order: analysed tracks ordered to flow from [start]
     * (the track playing now, if any), then unanalysed ones in their original order.
     */
    fun <T> order(tracks: List<MixEntry<T>>, start: MixEntry<*>? = null, beamWidth: Int = 8): List<T> {
        val analysed = tracks.filter { it.analysed }
        val rest = tracks.filter { !it.analysed }.map { it.value }
        if (analysed.size <= 1) return analysed.map { it.value } + rest
        val m = analysed.size
        val width = if (m > BEAM_LIMIT) 1 else beamWidth.coerceAtLeast(1)
        val ideal = idealEnergies(analysed.mapNotNull { e -> e.energy.takeIf { it.isFinite() } }, m)

        fun stepCost(prev: MixEntry<*>?, next: MixEntry<*>, position: Int): Double {
            var c = if (prev == null) 0.0 else transitionCost(prev, next)
            if (ideal != null && next.energy.isFinite()) c += ARC_WEIGHT * abs(next.energy - ideal[position])
            return c
        }

        // Beam states: path (indices into analysed), used flags, cost.
        var paths = arrayOf(IntArray(0))
        var used = arrayOf(BooleanArray(m))
        var costs = doubleArrayOf(0.0)
        for (step in 0 until m) {
            val candPath = IntArray(width)
            val candFrom = IntArray(width)
            val candCost = DoubleArray(width) { Double.MAX_VALUE }
            var filled = 0
            for (b in paths.indices) {
                val path = paths[b]
                val prev = if (step == 0) start else analysed[path[step - 1]]
                for (j in 0 until m) {
                    if (used[b][j]) continue
                    val c = costs[b] + stepCost(prev, analysed[j], step)
                    // Insert into the sorted top-`width` list (stable: earlier candidates win ties).
                    if (filled == width && c >= candCost[width - 1]) continue
                    var pos = if (filled < width) filled else width - 1
                    while (pos > 0 && candCost[pos - 1] > c) {
                        candCost[pos] = candCost[pos - 1]; candPath[pos] = candPath[pos - 1]; candFrom[pos] = candFrom[pos - 1]
                        pos--
                    }
                    candCost[pos] = c; candPath[pos] = j; candFrom[pos] = b
                    if (filled < width) filled++
                }
            }
            paths = Array(filled) { i -> paths[candFrom[i]] + candPath[i] }
            used = Array(filled) { i -> used[candFrom[i]].copyOf().also { it[candPath[i]] = true } }
            costs = DoubleArray(filled) { candCost[it] }
        }
        return paths[0].map { analysed[it].value } + rest
    }

    /** Cost of playing [b] right after [a] (lower is smoother). */
    fun transitionCost(a: MixEntry<*>, b: MixEntry<*>): Double {
        var c = KEY_WEIGHT * keyCost(a.key, b.key) + TEMPO_WEIGHT * tempoCost(a.bpm, b.bpm)
        if (a.energy.isFinite() && b.energy.isFinite()) c += JUMP_WEIGHT * abs(a.energy - b.energy)
        return c
    }

    /** 0 for the same key, 0.6 for a Camelot-compatible move, more the further apart. */
    fun keyCost(a: Int, b: Int): Double {
        if (!KeyNames.isValid(a) || !KeyNames.isValid(b)) return UNKNOWN_KEY_COST
        if (a == b) return 0.0
        if (Camelot.compatible(a, b)) return 0.6
        val d = Camelot.distance(a, b)
        val sameMode = KeyNames.isMinor(a) == KeyNames.isMinor(b)
        // Two steps around the wheel, or one step plus a mode change, still sounds related.
        if ((sameMode && d == 2) || (!sameMode && d == 1)) return 2.0
        return 3.0 + 0.25 * d
    }

    /** Relative tempo change (half/double time allowed with a small penalty), capped. */
    fun tempoCost(a: Float, b: Float): Double {
        if (a <= 0f || b <= 0f) return UNKNOWN_TEMPO_COST
        val r = ln(b.toDouble() / a)
        val direct = abs(r)
        val octave = min(abs(r - LN2), abs(r + LN2))
        val best = min(direct, octave)
        val change = exp(best) - 1.0
        val cost = min(change / TEMPO_STEP, MAX_TEMPO_COST)
        return if (octave < direct) cost + OCTAVE_PENALTY else cost
    }

    /**
     * The energy each slot should ideally have: the arc's shape (rise to a peak at 70 %, ease off)
     * filled with the set's own energies by rank, so the target is always achievable and the
     * search isn't lured into leaving all the quiet songs for a big drop at the end.
     * Null when there's no energy spread to shape.
     */
    private fun idealEnergies(energies: List<Float>, slots: Int): DoubleArray? {
        if (energies.size < 2 || slots < 2) return null
        val sorted = energies.sorted()
        if (sorted.last() - sorted.first() < 1e-3f) return null
        val order = (0 until slots).sortedBy { arc(it.toDouble() / (slots - 1)) }
        val out = DoubleArray(slots)
        for ((rank, slot) in order.withIndex()) out[slot] = quantile(sorted, rank.toDouble() / (slots - 1))
        return out
    }

    private fun quantile(sorted: List<Float>, q: Double): Double {
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val i = pos.toInt().coerceAtMost(sorted.size - 2)
        val f = pos - i
        return sorted[i] * (1 - f) + sorted[i + 1] * f
    }

    /** Arc shape over the set: 0.4 at the start, peak at 70 %, 0.5 at the end. */
    private fun arc(p: Double): Double =
        if (p <= 0.7) 0.4 + 0.6 * (p / 0.7) else 1.0 - 0.5 * ((p - 0.7) / 0.3)
}
