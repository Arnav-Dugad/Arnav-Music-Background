package com.arnav.music.domain.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Result of tempo tracking. [bpm] is 0 when no steady beat was found (low [confidence]). */
data class TempoEstimate(val bpm: Float, val confidence: Float, val beatOffsetMs: Long) {
    companion object {
        val NONE = TempoEstimate(0f, 0f, 0L)
    }
}

/** Onset strength per analysis frame; frame `t` is centred on sample `t * hopSamples`. */
class OnsetEnvelope(val values: FloatArray, val sampleRate: Int, val hopSamples: Int) {
    val frameRate: Float get() = sampleRate.toFloat() / hopSamples
    val size: Int get() = values.size
}

/** Everything the analyzer stores for one track. */
class AudioAnalysis(
    val tempo: TempoEstimate,
    val loudnessLufs: Float,
    val energy: Float,
    val onsetsPerSecond: Float,
    /** One unsigned byte (0..255) per envelope step. */
    val envelope: ByteArray,
    /** Musical key ([KeyNames] numbering, −1 when unsure). */
    val key: KeyEstimate = KeyEstimate.NONE,
    /** Where the music starts and the closing fade/silence begins. */
    val sections: TrackSections = TrackSections.NONE,
)

/**
 * Small, dependency-free audio analysis for on-device features (tempo, loudness, energy; key via
 * [KeyDetector], intro/outro via [SectionDetector]).
 * Input is mono PCM in [-1, 1]; tuned for ~11 025 Hz but works at any rate.
 * Every function takes an explicit `length` so callers can pass a partially filled buffer.
 */
object AudioDsp {
    const val MIN_BPM = 60f
    const val MAX_BPM = 190f
    /** Below this confidence there's no steady beat worth showing (raised further for short clips). */
    const val MIN_TEMPO_CONFIDENCE = 0.08f
    /** Returned for silence / nothing above the absolute gate. */
    const val SILENCE_LUFS = -70f

    private const val PRIOR_CENTER_BPM = 120.0
    private const val PRIOR_SIGMA_OCTAVES = 1.0
    private const val LOG_COMPRESSION = 100f

    // ------------------------------------------------------------------ analysis
    fun analyze(samples: FloatArray, sampleRate: Int, length: Int = samples.size, envelopeStepMs: Long = 500L, loudnessOffsetDb: Float = 0f): AudioAnalysis {
        val n = length.coerceIn(0, samples.size)
        val onset = onsetEnvelope(samples, sampleRate, n)
        val tempo = estimateTempo(onset)
        val rawLoudness = integratedLoudness(samples, sampleRate, n)
        val loudness = if (rawLoudness <= SILENCE_LUFS) SILENCE_LUFS else rawLoudness + loudnessOffsetDb
        val rate = onsetRate(onset)
        return AudioAnalysis(
            tempo = tempo,
            loudnessLufs = loudness,
            energy = energyScore(loudness, rate, tempo.bpm),
            onsetsPerSecond = rate,
            envelope = energyEnvelope(samples, sampleRate, n, envelopeStepMs),
            key = KeyDetector.detect(samples, sampleRate, n),
            sections = SectionDetector.detect(samples, sampleRate, n),
        )
    }

    // ------------------------------------------------------------------ onsets
    /** Frame length in samples: the power of two nearest to ~23 ms (256 at 11 025 Hz). */
    fun frameSize(sampleRate: Int): Int {
        val target = 0.023 * sampleRate
        val exp = log2(max(target, 64.0)).roundToInt().coerceIn(6, 14)
        return 1 shl exp
    }

