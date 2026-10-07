package com.arnav.music.core.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.playback.Progress
import com.arnav.music.ui.theme.ArnavTheme
import org.koin.compose.koinInject
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min

/** Smoothing time constant of the level follower. */
private const val LOUDNESS_SMOOTH_MS = 150.0
/** Envelope levels this far below the song's loud parts (its 95th percentile) read as 0. */
private const val LOUDNESS_RANGE_DB = 30.0
/** Extrapolate at most this far past the last progress update. */
private const val LOUDNESS_MAX_EXTRAPOLATE_MS = 1_000L

/** Last known playback position and when we heard about it (separate from BeatPulse's). */
private class LoudnessAnchor {
    var positionMs = Long.MIN_VALUE
    var atNanos = 0L
}

/**
 * How loud the current local track is right now, 0..1, from its analysed energy envelope: the
 * level at the (extrapolated) playback position, linearly interpolated between envelope steps,
 * mapped on a dB scale (the song's loud parts ≈ 1, [LOUDNESS_RANGE_DB] below ≈ 0) and smoothed
 * with a ~150 ms follower. It's 0 — and no frame loop runs — when the track has no analysis
 * (e.g. YouTube), while paused, or under reduced motion.
 */
@Composable
fun rememberLoudness(trackId: String?, progress: Progress, isPlaying: Boolean): State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
    val reduced = ArnavTheme.motion.reduced
    val db = koinInject<ArnavDatabase>()
    val dao = remember(db) { db.audioFeatures() }
    val wanted = !reduced && trackId != null

    val envelope by produceState<ByteArray?>(null, trackId, wanted) {
        value = null
        val id = trackId
        if (wanted && id != null) {
            var stamp = Long.MIN_VALUE
            dao.observe(id).collect { row ->
                val next = row?.takeIf { it.ok && it.envelope.isNotEmpty() }
                // The query re-emits on every write to the table; only react to real changes.
                val nextStamp = next?.analyzedAt ?: Long.MIN_VALUE
                if (nextStamp != stamp || (next == null) != (value == null)) {
                    stamp = nextStamp
                    value = next?.envelope
                }
            }
        }
    }

    val anchor = remember { LoudnessAnchor() }
    val position = progress.positionMs
    SideEffect {
        if (anchor.positionMs != position) {
            anchor.positionMs = position
            anchor.atNanos = System.nanoTime()
        }
    }

    val env = envelope
    val running = wanted && isPlaying && env != null
    LaunchedEffect(running, env) {
        val values = env
        if (!running || values == null) {
            level.floatValue = 0f
            return@LaunchedEffect
        }
        anchor.atNanos = System.nanoTime()
        var smoothed = level.floatValue.toDouble()
        var lastFrame = 0L
        while (true) {
            val frame = withFrameNanos { it }
            val dtMs = if (lastFrame == 0L) 16.0 else ((frame - lastFrame) / 1_000_000.0).coerceIn(0.0, 100.0)
            lastFrame = frame
            val ahead = min((System.nanoTime() - anchor.atNanos) / 1_000_000L, LOUDNESS_MAX_EXTRAPOLATE_MS).coerceAtLeast(0L)
            val pos = anchor.positionMs.coerceAtLeast(0L) + ahead
            val target = envelopeLevel(values, pos)
            val alpha = 1.0 - exp(-dtMs / LOUDNESS_SMOOTH_MS)
            smoothed += (target - smoothed) * alpha
            level.floatValue = smoothed.toFloat().coerceIn(0f, 1f)
        }
    }
    return level
}

/** Envelope level at [positionMs] (steps are centred on their half-second), mapped to 0..1 in dB. */
private fun envelopeLevel(envelope: ByteArray, positionMs: Long): Double {
    val step = AudioFeatures.ENVELOPE_STEP_MS.toDouble()
    val x = (positionMs / step - 0.5).coerceIn(0.0, (envelope.size - 1).toDouble())
    val i = x.toInt()
    val f = x - i
    val a = (envelope[i].toInt() and 0xFF) / 255.0
    val b = (envelope[min(i + 1, envelope.size - 1)].toInt() and 0xFF) / 255.0
    val linear = a + (b - a) * f
    if (linear <= 1e-4) return 0.0
    return (1.0 + 20.0 * log10(linear) / LOUDNESS_RANGE_DB).coerceIn(0.0, 1.0)
}
