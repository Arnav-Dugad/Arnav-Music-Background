package com.arnav.music.feature.moments

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.arnav.music.domain.model.Moment
import com.arnav.music.domain.model.MomentMotion
import com.arnav.music.ui.theme.ArnavTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural Moment environments, rendered from the moment's aesthetic descriptor — no images,
 * no network, no AI image generation. Motion is slow (≥ 8 s cycles) and never flashes.
 */
@Composable
fun MomentCanvas(moment: Moment, modifier: Modifier = Modifier, animated: Boolean = true, density: Float = 1f) {
    val budget = ArnavTheme.budget
    val live = animated && !ArnavTheme.motion.reduced && (budget?.particles ?: 0) > 0
    val particleCount = ((budget?.particles ?: 0) * density).toInt().coerceIn(0, 48)
    val colors = moment.palette.map { Color(it) }
    val seeds = remember(moment.id, particleCount) {
        val r = Random(moment.id.hashCode())
        List(particleCount.coerceAtLeast(12)) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.3f + r.nextFloat() * 0.7f, r.nextFloat()) }
    }
    val t = if (live) {
        rememberInfiniteTransition(label = "moment").animateFloat(
            0f, 1f, infiniteRepeatable(tween(((22_000) / (0.5f + moment.aesthetic.motion)).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "t",
        ).value
    } else 0.18f

    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.linearGradient(colors.take(2), start = Offset(0f, 0f), end = Offset(w, h)))
        val accent = colors.last()
        val phase = (t * 2 * PI).toFloat()
        // A large, slowly orbiting glow anchors every environment.
        val gc = Offset(w * (0.7f + 0.12f * cos(phase)), h * (0.3f + 0.1f * sin(phase)))
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent), gc, w * 0.75f), w * 0.75f, gc)

        when (moment.motion) {
            MomentMotion.DRIFT -> seeds.take(particleCount).forEach { s ->
                val x = ((s[0] + t * 0.15f * s[2]) % 1f) * w
                val y = (s[1] + 0.03f * sin(phase + s[3] * 6f)) * h
                drawCircle(accent.copy(alpha = 0.18f * s[2]), (2f + 6f * s[2]) * density * 1.2f, Offset(x, y))
            }
            MomentMotion.RAIN -> seeds.take(particleCount).forEach { s ->
                val y = ((s[1] + t * (0.8f + s[2])) % 1f) * h
                val x = s[0] * w
                drawLine(Color.White.copy(alpha = 0.10f + 0.12f * s[2]), Offset(x, y), Offset(x - 4f, y + 26f * s[2] + 10f), strokeWidth = 1.6f, cap = StrokeCap.Round)
            }
            MomentMotion.PULSE -> repeat(3) { i ->
                val p = (t + i / 3f) % 1f
                drawCircle(accent.copy(alpha = 0.22f * (1f - p)), w * (0.15f + 0.6f * p), Offset(w * 0.3f, h * 0.65f), style = Stroke(width = 2f + 4f * (1f - p)))
            }
            MomentMotion.SHIMMER -> seeds.take(particleCount).forEach { s ->
                // Slow twinkle: ~0.12 Hz per particle, well below any flicker threshold.
                val a = 0.08f + 0.22f * (0.5f + 0.5f * sin(phase + s[3] * 12f))
                drawCircle(Color.White.copy(alpha = a), 1.5f + 2.5f * s[2], Offset(s[0] * w, s[1] * h))
            }
            MomentMotion.STILL -> drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f))))
            MomentMotion.SURGE -> repeat(4) { i ->
                val off = ((t + i / 4f) % 1f) * (w + h) - h
                drawLine(accent.copy(alpha = 0.16f), Offset(off, h), Offset(off + h, 0f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            }
        }
        // Grain-free vignette for legibility of overlaid type.
        drawRect(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)))
    }
}
