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
import com.arnav.music.core.db.AudioFeaturesEntity
import com.arnav.music.core.playback.Progress
import com.arnav.music.ui.theme.ArnavTheme
import org.koin.compose.koinInject
import kotlin.math.exp
import kotlin.math.min

private const val ATTACK_MS = 60.0
private const val DECAY_MS = 260.0
/** Above this tempo only every other beat pulses (keeps it ≤ ~2.3 Hz — never a flicker). */
private const val HALF_RATE_ABOVE_BPM = 140f
/** Extrapolate at most this far past the last progress update. */
private const val MAX_EXTRAPOLATE_MS = 1_000L

/** Last known playback position and when we heard about it. */
private class PositionAnchor {
    var positionMs = Long.MIN_VALUE
    var atNanos = 0L
}

/**
 * A soft 0..1 pulse locked to the beat of the current local track: ~60 ms rise, ~260 ms decay,
 * scaled by the song's energy envelope at that moment. It's 0 (and no frame loop runs) when
 * disabled, paused, under reduced motion, or when the track has no analysed steady beat.
 */
@Composable
fun rememberBeatPulse(trackId: String?, progress: Progress, isPlaying: Boolean, enabled: Boolean): State<Float> {
    val pulse = remember { mutableFloatStateOf(0f) }
    val reduced = ArnavTheme.motion.reduced
    val db = koinInject<ArnavDatabase>()
    val dao = remember(db) { db.audioFeatures() }
    val wanted = enabled && !reduced && trackId != null

    val features by produceState<AudioFeaturesEntity?>(null, trackId, wanted) {
        value = null
        val id = trackId
        if (wanted && id != null) {
            dao.observe(id).collect { row ->
                val next = row?.takeIf { it.ok && it.bpm > 0f }
                val cur = value
                // The query re-emits on every write to the table; only react to real changes.
                if ((next == null) != (cur == null) || (next != null && cur != null && (next.trackId != cur.trackId || next.analyzedAt != cur.analyzedAt))) {
                    value = next
                }
            }
        }
    }

    val anchor = remember { PositionAnchor() }
    val position = progress.positionMs
    SideEffect {
        if (anchor.positionMs != position) {
            anchor.positionMs = position
            anchor.atNanos = System.nanoTime()
        }
    }

    val f = features
    val running = wanted && isPlaying && f != null
    LaunchedEffect(running, f) {
        val feat = f
        if (!running || feat == null) {
            pulse.floatValue = 0f
            return@LaunchedEffect
        }
        // Re-anchor so a long pause doesn't look like a big jump forward.
        anchor.atNanos = System.nanoTime()
        val beatMs = 60_000.0 / feat.bpm
        val cycleMs = if (feat.bpm > HALF_RATE_ABOVE_BPM) beatMs * 2 else beatMs
        val envelope = feat.envelope
        val offset = feat.beatOffsetMs.toDouble()
        while (true) {
            withFrameNanos { }
            val now = System.nanoTime()
            val ahead = min((now - anchor.atNanos) / 1_000_000L, MAX_EXTRAPOLATE_MS).coerceAtLeast(0L)
            val pos = anchor.positionMs.coerceAtLeast(0L) + ahead
            val phase = (((pos - offset) % cycleMs) + cycleMs) % cycleMs
            val shape = pulseShape(phase, cycleMs)
            val env = if (envelope.isEmpty()) 0.6f else {
                val i = (pos / AudioFeatures.ENVELOPE_STEP_MS).toInt().coerceIn(0, envelope.size - 1)
                (envelope[i].toInt() and 0xFF) / 255f
            }
            pulse.floatValue = (shape * (0.35f + 0.65f * env)).coerceIn(0f, 1f)
        }
    }
    return pulse
}

/** Quick smooth attack, exponential decay; the previous beat's tail is kept so there's no step. */
private fun pulseShape(phaseMs: Double, cycleMs: Double): Float {
    return if (phaseMs < ATTACK_MS) {
        val t = phaseMs / ATTACK_MS
        val rise = t * t * (3 - 2 * t)
        val tail = exp(-(phaseMs + cycleMs - ATTACK_MS) / DECAY_MS)
        maxOf(rise, tail).toFloat()
    } else {
        exp(-(phaseMs - ATTACK_MS) / DECAY_MS).toFloat()
    }
}
