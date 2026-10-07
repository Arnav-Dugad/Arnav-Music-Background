package com.arnav.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.arnav.music.ui.theme.ArnavTheme
import kotlinx.coroutines.delay

/** One seek step per tap, like YouTube (double tap = first step, each further tap adds one). */
const val SEEK_STEP_MS = 5_000L
private const val SESSION_MS = 750L

/**
 * Double-tap-to-seek session: which side was tapped, how many steps have accumulated and where the
 * last tap landed (for the ripple). While a session is live, every single tap adds another step.
 */
@Stable
class SeekTapState {
    /** −1 = back, +1 = forward, 0 = idle. */
    var side by mutableIntStateOf(0)
        private set
    var steps by mutableIntStateOf(0)
        private set
    /** Bumps on every step so the ripple restarts. */
    var pulse by mutableIntStateOf(0)
        private set
    var tapX by mutableFloatStateOf(0.5f)
        private set
    var tapY by mutableFloatStateOf(0.5f)
        private set
    private var targetMs = 0L

    val active: Boolean get() = steps > 0

    /** Adds one step on [dir]'s side and returns the clamped position to seek to. */
    fun step(dir: Int, fx: Float, fy: Float, positionMs: Long, durationMs: Long): Long {
        if (!active || side != dir) { side = dir; steps = 0; targetMs = positionMs }
        steps += 1
        targetMs = (targetMs + dir * SEEK_STEP_MS).coerceIn(0L, durationMs.coerceAtLeast(0L))
        tapX = fx; tapY = fy
        pulse += 1
        return targetMs
    }

    internal fun end() { steps = 0 }
}

@Composable
fun rememberSeekTapState(): SeekTapState {
    val s = remember { SeekTapState() }
    LaunchedEffect(s.pulse) {
        if (s.active) { delay(SESSION_MS); s.end() }
    }
    return s
}

/**
 * Taps on an on-device cover. Without [seek] this is a plain tap / long press. With it, a double tap
 * on the left or right half seeks by [SEEK_STEP_MS]; while that session is live single taps keep
 * adding steps instantly (no double-tap wait), and the normal tap action returns once it ends.
 */
@Composable
fun Modifier.coverTaps(
    seek: SeekTapState?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSeek: (dir: Int, fx: Float, fy: Float) -> Unit,
    longPressLabel: String? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val seekBy by rememberUpdatedState(onSeek)
    val session = seek?.active == true
    return this
        .pointerInput(seek != null, session) {
            fun fire(o: Offset) {
                val w = size.width.coerceAtLeast(1)
                val h = size.height.coerceAtLeast(1)
                seekBy(if (o.x < w / 2f) -1 else 1, o.x / w, o.y / h)
            }
            when {
                seek == null -> detectTapGestures(onTap = { tap() }, onLongPress = { long() })
                session -> detectTapGestures(onTap = { fire(it) }, onLongPress = { long() })
                else -> detectTapGestures(onTap = { tap() }, onDoubleTap = { fire(it) }, onLongPress = { long() })
            }
        }
        .semantics {
            onClick { tap(); true }
            onLongClick(label = longPressLabel) { long(); true }
        }
}

/**
 * Watches taps on the YouTube player without consuming anything: every touch still reaches the
 * player itself (its own controls, ads and attribution keep working). Draws nothing.
 */
fun Modifier.observeDoubleTaps(enabled: Boolean, seek: SeekTapState, onSeek: (dir: Int, fx: Float, fy: Float) -> Unit): Modifier =
    if (!enabled) this else pointerInput(seek) {
        awaitPointerEventScope {
            var downAt = 0L
            var downPos = Offset.Zero
            var lastTapAt = 0L
            var lastTapPos = Offset.Zero
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull() ?: continue
                if (ev.type == PointerEventType.Press) {
                    downAt = ch.uptimeMillis; downPos = ch.position
                } else if (ev.type == PointerEventType.Release && ev.changes.size == 1) {
                    val tapped = ch.uptimeMillis - downAt < viewConfiguration.longPressTimeoutMillis &&
                        (ch.position - downPos).getDistance() < viewConfiguration.touchSlop
                    if (!tapped) { lastTapAt = 0L; continue }
                    val w = size.width.coerceAtLeast(1)
                    val h = size.height.coerceAtLeast(1)
                    val dir = if (ch.position.x < w / 2f) -1 else 1
                    val second = ch.uptimeMillis - lastTapAt < viewConfiguration.doubleTapTimeoutMillis &&
                        (ch.position - lastTapPos).getDistance() < 100.dp.toPx()
                    if (seek.active || second) {
                        onSeek(dir, ch.position.x / w, ch.position.y / h)
                        lastTapAt = 0L
                    } else {
                        lastTapAt = ch.uptimeMillis; lastTapPos = ch.position
                    }
                }
            }
        }
    }

/**
 * YouTube-style feedback drawn over an on-device cover: a soft arc on the tapped side, a ripple
 * from the finger and the accumulated "−10" / "+10". Draws no input handling of its own.
 */
@Composable
fun SeekRipple(seek: SeekTapState, modifier: Modifier = Modifier) {
    val motion = ArnavTheme.motion
    val shown by animateFloatAsState(if (seek.active) 1f else 0f, tween(if (seek.active) 120 else 380), label = "seekShown")
    // Remember the last session so the label doesn't flip to "+0" while fading out.
    val last = remember { intArrayOf(1, 1) }
    if (seek.active) { last[0] = seek.side; last[1] = seek.steps }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(seek.pulse) {
        if (seek.active && !motion.reduced) { ripple.snapTo(0f); ripple.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    }
    if (shown <= 0.001f) return
    val forward = last[0] > 0
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // The arc: a big circle centred beyond the edge, so only a curved slice shows.
            val cx = if (forward) w * 1.2f else -w * 0.2f
            drawCircle(Color.Black.copy(alpha = 0.26f * shown), radius = w * 0.62f, center = Offset(cx, h / 2f))
            val r = ripple.value
            if (r < 1f) {
                drawCircle(
                    Color.White.copy(alpha = 0.28f * (1f - r) * shown),
                    radius = lerp(w * 0.06f, w * 0.42f, r),
                    center = Offset(seek.tapX * w, seek.tapY * h),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(0.42f).fillMaxHeight().align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .graphicsLayer { alpha = shown },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeekLabel(forward, last[1], Color.White)
        }
    }
}

/**
 * The same feedback for the YouTube player, as a small chip placed beside the video (never on it):
 * the player stays fully visible.
 */
@Composable
fun SeekChip(seek: SeekTapState, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(if (seek.active) 1f else 0f, tween(if (seek.active) 120 else 380), label = "seekChip")
    val last = remember { intArrayOf(1, 1) }
    if (seek.active) { last[0] = seek.side; last[1] = seek.steps }
    if (shown <= 0.001f) return
    val forward = last[0] > 0
    Box(modifier, contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(
            Modifier
                .graphicsLayer { alpha = shown; val s = 0.85f + 0.15f * shown; scaleX = s; scaleY = s }
                .background(Color.Black.copy(alpha = 0.62f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeekLabel(forward, last[1], Color.White)
        }
    }
}

@Composable
private fun SeekLabel(forward: Boolean, steps: Int, color: Color) {
    val secs = steps * (SEEK_STEP_MS / 1000L)
    if (!forward) {
        Icon(Icons.Rounded.FastRewind, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
    }
    Text(if (forward) "+$secs" else "−$secs", style = ArnavTheme.type.label, color = color)
    if (forward) {
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.FastForward, null, tint = color, modifier = Modifier.size(18.dp))
    }
}
