package com.arnav.music.domain.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * "Sing" mode: reduces centre-panned vocals in a stereo mix while keeping bass, kick, cymbals and
 * anything panned to the sides.
 *
 * Signal path (per frame, mid/side):
 * - mid = (L+R)/2, side = (L−R)/2; both go through the same crossovers so they stay phase-aligned:
 *   a Linkwitz–Riley 8th-order split at ~150 Hz (bass stays untouched) and a 4th-order split at
 *   ~5 kHz (air/cymbals stay untouched). Linkwitz–Riley pairs sum back to an all-pass, so with
 *   nothing removed the output has the input's magnitude response.
 * - The 150 Hz–5 kHz "vocal" band is split again (≈500 Hz / ≈2 kHz) and, per sub-band, the part of
 *   the mid that is really shared by both channels is estimated from smoothed band energies:
 *   `w = (E[mid²] − E[side²]) / (E[mid²] + E[side²])` (1 = identical channels, 0 = one-sided or
 *   uncorrelated). The mid sub-band is reduced by `level × w`; the side is kept.
 * - Changes of [target] ramp over ~150 ms (no clicks); the output is soft-clipped.
 *
 * At level 0 (after the ramp) it is a bit-exact pass-through and costs almost nothing.
 * Not thread-safe: one instance per audio stream; [target] may be written from any thread.
 */
class VocalReducer(sampleRate: Int, rampMs: Float = 150f) {
    val sampleRate: Int = sampleRate.coerceAtLeast(8_000)

    /** Wanted vocal reduction, 0 (off) … 1 (max). Applied gradually. */
    @Volatile
    var target: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Current (ramped) reduction level. */
    var level: Float = 0f
        private set

    /** Wet/dry mix used to enter and leave processing without a click. */
    private var engaged = 0.0
    private val rampStep = 1.0 / (rampMs.coerceAtLeast(1f) / 1000.0 * this.sampleRate)

    /** True when nothing is being changed: the input passes through untouched. */
    val idle: Boolean get() = target == 0f && level == 0f && engaged == 0.0

    private val nyquistSafe = this.sampleRate * 0.45
    private val lowCross = minOf(LOW_CROSSOVER_HZ, nyquistSafe)
    private val highCross = minOf(HIGH_CROSSOVER_HZ, nyquistSafe)
    private val split1 = minOf(SPLIT_1_HZ, nyquistSafe)
    private val split2 = minOf(SPLIT_2_HZ, nyquistSafe)

    private val mid = Bands(this.sampleRate)
    private val side = Bands(this.sampleRate)
    private val energyMid = DoubleArray(SUB_BANDS)
    private val energySide = DoubleArray(SUB_BANDS)
    private val energyCoef = exp(-1.0 / (ENERGY_TAU_S * this.sampleRate))
    private var dirty = false

    private var outL = 0.0
    private var outR = 0.0

    /** Clears filter memory (after a seek or format change); keeps the current level. */
    fun reset() {
        mid.reset()
        side.reset()
        energyMid.fill(0.0)
        energySide.fill(0.0)
        dirty = false
    }

    /** In-place processing of interleaved 16-bit stereo PCM ([frames] L/R pairs from index 0). */
    fun processInterleaved16(pcm: ShortArray, frames: Int) {
        val n = minOf(frames, pcm.size / 2)
        if (n <= 0 || idleNow()) return
        var i = 0
        for (f in 0 until n) {
            processFrame(pcm[i] / 32768.0, pcm[i + 1] / 32768.0)
            pcm[i] = toShort(outL)
            pcm[i + 1] = toShort(outR)
            i += 2
        }
    }

    /** In-place processing of separate float channels in [-1, 1]. */
    fun process(left: FloatArray, right: FloatArray, frames: Int = minOf(left.size, right.size)) {
        val n = minOf(frames, left.size, right.size)
        if (n <= 0 || idleNow()) return
        for (f in 0 until n) {
            processFrame(left[f].toDouble(), right[f].toDouble())
            left[f] = outL.toFloat()
            right[f] = outR.toFloat()
        }
    }

    private fun idleNow(): Boolean {
        if (!idle) return false
        if (dirty) reset()
        return true
    }

