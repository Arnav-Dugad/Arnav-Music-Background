package com.arnav.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import com.arnav.music.ui.theme.ArnavTheme

/** Press compression: content dips to [scale] immediately on touch, springs back on release. */
fun Modifier.pressScale(interaction: MutableInteractionSource, scale: Float = 0.96f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val motion = ArnavTheme.motion
    val s by animateFloatAsState(if (pressed && !motion.reduced) scale else 1f, if (pressed) motion.instant() else motion.expressive(), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Skeleton shimmer. A slow, low-contrast sweep — loading should feel calm, not busy. */
fun Modifier.shimmer(): Modifier = composed {
    val colors = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val base = colors.content.copy(alpha = if (colors.isDark) 0.06f else 0.07f)
    val hi = colors.content.copy(alpha = if (colors.isDark) 0.12f else 0.12f)
    if (motion.reduced) return@composed drawWithCache { onDrawBehind { drawRect(base) } }
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    drawWithCache {
        val w = size.width
        onDrawBehind {
            val x = -w + 3 * w * t
            drawRect(Brush.linearGradient(listOf(base, hi, base), start = Offset(x, 0f), end = Offset(x + w, size.height)))
        }
    }
}

@Composable
fun rememberInteraction() = remember { MutableInteractionSource() }

fun Color.scale(alpha: Float) = copy(alpha = this.alpha * alpha)

/**
 * The surface tilts toward the finger while pressed (the touched side sinks a few degrees, like
 * pressing a real card) and springs flat on release. Never consumes the touch; off under reduced motion.
 */
fun Modifier.pressTilt(maxDegrees: Float = 7f): Modifier = composed {
    val motion = ArnavTheme.motion
    if (motion.reduced) return@composed this
    val rx = remember { androidx.compose.animation.core.Animatable(0f) }
    val ry = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val follow = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.7f, stiffness = 700f)
    val settle = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.45f, stiffness = 260f)
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun aim(p: Offset) {
                    val nx = ((p.x / size.width.coerceAtLeast(1)) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    val ny = ((p.y / size.height.coerceAtLeast(1)) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    scope.launch { ry.animateTo(nx * maxDegrees, follow) }
                    scope.launch { rx.animateTo(-ny * maxDegrees, follow) }
                }
                aim(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    aim(change.position)
                }
                scope.launch { rx.animateTo(0f, settle) }
                scope.launch { ry.animateTo(0f, settle) }
            }
        }
        .graphicsLayer {
            rotationX = rx.value
            rotationY = ry.value
            cameraDistance = 14f * density
        }
}
