package com.arnav.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The Arnav Music mark: a single flowing light-arc that rises and falls like a waveform and,
 * read as a whole, forms a soft "A". [progress] (0..1) draws the stroke for the launch reveal.
 */
@Composable
fun ArnavMark(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Color(0xFFB9AEFF), Color(0xFF8C7CFF), Color(0xFF52D6C3)),
    progress: Float = 1f,
    strokeFraction: Float = 0.085f,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * strokeFraction
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.86f)
            // Left leg rising into the apex.
            cubicTo(w * 0.24f, h * 0.62f, w * 0.36f, h * 0.10f, w * 0.50f, h * 0.12f)
            // Right leg falling away.
            cubicTo(w * 0.64f, h * 0.14f, w * 0.74f, h * 0.62f, w * 0.90f, h * 0.86f)
        }
        // Cross-bar as a small waveform: the "music" in the A.
        val bar = Path().apply {
            moveTo(w * 0.30f, h * 0.60f)
            cubicTo(w * 0.38f, h * 0.50f, w * 0.44f, h * 0.70f, w * 0.50f, h * 0.60f)
            cubicTo(w * 0.56f, h * 0.50f, w * 0.62f, h * 0.70f, w * 0.70f, h * 0.60f)
        }
        val brush = Brush.linearGradient(colors, start = Offset(0f, h), end = Offset(w, 0f))
        val measure = androidx.compose.ui.graphics.PathMeasure()
        fun partial(p: Path, t: Float): Path {
            if (t >= 1f) return p
            measure.setPath(p, false)
            val out = Path()
            measure.getSegment(0f, measure.length * t.coerceIn(0f, 1f), out, true)
            return out
        }
        drawPath(partial(path, progress * 1.25f), brush, style = Stroke(width = stroke, cap = StrokeCap.Round))
        val barT = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
        if (barT > 0f) drawPath(partial(bar, barT), brush, style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round))
    }
}
