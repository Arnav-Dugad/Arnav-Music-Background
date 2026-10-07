package com.arnav.music.core.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arnav.music.domain.audio.AudioDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Mono PCM in [-1, 1]; only the first [length] samples of [samples] are valid. */
class DecodedAudio(val samples: FloatArray, val length: Int, val sampleRate: Int, val sourceChannels: Int)

/**
 * Optional second output of [AudioDecoder.decode]: every output sample as mid ((L+R)/2, the same as
 * the mono output) and side ((L−R)/2, 0 for mono sources), at the output rate. Streaming, so the
 * side channel never has to be kept in memory.
 */
interface StereoSink {
    fun begin(sampleRate: Int)
    fun push(mid: Float, side: Float)
}

/**
 * Decodes a local audio file into low-rate mono PCM for analysis: MediaExtractor + MediaCodec
 * (synchronous API), downmix to mono, anti-alias low-pass and integer decimation to at most
 * ~11 025 Hz ([targetRate] may ask for another ceiling, e.g. 16 kHz for AI transcription). Only the
 * first [MAX_SECONDS] are decoded so memory stays bounded. A [stereo] sink also receives mid/side.
 */
object AudioDecoder {
    const val TARGET_RATE = 11_025
    const val MAX_SECONDS = 8 * 60
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 300