    private fun processFrame(l: Double, r: Double) {
        dirty = true
        // Ramps (linear, ~150 ms full scale).
        val t = target.toDouble()
        val lv = level.toDouble()
        val nextLevel = when {
            lv < t -> minOf(t, lv + rampStep)
            lv > t -> maxOf(t, lv - rampStep)
            else -> lv
        }
        level = nextLevel.toFloat()
        val wantEngaged = if (t > 0.0 || nextLevel > 0.0) 1.0 else 0.0
        engaged = when {
            engaged < wantEngaged -> minOf(wantEngaged, engaged + rampStep)
            engaged > wantEngaged -> maxOf(wantEngaged, engaged - rampStep)
            else -> engaged
        }
        if (nextLevel <= 0.0 && engaged <= 0.0) {
            level = 0f
            engaged = 0.0
        }

        val m = (l + r) * 0.5
        val s = (l - r) * 0.5
        mid.split(m)
        side.split(s)

        var midOut = mid.bass + mid.air
        val a = energyCoef
        val b = 1.0 - a
        for (k in 0 until SUB_BANDS) {
            val mk = mid.sub[k]
            val sk = side.sub[k]
            energyMid[k] = a * energyMid[k] + b * mk * mk
            energySide[k] = a * energySide[k] + b * sk * sk
            val em = energyMid[k]
            val es = energySide[k]
            val total = em + es
            val w = if (total > ENERGY_FLOOR) ((em - es) / total).coerceIn(0.0, 1.0) else 0.0
            midOut += mk * (1.0 - nextLevel * w)
        }
        val sideOut = side.bass + side.air + side.sub[0] + side.sub[1] + side.sub[2]

        val wetL = midOut + sideOut
        val wetR = midOut - sideOut
        val e = engaged
        outL = softClip(l + (wetL - l) * e)
        outR = softClip(r + (wetR - r) * e)
    }

    /** Mid or side signal split into bass | 3 vocal sub-bands | air (sums to an all-passed input). */
    private inner class Bands(sr: Int) {
        private val bassLp = Array(4) { Biquad.lowPass(sr, lowCross, LR8_Q[it]) }
        private val bassHp = Array(4) { Biquad.highPass(sr, lowCross, LR8_Q[it]) }
        private val airLp = Array(2) { Biquad.lowPass(sr, highCross, LR4_Q) }
        private val airHp = Array(2) { Biquad.highPass(sr, highCross, LR4_Q) }
        private val sub1 = Biquad.lowPass(sr, split1, BUTTERWORTH_Q)
        private val sub2 = Biquad.lowPass(sr, split2, BUTTERWORTH_Q)

        var bass = 0.0
        var air = 0.0
        val sub = DoubleArray(SUB_BANDS)

        fun split(x: Double) {
            var lo = x
            for (f in bassLp) lo = f.process(lo)
            var hi = x
            for (f in bassHp) hi = f.process(hi)
            var voc = hi
            for (f in airLp) voc = f.process(voc)
            var top = hi
            for (f in airHp) top = f.process(top)
            bass = lo
            air = top
            // Complementary sub-bands of the vocal band: they always sum back to `voc` exactly.
            val s1 = sub1.process(voc)
            val rest = voc - s1
            val s2 = sub2.process(rest)
            sub[0] = s1
            sub[1] = s2
            sub[2] = rest - s2
        }

        fun reset() {
            bassLp.forEach { it.reset() }
            bassHp.forEach { it.reset() }
            airLp.forEach { it.reset() }
            airHp.forEach { it.reset() }
            sub1.reset()
            sub2.reset()
            bass = 0.0
            air = 0.0
            sub.fill(0.0)
        }
    }

    /** RBJ-cookbook biquad, transposed direct form II, double precision. */
    internal class Biquad private constructor(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            // Flush denormals so silence doesn't slow the audio thread down.
            if (abs(z1) < 1e-25) z1 = 0.0
            if (abs(z2) < 1e-25) z2 = 0.0
            return y
        }

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        companion object {
            fun lowPass(sr: Int, hz: Double, q: Double): Biquad {
                val w0 = 2.0 * PI * hz / sr
                val c = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun highPass(sr: Int, hz: Double, q: Double): Biquad {
                val w0 = 2.0 * PI * hz / sr
                val c = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    companion object {
        /** The reduction a tap on the Sing button switches to. */
        const val DEFAULT_LEVEL = 0.85f

        const val LOW_CROSSOVER_HZ = 150.0
        const val HIGH_CROSSOVER_HZ = 5_000.0
        private const val SPLIT_1_HZ = 500.0
        private const val SPLIT_2_HZ = 2_000.0
        private const val SUB_BANDS = 3
        private const val ENERGY_TAU_S = 0.08
        private const val ENERGY_FLOOR = 1e-10
        private const val BUTTERWORTH_Q = 0.7071067811865476
        private const val LR4_Q = BUTTERWORTH_Q
        /** Linkwitz–Riley 8th order = 4th-order Butterworth applied twice. */
        private val LR8_Q = doubleArrayOf(0.5411961001461970, 1.3065629648763766, 0.5411961001461970, 1.3065629648763766)

        private const val CLIP_KNEE = 0.85

        /** Linear up to the knee, then a smooth tanh shoulder that never exceeds ±1. */
        fun softClip(x: Double): Double {
            val ax = abs(x)
            if (ax <= CLIP_KNEE) return x
            val over = (ax - CLIP_KNEE) / (1.0 - CLIP_KNEE)
            val y = CLIP_KNEE + (1.0 - CLIP_KNEE) * tanh(over)
            return if (x < 0) -y else y
        }

        private fun toShort(x: Double): Short {
            val v = (x * 32768.0).toInt()
            return v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
