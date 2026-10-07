package com.arnav.music.domain.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** One "the beat drops" moment: [atMs] into the song, [strength] 0..1 (how big the jump is). */
data class BeatDrop(val atMs: Long, val strength: Float)

/**
 * Finds beat drops in a song's energy envelope (RMS per step, linear, ~1 at the loud parts — the
 * analyzer's stored 500 ms envelope works as is).
 *
 * A drop at step `i` needs all of:
 * - **Before**: a sustained lower stretch (a breakdown or build-up) — the [PRE_MS] window's median
 *   is at least [MIN_RISE_DB] below the loud stretch after, and even its upper quartile stays
 *   [MIN_GAP_DB] below it (a few loud hits or a riser at the very end are fine).
 * - **After**: it stays high — the [POST_MS] window's lower quartile is that loud stretch, and its
 *   median is within [LOUD_WITHIN_DB] of the song's loud parts (its 80th percentile).
 * - **Sharp**: the 1 s step across the boundary is at least [MIN_STEP_DB] and at least
 *   [MIN_STEP_SHARE] of the whole rise, so a gradual crescendo (however big) never counts. For that
 *   share, a riser of up to ~1 s right before the drop is skipped over when the second before it is
 *   still at the breakdown's level. Every step of the second after must also clear every step of the
 *   second before by [MIN_CLEAR_DB], and land [MIN_ABOVE_TREND_DB] above the run-up's rising trend
 *   (a least-squares line through the [PRE_MS] window) — the ways a noisy swell would sneak through.
 *
 * The strongest local maxima are kept, at most [MAX_DROPS], at least [MIN_SPACING_MS] apart. The
 * time is interpolated within the step (in power) and snapped to the nearest beat when a tempo is
 * known and the beat is within [MAX_SNAP_MS].
 */
object DropDetector {
    const val PRE_MS = 6_000L
    const val POST_MS = 4_000L
    const val MIN_RISE_DB = 6.0
    const val MIN_GAP_DB = 2.0
    const val MIN_STEP_DB = 5.0
    const val MIN_STEP_SHARE = 0.35
    const val MIN_CLEAR_DB = 2.5
    const val MIN_ABOVE_TREND_DB = 4.5
    const val LOUD_WITHIN_DB = 6.0
    const val MAX_DROPS = 6
    const val MIN_SPACING_MS = 20_000L
    const val MAX_SNAP_MS = 350L
    /** How far the second before a riser may sit from the breakdown's median (dB). */
    private const val RISER_FLAT_DB = 3.0
    /** Levels are floored here (dB below the envelope's reference, ≈ its loud parts). */
    private const val FLOOR_DB = -60.0
    /** A "breakdown" that's essentially silence (a gap between tracks, a hidden track) isn't one. */
    private const val AUDIBLE_DB = -45.0
    /** A rise this big (dB) is a full-strength drop. */
    private const val FULL_RISE_DB = 18.0

    /** [envelope]: one unsigned byte per [stepMs], 255 ≈ the song's loud parts (as stored by the analyzer). */
    fun detect(envelope: ByteArray, stepMs: Long, bpm: Float = 0f, beatOffsetMs: Long = 0L): List<BeatDrop> =
        detect(FloatArray(envelope.size) { (envelope[it].toInt() and 0xFF) / 255f }, stepMs, bpm, beatOffsetMs)

    /** [levels]: linear RMS per [stepMs], ≈1 at the loud parts. */
    fun detect(levels: FloatArray, stepMs: Long, bpm: Float = 0f, beatOffsetMs: Long = 0L): List<BeatDrop> {
        if (stepMs <= 0L) return emptyList()
        val pre = ceilDiv(PRE_MS, stepMs)
        val post = ceilDiv(POST_MS, stepMs)
        val stepWin = max(1, ceilDiv(1_000L, stepMs))
        val n = levels.size
        if (n < pre + post + 1) return emptyList()
        val db = DoubleArray(n) { toDb(levels[it]) }
        val sorted = db.copyOf().also { it.sort() }
        val loudRef = sorted[((n - 1) * 0.8).toInt()]
        if (loudRef <= AUDIBLE_DB) return emptyList()

        // Score every boundary i (the drop happens in step i).
        val score = DoubleArray(n)
        val rises = DoubleArray(n)
        for (i in pre..n - post) {
            val before = db.copyOfRange(i - pre, i).also { it.sort() }
            val after = db.copyOfRange(i, i + post).also { it.sort() }
            val preMed = quantile(before, 0.5)
            val preHigh = quantile(before, 0.75)
            val postLow = quantile(after, 0.25)
            val postMed = quantile(after, 0.5)
            val rise = postLow - preMed
            if (rise < MIN_RISE_DB) continue
            if (postLow - preHigh < MIN_GAP_DB) continue
            if (postMed < loudRef - LOUD_WITHIN_DB) continue
            if (preMed < AUDIBLE_DB) continue
            val after1 = mean(db, i, i + stepWin)
            val stepA = after1 - mean(db, i - stepWin, i)
            // A short riser right before the drop: measure from the second before it instead,
            // but only when that second still sits at the breakdown's level (a slope never does).
            val earlier = mean(db, i - 2 * stepWin, i - stepWin)
            val stepB = if (abs(earlier - preMed) <= RISER_FLAT_DB) after1 - earlier else 0.0
            if (stepA < MIN_STEP_DB || max(stepA, stepB) < MIN_STEP_SHARE * rise) continue
            // Every step just after must clear every step just before (a noisy slope doesn't).
            val clear = lowest(db, i, i + stepWin) - highest(db, i - stepWin, i)
            if (clear < MIN_CLEAR_DB) continue
            // It must jump above where the run-up was heading (a crescendo lands right on its trend).
            if (after1 - trendAt(db, i - pre, i, i + (stepWin - 1) / 2.0) < MIN_ABOVE_TREND_DB) continue
            // Scored on the immediate step so the boundary itself wins over the steps after it.
            score[i] = rise + 0.5 * stepA
            rises[i] = rise
        }

        // Local maxima only (a drop smeared over two steps is one drop), strongest first.
        val candidates = (pre..n - post).filter { i ->
            score[i] > 0.0 && (i == 0 || score[i] >= score[i - 1]) && (i == n - 1 || score[i] > score[i + 1])
        }.sortedByDescending { score[it] }

        val beatMs = if (bpm > 0f) 60_000.0 / bpm else 0.0
        val picked = ArrayList<BeatDrop>()
        for (i in candidates) {
            if (picked.size >= MAX_DROPS) break
            val at = snap(onsetMs(levels, db, i, pre, post, stepMs), beatMs, beatOffsetMs)
            if (picked.any { abs(it.atMs - at) < MIN_SPACING_MS }) continue
            picked += BeatDrop(at, (rises[i] / FULL_RISE_DB).coerceIn(0.0, 1.0).toFloat())
        }
        return picked.sortedBy { it.atMs }
    }