    fun decode(
        context: Context,
        uri: Uri,
        maxSeconds: Int = MAX_SECONDS,
        isCancelled: () -> Boolean = { false },
        targetRate: Int = TARGET_RATE,
        stereo: StereoSink? = null,
    ): DecodedAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
            }
            val inFormat = format ?: return null
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIndex)

            var srcRate = inFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            val maxUs = maxSeconds * 1_000_000L

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()
            started = true

            var resampler: Resampler? = null
            var out = FloatArray(0)
            var outLen = 0
            var maxSamples = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                if (isCancelled()) return null
                var progressed = false
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        val time = extractor.sampleTime
                        if (size < 0 || time > maxUs) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            progressed = true
                            dec.queueInputBuffer(inIndex, 0, size, time.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        if (resampler == null) {
                            srcRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                        }
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        encoding = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        progressed = true
                    }
                    outIndex >= 0 -> {
                        progressed = true
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        val buf = dec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val rs = resampler ?: Resampler(srcRate, targetRate, stereo).also { r ->
                                resampler = r
                                stereo?.begin(r.outRate)
                                val estSeconds = if (durationUs > 0) ceil(durationUs / 1_000_000.0).toInt() + 2 else maxSeconds
                                maxSamples = maxSeconds * r.outRate
                                out = FloatArray(min(maxSamples, estSeconds.coerceAtLeast(10) * r.outRate))
                            }
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val slice = buf.slice().order(ByteOrder.nativeOrder())
                            val written = rs.feed(slice, encoding, channels) { v ->
                                if (outLen >= out.size && out.size < maxSamples) {
                                    out = out.copyOf(min(maxSamples, maxOf(out.size * 3 / 2, out.size + rs.outRate)))
                                }
                                if (outLen < out.size) { out[outLen++] = v; true } else false
                            }
                            if (!written) outputDone = true
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                    }
                }
                // Guard against a decoder that stops responding (or hangs at end of stream).
                idle = if (progressed) 0 else idle + 1
                if (idle > MAX_IDLE_POLLS) break
            }
            val r = resampler ?: return null
            if (outLen == 0) return null
            return DecodedAudio(out, outLen, r.outRate, channels)
        } catch (e: Exception) {
            return null
        } finally {
            codec?.let { c ->
                if (started) runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    /**
     * Streaming downmix + low-pass + integer decimation. The output rate is the source rate divided
     * by the smallest integer that brings it to ≤ [targetRate] (44.1 kHz → 11 025 Hz, 48 kHz → 9 600 Hz
     * by default). With a [stereo] sink the side signal (first two channels) is filtered and
     * decimated the same way.
     */
    private class Resampler(srcRate: Int, targetRate: Int, private val stereo: StereoSink?) {
        private val factor = maxOf(1, ceil(srcRate.toDouble() / targetRate.coerceAtLeast(1_000)).toInt())
        val outRate = maxOf(1, srcRate / factor)
        private val lp1: AudioDsp.Biquad? = if (factor > 1) lowPass(srcRate.toDouble(), 0.42 * outRate) else null
        private val lp2: AudioDsp.Biquad? = if (factor > 1) lowPass(srcRate.toDouble(), 0.42 * outRate) else null
        private val sideLp1: AudioDsp.Biquad? = if (factor > 1 && stereo != null) lowPass(srcRate.toDouble(), 0.42 * outRate) else null
        private val sideLp2: AudioDsp.Biquad? = if (factor > 1 && stereo != null) lowPass(srcRate.toDouble(), 0.42 * outRate) else null
        private var acc = 0.0
        private var sideAcc = 0.0
        private var count = 0

        /** Feeds interleaved PCM; returns false once [sink] refuses more samples. */
        fun feed(buf: ByteBuffer, encoding: Int, channels: Int, sink: (Float) -> Boolean): Boolean {
            val ch = channels.coerceAtLeast(1)
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = buf.asFloatBuffer()
                    val frames = fb.remaining() / ch
                    for (i in 0 until frames) {
                        var s = 0f
                        var side = 0f
                        for (c in 0 until ch) {
                            val v = fb.get()
                            s += v
                            if (c == 0) side += v else if (c == 1) side -= v
                        }
                        if (!push(s / ch, if (ch >= 2) side * 0.5f else 0f, sink)) return false
                    }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    val frames = buf.remaining() / ch
                    for (i in 0 until frames) {
                        var s = 0f
                        var side = 0f
                        for (c in 0 until ch) {
                            val v = ((buf.get().toInt() and 0xFF) - 128) / 128f
                            s += v
                            if (c == 0) side += v else if (c == 1) side -= v
                        }
                        if (!push(s / ch, if (ch >= 2) side * 0.5f else 0f, sink)) return false
                    }
                }
                else -> {
                    val sb = buf.asShortBuffer()
                    val frames = sb.remaining() / ch
                    for (i in 0 until frames) {
                        var s = 0f
                        var side = 0f
                        for (c in 0 until ch) {
                            val v = sb.get() / 32768f
                            s += v
                            if (c == 0) side += v else if (c == 1) side -= v
                        }
                        if (!push(s / ch, if (ch >= 2) side * 0.5f else 0f, sink)) return false
                    }
                }
            }
            return true
        }

        private fun push(x: Float, side: Float, sink: (Float) -> Boolean): Boolean {
            if (factor == 1) {
                val v = x.coerceIn(-1f, 1f)
                stereo?.push(v, side.coerceIn(-1f, 1f))
                return sink(v)
            }
            val y = lp2!!.process(lp1!!.process(x.toDouble()))
            acc += y
            if (stereo != null) sideAcc += sideLp2!!.process(sideLp1!!.process(side.toDouble()))
            if (++count == factor) {
                val v = (acc / factor).toFloat().coerceIn(-1f, 1f)
                acc = 0.0
                count = 0
                if (stereo != null) {
                    stereo.push(v, (sideAcc / factor).toFloat().coerceIn(-1f, 1f))
                    sideAcc = 0.0
                }
                return sink(v)
            }
            return true
        }

        /** RBJ Butterworth low-pass (Q = 1/√2); two in series give a 4th-order roll-off. */
        private fun lowPass(fs: Double, cutoff: Double): AudioDsp.Biquad {
            val w0 = 2.0 * PI * min(cutoff, fs * 0.45) / fs
            val alpha = sin(w0) / (2.0 * 0.7071067811865476)
            val cw = cos(w0)
            val a0 = 1.0 + alpha
            return AudioDsp.Biquad((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        }
    }
}
