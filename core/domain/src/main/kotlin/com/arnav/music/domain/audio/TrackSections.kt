package com.arnav.music.domain.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Where the music really starts and where the closing fade/silence begins.
 * [introMs] is 0 when the song starts right away; [outroMs] is 0 when there's no quiet tail.
 */
data class TrackSections(val introMs: Long, val outroMs: Long) {
    companion object {
        val NONE = TrackSections(0L, 0L)
    }
}

/**
 * Intro/outro detection from short-term level (plain RMS in 50 ms blocks).
 *
 * - The reference is the median of the 400 ms short-term level over every non-silent moment.
 * - **Intro**: the first 50 ms block within [INTRO_DB] of that median where most of the following
 *   second stays within it too — so a lone click doesn't count. If that's past [MAX_INTRO_FRACTION]
 *   of the track (a genuinely quiet opening), only leading digital silence is skipped instead.
 * - **Outro**: the start of the final stretch whose short-term level stays at least [OUTRO_DB]
 *   below the median until the end (a fade-out tail or trailing silence); 0 when that stretch is
 *   shorter than [MIN_OUTRO_MS].
 */
object SectionDetector {
    const val BLOCK_MS = 50L
    const val INTRO_DB = 18.0
    const val OUTRO_DB = 10.0
    const val MAX_INTRO_FRACTION = 0.2
    const val MIN_OUTRO_MS = 1_500L
    /** Absolute floor for "there's sound at all" (dBFS). */
    private const val SILENCE_DBFS = -60.0
    /** Short-term window, in blocks (400 ms). */
    private const val SHORT_BLOCKS = 8
    /** Sustain check after the intro point, in blocks (1 s). */
    private const val SUSTAIN_BLOCKS = 20

    fun detect(samples: FloatArray, sampleRate: Int, length: Int = samples.size): TrackSections =
        detect(blockLevels(samples, sampleRate, length))

    /** Mean power per [BLOCK_MS] block (the last, partial block included). */
    fun blockLevels(samples: FloatArray, sampleRate: Int, length: Int = samples.size): DoubleArray {
        val n = length.coerceIn(0, samples.size)
        if (n == 0 || sampleRate <= 0) return DoubleArray(0)
        val block = max(1, (sampleRate * BLOCK_MS / 1000L).toInt())
        val count = (n + block - 1) / block
        return DoubleArray(count) { b ->
            val a = b * block
            val e = min(n, a + block)
            var s = 0.0
            for (i in a until e) s += samples[i].toDouble() * samples[i]
            s / (e - a)
        }
    }

    /** [power] holds mean power per [BLOCK_MS] block. */
    fun detect(power: DoubleArray): TrackSections {
        val count = power.size
        if (count < SUSTAIN_BLOCKS * 2) return TrackSections.NONE
        val prefix = DoubleArray(count + 1)
        for (i in 0 until count) prefix[i + 1] = prefix[i] + power[i]
        fun meanDb(from: Int, to: Int): Double {
            val a = from.coerceIn(0, count)
            val b = to.coerceIn(a, count)
            if (b == a) return db(power[a.coerceAtMost(count - 1)])
            return db((prefix[b] - prefix[a]) / (b - a))
        }
        // Short-term level centred on each block.
        val half = SHORT_BLOCKS / 2
        val shortTerm = DoubleArray(count) { meanDb(it - half, it + half) }
        val audible = shortTerm.filter { it > SILENCE_DBFS }.sorted()
        if (audible.size < SUSTAIN_BLOCKS) return TrackSections.NONE
        val median = audible[audible.size / 2]
        val durationMs = count * BLOCK_MS

        // ---- intro
        val introGate = median - INTRO_DB
        var musicStart = -1
        for (i in 0 until count) {
            if (db(power[i]) >= introGate && sustained(power, i, introGate)) { musicStart = i; break }
        }
        val cap = (durationMs * MAX_INTRO_FRACTION).toLong()
        var introMs = if (musicStart < 0) 0L else musicStart * BLOCK_MS
        if (introMs > cap) {
            val silenceGate = max(SILENCE_DBFS, median - 40.0)
            val firstSound = (0 until count).firstOrNull { db(power[it]) >= silenceGate } ?: 0
            introMs = min(firstSound * BLOCK_MS, cap)
        }

        // ---- outro
        val outroGate = median - OUTRO_DB
        var tail = count
        while (tail > 0 && shortTerm[tail - 1] <= outroGate) tail--
        val outroMs = if (tail >= count || (count - tail) * BLOCK_MS < MIN_OUTRO_MS || tail * BLOCK_MS <= introMs) 0L else tail * BLOCK_MS
        return TrackSections(introMs, outroMs)
    }

    /** Most of the second from [from] stays above [gateDb] (a click or a breath doesn't). */
    private fun sustained(power: DoubleArray, from: Int, gateDb: Double): Boolean {
        val end = min(power.size, from + SUSTAIN_BLOCKS)
        var loud = 0
        for (i in from until end) if (db(power[i]) >= gateDb) loud++
        return loud >= (end - from) * 0.6
    }

    private fun db(p: Double): Double = 10.0 * log10(p + 1e-12)
}
