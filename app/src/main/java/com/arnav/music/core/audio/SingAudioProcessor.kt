package com.arnav.music.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.arnav.music.domain.audio.VocalReducer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 audio processor for [SingMode]: runs [VocalReducer] on 16-bit stereo PCM. Any other
 * format (mono, float, surround) makes the processor inactive, so ExoPlayer passes it through.
 * At level 0 the audio is copied untouched (bit-exact).
 *
 * Inserted into the player's [androidx.media3.exoplayer.audio.DefaultAudioSink] by PlaybackService,
 * before the sink's own silence-skipping and speed processors.
 */
@OptIn(UnstableApi::class)
class SingAudioProcessor(private val level: () -> Float = { SingMode.target }) : BaseAudioProcessor() {
    private var reducer: VocalReducer? = null
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val r = reducer
        if (r == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        r.target = level()
        if (r.idle) {
            // Pass-through: nothing to change.
            output.put(inputBuffer)
            output.flip()
            return
        }
        val frames = remaining / BYTES_PER_FRAME
        val samples = frames * 2
        if (scratch.size < samples) scratch = ShortArray(samples)
        // An independent view in native order (PCM in ExoPlayer is native-endian).
        inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, samples)
        inputBuffer.position(inputBuffer.position() + samples * 2)
        r.processInterleaved16(scratch, frames)
        output.asShortBuffer().put(scratch, 0, samples)
        output.position(samples * 2)
        // Whole frames always arrive; copy any stray trailing bytes unchanged just in case.
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        // The configured format is applied on flush.
        val format = inputAudioFormat
        if (format.encoding == C.ENCODING_PCM_16BIT && format.channelCount == 2 && format.sampleRate > 0) {
            val current = reducer
            if (current == null || current.sampleRate != format.sampleRate) {
                reducer = VocalReducer(format.sampleRate).also { it.target = level() }
            } else {
                current.reset()
            }
        } else {
            reducer = null
        }
    }

    override fun onReset() {
        reducer = null
        scratch = ShortArray(0)
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
