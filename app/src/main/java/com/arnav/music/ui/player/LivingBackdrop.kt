package com.arnav.music.ui.player

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arnav.music.core.settings.ArtworkMotion
import com.arnav.music.domain.color.ArtworkPalette
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.theme.ArnavTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Atmospheric backdrop driven by the artwork palette. "Living Artwork" adds slow gradient
 * drift, very soft light movement and optional gyroscope parallax. No flashing; periods ≥ 18 s.
 */
@Composable
fun LivingBackdrop(
    palette: ArtworkPalette,
    artworkUrl: String?,
    seed: String,
    artworkMotion: ArtworkMotion,
    gyro: Boolean,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    /** 0..1 beat pulse for analyzed on-device songs; read during drawing only (no recomposition). */
    pulse: () -> Float = { 0f },
    /** 0..1: the cover itself, heavily blurred across the whole screen (lyrics mode). */
    artworkBlur: Float = 0f,
    /** On-device covers: the palette becomes a slowly drifting mesh gradient (see [MovingGradient]). */
    movingGradient: Boolean = false,
) {
    val budget = ArnavTheme.budget
    val colors = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val spec = tween<Color>(if (motion.reduced) 0 else 900)
    val base by animateColorAsState(Color(palette.backdrop), spec, label = "bd")
    val second by animateColorAsState(Color(palette.backdropSecondary), spec, label = "bd2")
    val accent by animateColorAsState(Color(palette.accent), spec, label = "acc")
    val alive = budget?.livingArtwork == true && artworkMotion != ArtworkMotion.OFF && !motion.reduced
    val speed = if (artworkMotion == ArtworkMotion.DYNAMIC) 1f else 0.55f

    // The mesh gradient has its own (draw-only) clock; the classic drift runs only without it.
    val t = if (alive && !movingGradient) {
        val inf = rememberInfiniteTransition(label = "living")
        inf.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween((26_000 / speed).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "t").value
    } else 0f

    val tilt = rememberTilt(enabled = alive && gyro)
    // The mesh keeps drifting unless motion is reduced or the device is saving power / cooling down.
    val meshAlive = !motion.reduced && budget?.powerSave != true && budget?.thermalThrottled != true

    Box(modifier.fillMaxSize()) {
        if (movingGradient) {
            MovingGradient(palette, oled = colors.isOled, animate = meshAlive, modifier = Modifier.fillMaxSize(), intensity = intensity, pulse = pulse, tilt = tilt)
        } else Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(base, second)))
            val w = size.width; val h = size.height
            val px = tilt.first * 24.dp.toPx(); val py = tilt.second * 24.dp.toPx()
            // The beat breathes the light: a few percent of size and glow, never a flash.
            val beat = pulse().coerceIn(0f, 1f)
            val a = accent.copy(alpha = ((if (colors.isOled) 0.10f else 0.22f) * intensity * (1f + 0.45f * beat)).coerceAtMost(1f))
            val ra = w * 0.9f * (1f + 0.07f * beat)
            drawCircle(
                Brush.radialGradient(listOf(a, Color.Transparent), center = Offset(w * (0.25f + 0.08f * cos(t)) + px, h * (0.22f + 0.05f * sin(t * 1.3f)) + py), radius = ra),
                radius = ra, center = Offset(w * (0.25f + 0.08f * cos(t)) + px, h * (0.22f + 0.05f * sin(t * 1.3f)) + py),
            )
            val b = Color(palette.dominant).copy(alpha = (if (colors.isOled) 0.06f else 0.16f) * intensity)
            drawCircle(
                Brush.radialGradient(listOf(b, Color.Transparent), center = Offset(w * (0.8f + 0.06f * sin(t)) - px, h * (0.65f + 0.06f * cos(t * 0.8f)) - py), radius = w),
                radius = w, center = Offset(w * (0.8f + 0.06f * sin(t)) - px, h * (0.65f + 0.06f * cos(t * 0.8f)) - py),
            )
        }
        // Real blur of the cover where the platform supports it cheaply (API 31+ RenderEffect).
        if (!movingGradient && budget?.realBlur == true && Build.VERSION.SDK_INT >= 31 && !colors.isOled) {
            Artwork(
                artworkUrl, seed,
                Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f * intensity; scaleX = 1.3f; scaleY = 1.3f }.blur(80.dp),
                shape = androidx.compose.ui.graphics.RectangleShape, decodeSize = 128,
            )
        }
        // Lyrics mode: the cover fills the screen, blurred (real blur on Android 12+, a tiny decoded
        // bitmap stretched smooth elsewhere), under a palette-tinted veil that keeps lines readable.
        if (artworkBlur > 0.01f) {
            val blurMod = if (Build.VERSION.SDK_INT >= 31) Modifier.blur(56.dp) else Modifier
            Artwork(
                artworkUrl, seed,
                Modifier.fillMaxSize().graphicsLayer { alpha = artworkBlur; scaleX = 1.35f; scaleY = 1.35f }.then(blurMod),
                shape = androidx.compose.ui.graphics.RectangleShape, decodeSize = 40,
            )
            Canvas(Modifier.fillMaxSize()) { drawRect(base.copy(alpha = 0.55f * artworkBlur)) }
        }
        // Bottom scrim guarantees control legibility over any artwork.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to base.copy(alpha = 0.85f)))
        }
    }
}

/** Very subtle device tilt (−1..1) from the rotation vector sensor, low-pass filtered. */
@Composable
private fun rememberTilt(enabled: Boolean): Pair<Float, Float> {
    val x = remember { mutableFloatStateOf(0f) }
    val y = remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    DisposableEffect(enabled) {
        if (!enabled) { x.floatValue = 0f; y.floatValue = 0f; return@DisposableEffect onDispose { } }
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                x.floatValue = x.floatValue * 0.9f + (e.values[1].coerceIn(-0.3f, 0.3f) / 0.3f) * 0.1f
                y.floatValue = y.floatValue * 0.9f + (e.values[0].coerceIn(-0.3f, 0.3f) / 0.3f) * 0.1f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return x.floatValue to y.floatValue
}