    /** Where in step [i] the music jumped: the share of the step that's already at the loud level. */
    private fun onsetMs(levels: FloatArray, db: DoubleArray, i: Int, pre: Int, post: Int, stepMs: Long): Long {
        val before = db.copyOfRange(i - pre, i).also { it.sort() }
        val after = db.copyOfRange(i + 1, min(db.size, i + post)).also { it.sort() }
        val pPre = power(quantile(before, 0.5))
        val pPost = if (after.isEmpty()) power(db[i]) else power(quantile(after, 0.5))
        val pHere = levels[i].toDouble().let { it * it }
        val loudShare = if (pPost - pPre <= 1e-12) 1.0 else ((pHere - pPre) / (pPost - pPre)).coerceIn(0.0, 1.0)
        return ((i + 1 - loudShare) * stepMs).roundToLong()
    }

    /** Nearest beat of the grid (offset + k·beat), if it's close enough; otherwise [atMs] unchanged. */
    private fun snap(atMs: Long, beatMs: Double, offsetMs: Long): Long {
        if (beatMs <= 0.0) return atMs
        val k = Math.round((atMs - offsetMs) / beatMs)
        val beat = (offsetMs + k * beatMs).roundToLong()
        return if (beat >= 0L && abs(beat - atMs) <= MAX_SNAP_MS) beat else atMs
    }

    private fun toDb(v: Float): Double = if (v <= 0f) FLOOR_DB else max(FLOOR_DB, 20.0 * log10(v.toDouble()))

    private fun power(db: Double): Double = 10.0.pow(db / 10.0)

    /** [s] sorted ascending, non-empty. */
    private fun quantile(s: DoubleArray, q: Double): Double {
        val x = q * (s.size - 1)
        val lo = x.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (x - lo)
    }

    private fun mean(a: DoubleArray, from: Int, to: Int): Double {
        val f = from.coerceIn(0, a.size)
        val t = to.coerceIn(f, a.size)
        if (t == f) return a[f.coerceAtMost(a.size - 1)]
        var s = 0.0
        for (k in f until t) s += a[k]
        return s / (t - f)
    }

    /** Least-squares line through a[from until to], evaluated at index [x]. */
    private fun trendAt(a: DoubleArray, from: Int, to: Int, x: Double): Double {
        val n = to - from
        if (n < 2) return a[from.coerceIn(0, a.size - 1)]
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (k in from until to) {
            val xk = k.toDouble()
            sx += xk; sy += a[k]; sxx += xk * xk; sxy += xk * a[k]
        }
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-9) return sy / n
        val slope = (n * sxy - sx * sy) / den
        // Only a rising run-up raises the bar; a falling one says nothing about a crescendo.
        val b = max(0.0, slope)
        return (sy - b * sx) / n + b * x
    }

    private fun lowest(a: DoubleArray, from: Int, to: Int): Double {
        var m = Double.MAX_VALUE
        for (k in from.coerceAtLeast(0) until to.coerceAtMost(a.size)) m = min(m, a[k])
        return if (m == Double.MAX_VALUE) FLOOR_DB else m
    }

    private fun highest(a: DoubleArray, from: Int, to: Int): Double {
        var m = -Double.MAX_VALUE
        for (k in from.coerceAtLeast(0) until to.coerceAtMost(a.size)) m = max(m, a[k])
        return if (m == -Double.MAX_VALUE) FLOOR_DB else m
    }

    private fun ceilDiv(a: Long, b: Long): Int = ((a + b - 1) / b).toInt()
}
