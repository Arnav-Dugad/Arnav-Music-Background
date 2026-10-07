package com.arnav.music.domain.lyrics

import com.arnav.music.domain.audio.VocalReducer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Streaming "is someone singing?" meter for on-device analysis, one value per [stepMs].
 *
 * Feed it the mid ((L+R)/2) and side ((L−R)/2) signal. Lead vocals sit in the centre and in the
 * ~300 Hz–3.4 kHz band, so per step it measures the centre-only part of that band — band energy of
 * the mid minus band energy of the side — relative to all the energy in the step, and weights it by
 * how much the band level moves within the step (syllables come and go; pads and drones don't).
 * Mono sources (side = 0) still work, with less contrast. [finish] scales the curve so the song's
 * busiest vocal parts read ≈ 1.
 */
class VocalActivityMeter(sampleRate: Int, val stepMs: Long = 500L) {
    private val rate = sampleRate.coerceAtLeast(4_000)
    private val stepSamples = max(1, (rate * stepMs / 1000L).toInt())
    private val subSamples = max(1, rate * SUB_MS / 1000)
    private val top = min(BAND_HIGH_HZ, rate * 0.45)

    private val midHp = Array(2) { VocalReducer.Biquad.highPass(rate, BAND_LOW_HZ, Q) }
    private val midLp = Array(2) { VocalReducer.Biquad.lowPass(rate, top, Q) }
    private val sideHp = Array(2) { VocalReducer.Biquad.highPass(rate, BAND_LOW_HZ, Q) }
    private val sideLp = Array(2) { VocalReducer.Biquad.lowPass(rate, top, Q) }

    private val values = ArrayList<Float>()
    private var count = 0
    private var eTotal = 0.0
    private var eMid = 0.0
    private var eSide = 0.0
    private var subCount = 0
    private var subEnergy = 0.0
    private val subs = ArrayList<Double>()

    fun push(mid: Float, side: Float) {
        val m = mid.toDouble()
        val s = side.toDouble()
        var bm = m
        for (f in midHp) bm = f.process(bm)
        for (f in midLp) bm = f.process(bm)
        var bs = s
        for (f in sideHp) bs = f.process(bs)
        for (f in sideLp) bs = f.process(bs)
        eTotal += m * m + s * s
        eMid += bm * bm
        eSide += bs * bs
        subEnergy += bm * bm
        if (++subCount == subSamples) {
            subs += subEnergy
            subEnergy = 0.0
            subCount = 0
        }
        if (++count == stepSamples) closeStep()
    }

    private fun closeStep() {
        val rms = sqrt(eTotal / max(1, count))
        val v = if (rms < SILENCE_RMS || eTotal <= 0.0) 0f else {
            val centre = ((eMid - eSide) / eTotal).coerceIn(0.0, 1.0)
            val mean = subs.average().takeIf { !it.isNaN() } ?: 0.0
            val modulation = if (subs.size < 2 || mean <= 0.0) 0.0 else {
                val variance = subs.sumOf { (it - mean) * (it - mean) } / subs.size
                (sqrt(variance) / mean / MODULATION_FULL).coerceIn(0.0, 1.0)
            }
            (centre * (0.6 + 0.4 * modulation)).toFloat()
        }
        values += v
        count = 0
        eTotal = 0.0
        eMid = 0.0
        eSide = 0.0
        subs.clear()
        subCount = 0
        subEnergy = 0.0
    }

    /** The curve (0..1 per step), scaled so the 95th percentile of the non-silent steps is 1. */
    fun finish(): FloatArray {
        if (count >= stepSamples / 2) closeStep()
        val raw = values.toFloatArray()
        val nonZero = raw.filter { it > 0f }.sorted()
        if (nonZero.isEmpty()) return FloatArray(raw.size)
        val ref = nonZero[((nonZero.size - 1) * 0.95).roundToInt()].coerceAtLeast(1e-4f)
        return FloatArray(raw.size) { (raw[it] / ref).coerceIn(0f, 1f) }
    }

    companion object {
        const val BAND_LOW_HZ = 300.0
        const val BAND_HIGH_HZ = 3_400.0
        private const val Q = 0.7071067811865476
        private const val SUB_MS = 50
        private const val SILENCE_RMS = 1e-3
        /** Coefficient of variation of 50 ms band energies that counts as clearly syllabic. */
        private const val MODULATION_FULL = 0.8

        /** One unsigned byte per step (0..255) for storage. */
        fun toBytes(curve: FloatArray): ByteArray = ByteArray(curve.size) { (curve[it].coerceIn(0f, 1f) * 255f).roundToInt().toByte() }

        fun fromBytes(bytes: ByteArray): FloatArray = FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
    }
}