    /**
     * Spectral-flux onset strength: Hann-windowed FFT frames (~23 ms, 50 % hop), log-compressed
     * magnitudes, positive differences summed over bins, then a local-mean high-pass and
     * half-wave rectification so only "something new started" moments remain.
     */
    fun onsetEnvelope(samples: FloatArray, sampleRate: Int, length: Int = samples.size): OnsetEnvelope {
        val n = length.coerceIn(0, samples.size)
        val size = frameSize(sampleRate)
        val hop = size / 2
        if (n == 0 || sampleRate <= 0) return OnsetEnvelope(FloatArray(0), max(sampleRate, 1), hop)
        val frames = 1 + n / hop
        val half = size / 2
        val bins = size / 2
        val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }
        var windowSum = 0f
        for (w in window) windowSum += w
        val norm = 2f / windowSum
        val fft = Fft(size)
        val re = FloatArray(size)
        val im = FloatArray(size)
        var prev = FloatArray(bins + 1)
        var cur = FloatArray(bins + 1)
        val flux = FloatArray(frames)
        for (t in 0 until frames) {
            val start = t * hop - half
            for (i in 0 until size) {
                val idx = start + i
                re[i] = if (idx in 0 until n) samples[idx] * window[i] else 0f
                im[i] = 0f
            }
            fft.transform(re, im)
            var sum = 0f
            for (k in 1..bins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
                val v = ln(1f + LOG_COMPRESSION * mag)
                cur[k] = v
                if (t > 0) {
                    val d = v - prev[k]
                    if (d > 0f) sum += d
                }
            }
            flux[t] = sum / bins
            val tmp = prev; prev = cur; cur = tmp
        }
        // Local-mean removal (±~0.19 s) keeps sustained loud passages from looking like onsets.
        val radius = max(2, (0.19f * sampleRate / hop).roundToInt())
        val prefix = DoubleArray(frames + 1)
        for (t in 0 until frames) prefix[t + 1] = prefix[t] + flux[t]
        val out = FloatArray(frames)
        for (t in 0 until frames) {
            val a = max(0, t - radius)
            val b = min(frames, t + radius + 1)
            val mean = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
            out[t] = max(0f, flux[t] - mean)
        }
        return OnsetEnvelope(out, sampleRate, hop)
    }

    /** Peaks of the onset envelope per second — a rough "how busy is it" measure. */
    fun onsetRate(env: OnsetEnvelope): Float {
        val v = env.values
        if (v.size < 8) return 0f
        var mean = 0.0
        for (x in v) mean += x
        mean /= v.size
        var varSum = 0.0
        for (x in v) varSum += (x - mean) * (x - mean)
        val std = sqrt(varSum / v.size)
        if (std <= 1e-9) return 0f
        val threshold = mean + 0.5 * std
        val w = max(1, (0.04f * env.frameRate).roundToInt())
        var count = 0
        for (t in v.indices) {
            val x = v[t]
            if (x <= threshold) continue
            var isPeak = true
            for (j in max(0, t - w)..min(v.size - 1, t + w)) {
                if (j == t) continue
                if (v[j] > x || (v[j] == x && j < t)) { isPeak = false; break }
            }
            if (isPeak) count++
        }
        val seconds = v.size / env.frameRate
        return if (seconds <= 0f) 0f else count / seconds
    }

    // ------------------------------------------------------------------ tempo
    /**
     * Autocorrelation tempo over [MIN_BPM]..[MAX_BPM] with a log-normal prior centred on 120 BPM
     * (which also settles octave ambiguities), parabolic peak refinement, then a fine comb search
     * that locks period and phase to the onsets. bpm = 0 when confidence is low.
     */
    fun estimateTempo(env: OnsetEnvelope): TempoEstimate {
        val g = env.values
        val n = g.size
        val fps = env.frameRate.toDouble()
        if (n < 16 || fps <= 0.0) return TempoEstimate.NONE
        val minLag = max(1, floor(60.0 * fps / MAX_BPM).toInt())
        val maxLag = ceil(60.0 * fps / MIN_BPM).toInt()
        if (n < maxLag * 2 + 2) return TempoEstimate.NONE
        var mean = 0.0
        for (x in g) mean += x
        mean /= n
        val x = DoubleArray(n) { g[it] - mean }
        var ac0 = 0.0
        for (v in x) ac0 += v * v
        ac0 /= n
        if (ac0 <= 1e-12) return TempoEstimate.NONE
        val lagEnd = min(n - 2, maxLag + 1)
        val ac = DoubleArray(lagEnd + 1)
        for (lag in max(1, minLag - 1)..lagEnd) {
            var s = 0.0
            val m = n - lag
            for (t in 0 until m) s += x[t] * x[t + lag]
            ac[lag] = s / m
        }
        // Prior-weighted pick among local maxima.
        var best = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            if (lag - 1 < 1 || lag + 1 > lagEnd) continue
            val a = ac[lag]
            if (a <= 0.0 || a < ac[lag - 1] || a < ac[lag + 1]) continue
            val score = a * prior(60.0 * fps / lag)
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (best < 0) return TempoEstimate.NONE
        var rangeMean = 0.0
        for (lag in minLag..maxLag) rangeMean += ac[min(lag, lagEnd)]
        rangeMean /= (maxLag - minLag + 1)
        val confidence = ((ac[best] - max(0.0, rangeMean)) / ac0).coerceIn(0.0, 1.0).toFloat()
        // Random envelopes produce autocorrelation peaks of roughly 2–3/sqrt(n); stay well above.
        val threshold = max(MIN_TEMPO_CONFIDENCE.toDouble(), 4.5 / sqrt(n.toDouble())).toFloat()
        if (confidence < threshold) return TempoEstimate(0f, confidence, 0L)

        // Parabolic interpolation around the integer lag.
        val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
        val denom = y0 - 2 * y1 + y2
        val delta = if (abs(denom) > 1e-12) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val coarse = best + delta

        // Fine comb search: the period that folds the onsets into the sharpest phase histogram.
        var bestPeriod = coarse
        var bestPhase = 0.0
        var bestPeak = -1.0
        val steps = 40
        for (i in -steps..steps) {
            val p = coarse * exp(i * 0.0005)
            val (peak, phase) = phaseFold(g, p)
            if (peak > bestPeak) { bestPeak = peak; bestPeriod = p; bestPhase = phase }
        }
        val bpm = (60.0 * fps / bestPeriod).toFloat()
        if (bpm < MIN_BPM - 1f || bpm > MAX_BPM + 1f) return TempoEstimate(0f, confidence, 0L)
        val hopMs = 1000.0 * env.hopSamples / env.sampleRate
        val periodMs = bestPeriod * hopMs
        var offsetMs = bestPhase * hopMs
        offsetMs = ((offsetMs % periodMs) + periodMs) % periodMs
        return TempoEstimate(bpm, confidence, offsetMs.roundToInt().toLong())
    }

    /** Log-normal tempo prior (in octaves) centred on [PRIOR_CENTER_BPM]. */
    private fun prior(bpm: Double): Double {
        val o = log2(bpm / PRIOR_CENTER_BPM) / PRIOR_SIGMA_OCTAVES
        return exp(-0.5 * o * o)
    }

    /**
     * Folds the envelope modulo [period] frames into a circular histogram (~1 frame per bin).
     * Returns the smoothed peak height (share of total onset mass) and its phase in frames.
     */
    private fun phaseFold(g: FloatArray, period: Double): Pair<Double, Double> {
        val bins = max(8, period.roundToInt())
        val hist = DoubleArray(bins)
        var total = 0.0
        for (t in g.indices) {
            val v = g[t]
            if (v <= 0f) continue
            val pos = ((t % period) / period) * bins
            val b0 = floor(pos).toInt()
            val frac = pos - b0
            hist[b0 % bins] += v * (1 - frac)
            hist[(b0 + 1) % bins] += v * frac
            total += v
        }
        if (total <= 0.0) return 0.0 to 0.0
        val smooth = DoubleArray(bins) { 0.25 * hist[(it - 1 + bins) % bins] + 0.5 * hist[it] + 0.25 * hist[(it + 1) % bins] }
        var bi = 0
        for (i in 1 until bins) if (smooth[i] > smooth[bi]) bi = i
        val a = smooth[(bi - 1 + bins) % bins]; val b = smooth[bi]; val c = smooth[(bi + 1) % bins]
        val d = a - 2 * b + c
        val off = if (abs(d) > 1e-12) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
        val phaseFrames = (((bi + off) / bins) * period + period) % period
        return (b / total) to phaseFrames
    }

    // ------------------------------------------------------------------ loudness
    /**
     * Approximate ITU-R BS.1770 integrated loudness of a mono signal: K-weighting (high-shelf +
     * RLB high-pass designed for [sampleRate]), 400 ms blocks with 75 % overlap, absolute gate at
     * −70 LUFS and relative gate at −10 LU. Returns [SILENCE_LUFS] when nothing passes the gates.
     */
    fun integratedLoudness(samples: FloatArray, sampleRate: Int, length: Int = samples.size): Float {
        val n = length.coerceIn(0, samples.size)
        if (n == 0 || sampleRate <= 0) return SILENCE_LUFS
        val shelf = Biquad.highShelf(sampleRate.toDouble(), 1681.974450955533, 3.999843853973347, 0.7071752369554196)
        val hp = Biquad.highPass(sampleRate.toDouble(), 38.13547087602444, 0.5003270373238773)
        val seg = max(1, (0.1 * sampleRate).roundToInt())
        val segments = n / seg
        val blockEnergies: DoubleArray
        if (segments < 4) {
            var s = 0.0
            for (i in 0 until n) { val y = hp.process(shelf.process(samples[i].toDouble())); s += y * y }
            blockEnergies = doubleArrayOf(s / n)
        } else {
            val segSum = DoubleArray(segments)
            for (i in 0 until segments * seg) {
                val y = hp.process(shelf.process(samples[i].toDouble()))
                segSum[i / seg] += y * y
            }
            blockEnergies = DoubleArray(segments - 3) { j -> (segSum[j] + segSum[j + 1] + segSum[j + 2] + segSum[j + 3]) / (4.0 * seg) }
        }
        fun lufs(z: Double) = -0.691 + 10.0 * log10(z)
        val absGated = blockEnergies.filter { it > 0.0 && lufs(it) > -70.0 }
        if (absGated.isEmpty()) return SILENCE_LUFS
        val relGate = lufs(absGated.average()) - 10.0
        val gated = absGated.filter { lufs(it) > relGate }
        if (gated.isEmpty()) return SILENCE_LUFS
        return lufs(gated.average()).toFloat().coerceAtLeast(SILENCE_LUFS)
    }

    // ------------------------------------------------------------------ energy
    /**
     * RMS per [stepMs] window scaled so the track's 95th percentile maps to 255.
     * Size is ceil(duration / step).
     */
    fun energyEnvelope(samples: FloatArray, sampleRate: Int, length: Int = samples.size, stepMs: Long = 500L): ByteArray {
        val n = length.coerceIn(0, samples.size)
        if (n == 0 || sampleRate <= 0 || stepMs <= 0) return ByteArray(0)
        val step = sampleRate * stepMs / 1000.0
        val count = ceil(n / step).toInt()
        val rms = FloatArray(count)
        for (i in 0 until count) {
            val a = (i * step).toInt().coerceAtMost(n)
            val b = ((i + 1) * step).toInt().coerceAtMost(n)
            var s = 0.0
            for (j in a until b) s += samples[j].toDouble() * samples[j]
            rms[i] = if (b > a) sqrt(s / (b - a)).toFloat() else 0f
        }
        val sorted = rms.copyOf().also { it.sort() }
        val p95 = sorted[((count - 1) * 0.95).roundToInt().coerceIn(0, count - 1)]
        if (p95 <= 1e-6f) return ByteArray(count)
        return ByteArray(count) { i -> (rms[i] / p95 * 255f).roundToInt().coerceIn(0, 255).toByte() }
    }

    /**
     * 0..1 energy: mostly loudness (−20..−6 LUFS → 0..1), then onset density (0..6 per second),
     * with a mild tempo nudge (70..160 BPM).
     */
    fun energyScore(loudnessLufs: Float, onsetsPerSecond: Float, bpm: Float): Float {
        val loud = ((loudnessLufs + 20f) / 14f).coerceIn(0f, 1f)
        val density = (onsetsPerSecond / 6f).coerceIn(0f, 1f)
        val tempo = if (bpm <= 0f) 0.3f else ((bpm - 70f) / 90f).coerceIn(0f, 1f)
        return (0.6f * loud + 0.3f * density + 0.1f * tempo).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ helpers
    /** Iterative in-place radix-2 FFT for a fixed power-of-two size. */
    class Fft(private val n: Int) {
        private val levels: Int
        private val cosTable: FloatArray
        private val sinTable: FloatArray
        private val rev: IntArray

        init {
            require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
            var l = 0
            while ((1 shl l) < n) l++
            levels = l
            cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
            sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
            rev = IntArray(n) { i ->
                var r = 0
                var v = i
                for (b in 0 until levels) { r = (r shl 1) or (v and 1); v = v shr 1 }
                r
            }
        }

        fun transform(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var size = 2
            while (size <= n) {
                val halfSize = size / 2
                val tableStep = n / size
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in i until i + halfSize) {
                        val l = j + halfSize
                        val c = cosTable[k]
                        val s = sinTable[k]
                        val tpre = re[l] * c + im[l] * s
                        val tpim = -re[l] * s + im[l] * c
                        re[l] = re[j] - tpre
                        im[l] = im[j] - tpim
                        re[j] += tpre
                        im[j] += tpim
                        k += tableStep
                    }
                    i += size
                }
                size *= 2
            }
        }
    }

    /** Direct-form-I biquad (RBJ cookbook designs via the bilinear transform). */
    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        companion object {
            /**
             * BS.1770 stage-1 shelf, parameterised as in libebur128 so that at 48 kHz it reproduces
             * the coefficients published in the standard.
             */
            fun highShelf(fs: Double, f0: Double, gainDb: Double, q: Double): Biquad {
                val k = tan(PI * min(f0, fs * 0.45) / fs)
                val vh = Math.pow(10.0, gainDb / 20.0)
                val vb = Math.pow(vh, 0.4996667741545416)
                val a0 = 1.0 + k / q + k * k
                return Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2.0 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2.0 * (k * k - 1.0) / a0,
                    (1.0 - k / q + k * k) / a0,
                )
            }

            /** Second-order high-pass (bilinear, pre-warped), unity gain in the pass band. */
            fun highPass(fs: Double, f0: Double, q: Double): Biquad {
                val k = tan(PI * min(f0, fs * 0.45) / fs)
                val a0 = 1.0 + k / q + k * k
                return Biquad(1.0 / a0, -2.0 / a0, 1.0 / a0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
            }
        }
    }
}
