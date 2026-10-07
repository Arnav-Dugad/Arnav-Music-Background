package com.arnav.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.arnav.music.ui.theme.Easing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Circular theme reveal: the old UI is captured as an image, the new theme renders underneath,
 * and a circle grows from the finger that made the change until the old image is gone.
 */
class ThemeReveal internal constructor(private val layer: GraphicsLayer, private val scope: CoroutineScope) {
    internal var snapshot by mutableStateOf<ImageBitmap?>(null)
    internal val progress = Animatable(0f)
    internal var origin by mutableStateOf(Offset.Unspecified)
    internal var lastTouch: Offset = Offset.Unspecified
    var enabled: Boolean = true

    /** Runs [change] (a theme/glass switch) behind a circular reveal. */
    fun run(change: () -> Unit) {
        if (!enabled || snapshot != null) { change(); return }
        scope.launch {
            val bitmap = runCatching { layer.toImageBitmap() }.getOrNull()
            if (bitmap == null) { change(); return@launch }
            origin = lastTouch
            progress.snapTo(0f)
            snapshot = bitmap
            change()
            delay(90) // let the new theme compose and draw underneath
            progress.animateTo(1f, tween(620, easing = Easing.EmphasizedDecelerate))
            snapshot = null
        }
    }
}

val LocalThemeReveal = staticCompositionLocalOf<ThemeReveal?> { null }

@Composable
fun ThemeRevealHost(reduceMotion: Boolean, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val reveal = remember(layer) { ThemeReveal(layer, scope) }
    reveal.enabled = !reduceMotion
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(reveal) {
                // Observe (never consume) touches so the reveal starts under the finger.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    reveal.lastTouch = down.position
                }
            }
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
    ) {
        CompositionLocalProvider(LocalThemeReveal provides reveal) { content() }
        val bmp = reveal.snapshot
        if (bmp != null) {
            Canvas(Modifier.fillMaxSize()) {
                val o = if (reveal.origin.isSpecified) reveal.origin else center
                val maxR = maxOf(
                    hypot(o.x, o.y), hypot(size.width - o.x, o.y),
                    hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y),
                )
                val r = maxR * reveal.progress.value
                val hole = Path().apply { addOval(Rect(o, r)) }
                clipPath(hole, ClipOp.Difference) { drawImage(bmp) }
            }
        }
    }
}
