package com.arnav.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import com.arnav.music.ui.theme.ArnavTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS-like elastic ends for a vertical list: dragging past an edge pulls the content with growing
 * resistance, and a fling that hits an edge carries its momentum into a short spring bounce.
 * Replaces the platform stretch for the list it's applied to. Off under reduced motion.
 */
class BounceState internal constructor(private val scope: CoroutineScope, private val enabled: Boolean) {
    internal val offset = Animatable(0f)
    private val maxPull = 220f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled) return Offset.Zero
            val cur = offset.value
            // While stretched, scrolling back toward rest first relaxes the stretch.
            if (cur != 0f && source == NestedScrollSource.UserInput && sign(available.y) != sign(cur)) {
                val next = if (abs(available.y) >= abs(cur)) 0f else cur + available.y
                val used = next - cur
                scope.launch { offset.snapTo(next) }
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || available.y == 0f) return Offset.Zero
            if (source != NestedScrollSource.UserInput) return Offset(0f, available.y)
            val cur = offset.value
            val resistance = 0.55f * (1f - (abs(cur) / maxPull)).coerceIn(0.05f, 1f)
            val next = (cur + available.y * resistance).coerceIn(-maxPull, maxPull)
            scope.launch { offset.snapTo(next) }
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!enabled || offset.value == 0f) return Velocity.Zero
            // Released while stretched: spring home and keep the fling from scrolling the list.
            offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 320f), initialVelocity = available.y * 0.25f)
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!enabled || available.y == 0f) return Velocity.Zero
            // Hit an edge mid-fling: the leftover momentum becomes a small bounce.
            offset.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 260f), initialVelocity = (available.y * 0.35f).coerceIn(-3500f, 3500f))
            return available
        }
    }
}

@Composable
fun rememberBounce(): BounceState {
    val scope = rememberCoroutineScope()
    val enabled = !ArnavTheme.motion.reduced
    return remember(scope, enabled) { BounceState(scope, enabled) }
}

/** Apply to the scrolling list itself. */
fun Modifier.bounce(state: BounceState): Modifier =
    this.nestedScroll(state.connection).graphicsLayer { translationY = state.offset.value }
