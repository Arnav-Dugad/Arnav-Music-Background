package com.arnav.music.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Result of key detection. [key] uses the [KeyNames] numbering (−1 when the evidence is weak);
 * [confidence] is 0..1 and [correlation] is the raw Pearson correlation with the winning profile.
 */
data class KeyEstimate(val key: Int, val confidence: Float, val correlation: Float) {
    companion object {
        val NONE = KeyEstimate(KeyNames.UNKNOWN, 0f, 0f)
    }
}

/**
 * Musical key from audio: a track-average chromagram correlated with 24 rotated key profiles
 * (the Krumhansl–Schmuckler method, with Temperley's profiles).
 *
 * Chroma: Hann-windowed FFT frames (~0.37 s, 4096 samples at 11 025 Hz, 50 % hop). Bin power is
 * collected per semitone for the five octaves C2..B6 (65 Hz – 2 kHz), log-compressed, the frame's
 * mean level is removed (so broadband noise and the bass level don't count) and the positive part
 * is folded into 12 pitch classes. Frames are normalised to unit sum and averaged, quiet frames skipped.
 */
object KeyDetector {
    /** Lowest analysed note (C2, ~65 Hz) as a MIDI number; five octaves up to B6 (~1976 Hz). */
    const val LOW_MIDI = 36
    const val HIGH_MIDI = 95
    /** Below this the key isn't reported. */
    const val MIN_CONFIDENCE = 0.4f

    /**
     * Temperley's (Kostka–Payne corpus) key profiles, index 0 = tonic. They weigh the tonic triad
     * more than the Krumhansl–Kessler ratings, which keeps a bare I chord from reading as its
     * mediant minor (C–E–G vs E minor) once the tones' overtones land in the chroma.
     */
    private val MAJOR_PROFILE = doubleArrayOf(0.748, 0.060, 0.488, 0.082, 0.670, 0.460, 0.096, 0.715, 0.104, 0.366, 0.057, 0.400)
    private val MINOR_PROFILE = doubleArrayOf(0.712, 0.084, 0.474, 0.618, 0.049, 0.460, 0.105, 0.747, 0.404, 0.067, 0.133, 0.330)

    /** Chroma below this spread (std / mean) is basically flat: no tonal centre. */
    private const val FULL_CONTRAST = 0.35
    /** Frames quieter than this RMS (≈ −60 dBFS) are skipped. */
    private const val SILENT_RMS = 1e-3
    private const val LOG_SCALE = 1e4

    /** Frame length: the power of two nearest to ~0.37 s (4096 at 11 025 Hz). */
    fun frameSize(sampleRate: Int): Int {
        val target = 0.37 * sampleRate
        val exp = log2(max(target, 256.0)).roundToInt().coerceIn(8, 15)
        return 1 shl exp
    }

    fun detect(samples: FloatArray, sampleRate: Int, length: Int = samples.size): KeyEstimate =
        detect(chroma(samples, sampleRate, length))

