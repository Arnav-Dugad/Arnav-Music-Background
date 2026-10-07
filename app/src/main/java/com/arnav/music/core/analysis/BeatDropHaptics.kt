package com.arnav.music.core.analysis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.playback.Engine
import com.arnav.music.core.playback.PlayerState
import com.arnav.music.core.playback.Progress
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.domain.audio.BeatDrop
import com.arnav.music.domain.audio.DropDetector
import com.arnav.music.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.min

/** Re-read the (extrapolated) playback position at least this often, so nothing drifts. */
private const val DROP_RESYNC_MS = 1_000L
/** Extrapolate at most this far past the last progress update (a stalled player stops the clock). */
private const val DROP_MAX_EXTRAPOLATE_MS = 1_500L
/** A jump of more than this from where the clock should be is a seek (or a track change). */
private const val DROP_SEEK_TOLERANCE_MS = 700L
/** Fire this much early: a vibration motor takes a moment to get going. */
private const val DROP_LEAD_MS = 20L
/** A drop noticed later than this (e.g. after a hiccup) is skipped rather than felt off the beat. */
private const val DROP_LATE_MS = 150L
/** Length of the fallback one-shot pulse on devices without composition primitives. */
private const val DROP_ONE_SHOT_MS = 30L

/** Last known playback position and when we heard about it. */
private class DropAnchor {
    var positionMs = Long.MIN_VALUE
    var atNanos = 0L
}

/**
 * One firm haptic pulse exactly when the beat drops in an analysed song on this phone (drops come
 * from its stored energy envelope via [DropDetector], snapped to its beat; worked out on first play,
 * nothing extra is stored). Only while [AppSettings.beatDropHaptics] and [AppSettings.haptics] are
 * on, the app is in the foreground and the song is playing. Pulses are scheduled against the
 * playback position, re-synced every second; seeks never fire the drops they jump over.
 * Renders nothing.
 */
@Composable
fun BeatDropHaptics(state: PlayerState, progress: Progress, settings: AppSettings) {
    val track = state.current
    val trackId = if (track != null && track.source == SourceType.LOCAL && state.engine == Engine.LOCAL) track.id.value else null
    val settingOn = settings.beatDropHaptics && settings.haptics
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val db = koinInject<ArnavDatabase>()
    val dao = remember(db) { db.audioFeatures() }
    val drops by produceState<List<BeatDrop>?>(null, trackId, settingOn) {
        value = null
        val id = trackId
        if (settingOn && id != null) {
            var stamp = Long.MIN_VALUE
            dao.observe(id).collect { row ->
                val usable = row?.takeIf { it.ok && it.envelope.isNotEmpty() }
                // The query re-emits on every write to the table; only react to a new analysis.
                val nextStamp = usable?.analyzedAt ?: Long.MIN_VALUE
                if (nextStamp != stamp) {
                    stamp = nextStamp
                    value = if (usable == null) null else withContext(Dispatchers.Default) {
                        DropDetector.detect(usable.envelope, AudioFeatures.ENVELOPE_STEP_MS, usable.bpm, usable.beatOffsetMs)
                    }.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    val anchor = remember { DropAnchor() }
    val position = progress.positionMs
    SideEffect {
        if (anchor.positionMs != position) {
            anchor.positionMs = position
            anchor.atNanos = System.nanoTime()
        }
    }

    val context = LocalContext.current
    val view = LocalView.current
    val pulser = remember(context, view) { DropPulser(context.applicationContext, view) }
    val list = drops
    val running = settingOn && foreground && trackId != null && state.isPlaying && !state.isBuffering && list != null
    LaunchedEffect(running, list) {
        if (!running || list == null) return@LaunchedEffect
        // Re-anchor so a long pause doesn't look like a big jump forward.
        anchor.atNanos = System.nanoTime()
        fun positionNow(): Long {
            val ahead = ((System.nanoTime() - anchor.atNanos) / 1_000_000L).coerceIn(0L, DROP_MAX_EXTRAPOLATE_MS)
            return anchor.positionMs.coerceAtLeast(0L) + ahead + DROP_LEAD_MS
        }
        var lastPos = positionNow()
        var lastNanos = System.nanoTime()
        // Drops at or after this position may still fire.
        var armedFrom = lastPos
        while (true) {
            val pos = positionNow()
            val nowNanos = System.nanoTime()
            val expected = lastPos + (nowNanos - lastNanos) / 1_000_000L
            if (abs(pos - expected) > DROP_SEEK_TOLERANCE_MS) armedFrom = pos
            lastPos = pos
            lastNanos = nowNanos
            val next = list.firstOrNull { it.atMs >= armedFrom }
            if (next == null) {
                delay(DROP_RESYNC_MS)
                continue
            }
            val wait = next.atMs - pos
            if (wait <= 0L) {
                if (wait >= -DROP_LATE_MS) pulser.pulse(next.strength)
                armedFrom = next.atMs + 1
                continue
            }
            delay(min(wait, DROP_RESYNC_MS))
        }
    }
}

/**
 * A single short, firm pulse: a THUD (or CLICK) composition primitive where the vibrator has one,
 * else a [DROP_ONE_SHOT_MS] one-shot; without vibrator access, the view's long-press haptic.
 * Never repeats on its own.
 */
private class DropPulser(context: Context, private val view: View) {
    private val vibrator: Vibrator? = runCatching {
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) null
        else if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    }.getOrNull()?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }

    fun pulse(strength: Float) {
        val v = vibrator
        if (v != null) {
            try {
                vibrate(v, (0.7f + 0.3f * strength).coerceIn(0.7f, 1f))
                return
            } catch (e: Exception) {
                // Fall through to the view haptic.
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun vibrate(v: Vibrator, scale: Float) {
        val effect = when {
            Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) ->
                VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale).compose()
            Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale).compose()
            else -> {
                val amplitude = if (v.hasAmplitudeControl()) (255 * scale).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                VibrationEffect.createOneShot(DROP_ONE_SHOT_MS, amplitude)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Media usage: follows the system's "media vibration" setting.
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            v.vibrate(effect)
        }
    }
}
