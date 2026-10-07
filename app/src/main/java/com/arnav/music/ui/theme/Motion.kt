package com.arnav.music.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.arnav.music.core.settings.MotionLevel

/**
 * Arnav Motion System. Five physically related tokens; every animation in the app uses one.
 * Reduced/minimal motion collapses travel and springiness without removing feedback.
 */
object Easing {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Immutable
class ArnavMotion(val level: MotionLevel) {
    val reduced: Boolean get() = level != MotionLevel.FULL
    private val minimal get() = level == MotionLevel.MINIMAL

    /** Press feedback, toggles. */
    fun <T> instant(): FiniteAnimationSpec<T> = if (minimal) snap() else tween(90, easing = Easing.Standard)

    /** Small state changes: colors, icon swaps. */
    fun <T> fast(): FiniteAnimationSpec<T> = if (minimal) snap() else tween(if (reduced) 120 else 180, easing = Easing.Standard)

    /** Direct manipulation follow-through: sliders, sheets settling, reorder displacement. */
    fun <T> responsive(): FiniteAnimationSpec<T> = when {
        minimal -> snap()
        reduced -> tween(160, easing = Easing.Emphasized)
        else -> spring(dampingRatio = 0.86f, stiffness = 700f)
    }

    /** Hero moments: favorite, play morph, cards entering. */
    fun <T> expressive(): FiniteAnimationSpec<T> = when {
        minimal -> snap()
        reduced -> tween(200, easing = Easing.Emphasized)
        else -> spring(dampingRatio = 0.72f, stiffness = 380f)
    }

    /** Spatial transitions: player morph, screen-level reveals. */
    fun <T> cinematic(): FiniteAnimationSpec<T> = when {
        minimal -> snap()
        reduced -> tween(240, easing = Easing.Emphasized)
        else -> tween(520, easing = Easing.Emphasized)
    }

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = if (minimal) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
    fun sizeSpring(): FiniteAnimationSpec<IntSize> = if (minimal) snap() else spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize(1, 1))

    /** Distance multiplier for translations (reduced motion moves less). */
    val travel: Float get() = when (level) { MotionLevel.FULL -> 1f; MotionLevel.REDUCED -> 0.35f; MotionLevel.MINIMAL -> 0f }

    companion object {
        @Suppress("unused") fun <T> none(): AnimationSpec<T> = snap()
    }
}