    /**
     * Track-average chroma vector (index 0 = C), summing to 1; all zeros when nothing tonal was
     * heard (silence, too short).
     */
    fun chroma(samples: FloatArray, sampleRate: Int, length: Int = samples.size): FloatArray {
        val n = length.coerceIn(0, samples.size)
        val out = DoubleArray(12)
        if (sampleRate <= 0 || n == 0) return FloatArray(12)
        val size = frameSize(sampleRate)
        val hop = size / 2
        // Map each FFT bin to the nearest semitone in range (−1 = outside).
        val binNote = IntArray(size / 2 + 1) { k ->
            if (k == 0) -1 else {
                val f = k.toDouble() * sampleRate / size
                val midi = (69.0 + 12.0 * log2(f / 440.0)).roundToInt()
                if (midi in LOW_MIDI..HIGH_MIDI && f < sampleRate * 0.45) midi - LOW_MIDI else -1
            }
        }
        val notes = HIGH_MIDI - LOW_MIDI + 1
        // Semitones too narrow to own a bin (only at very low rates) borrow the nearest one.
        val noteBin = IntArray(notes) { i ->
            val f = 440.0 * Math.pow(2.0, (i + LOW_MIDI - 69) / 12.0)
            (f * size / sampleRate).roundToInt().coerceIn(1, size / 2)
        }
        val ownsBin = BooleanArray(notes)
        for (k in binNote.indices) if (binNote[k] >= 0) ownsBin[binNote[k]] = true

        val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }
        val fft = AudioDsp.Fft(size)
        val re = FloatArray(size)
        val im = FloatArray(size)
        val notePower = DoubleArray(notes)
        val level = DoubleArray(notes)
        val frame = DoubleArray(12)
        var frames = 0
        var start = 0
        while (start < n) {
            var energy = 0.0
            for (i in 0 until size) {
                val idx = start + i
                val x = if (idx < n) samples[idx] else 0f
                energy += x.toDouble() * x
                re[i] = x * window[i]
                im[i] = 0f
            }
            start += hop
            if (sqrt(energy / size) < SILENT_RMS) continue
            fft.transform(re, im)
            notePower.fill(0.0)
            for (k in 1..size / 2) {
                val note = binNote[k]
                if (note >= 0) notePower[note] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            }
            for (i in 0 until notes) {
                if (!ownsBin[i]) {
                    val k = noteBin[i]
                    notePower[i] = (re[k] * re[k] + im[k] * im[k]).toDouble()
                }
            }
            var mean = 0.0
            for (i in 0 until notes) {
                level[i] = ln(1.0 + LOG_SCALE * notePower[i] / size)
                mean += level[i]
            }
            mean /= notes
            frame.fill(0.0)
            var total = 0.0
            for (i in 0 until notes) {
                val v = level[i] - mean
                if (v > 0) { frame[(i + LOW_MIDI) % 12] += v; total += v }
            }
            if (total > 1e-9) {
                for (pc in 0 until 12) out[pc] += frame[pc] / total
                frames++
            }
        }
        if (frames == 0) return FloatArray(12)
        return FloatArray(12) { (out[it] / frames).toFloat() }
    }

    /** Krumhansl–Schmuckler: correlates [chroma] (index 0 = C) with all 24 rotated key profiles. */
    fun detect(chroma: FloatArray): KeyEstimate {
        if (chroma.size != 12) return KeyEstimate.NONE
        var mean = 0.0
        for (v in chroma) mean += v
        mean /= 12
        if (mean <= 1e-9) return KeyEstimate.NONE
        var varSum = 0.0
        for (v in chroma) varSum += (v - mean) * (v - mean)
        val contrast = sqrt(varSum / 12) / mean
        var bestKey = -1
        var best = -2.0
        for (key in 0 until 24) {
            val r = correlation(chroma, if (key < 12) MAJOR_PROFILE else MINOR_PROFILE, key % 12)
            if (r > best) { best = r; bestKey = key }
        }
        val confidence = (best.coerceIn(0.0, 1.0) * (contrast / FULL_CONTRAST).coerceIn(0.0, 1.0)).toFloat()
        val key = if (confidence >= MIN_CONFIDENCE) bestKey else KeyNames.UNKNOWN
        return KeyEstimate(key, confidence, best.toFloat())
    }

    /** Pearson correlation between [chroma] and [profile] rotated so its tonic sits on [tonic]. */
    fun correlation(chroma: FloatArray, profile: DoubleArray, tonic: Int): Double {
        var mx = 0.0
        var my = 0.0
        for (pc in 0 until 12) { mx += chroma[pc]; my += profile[Math.floorMod(pc - tonic, 12)] }
        mx /= 12; my /= 12
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (pc in 0 until 12) {
            val dx = chroma[pc] - mx
            val dy = profile[Math.floorMod(pc - tonic, 12)] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx <= 1e-18 || syy <= 1e-18) return 0.0
        return sxy / sqrt(sxx * syy)
    }
}
