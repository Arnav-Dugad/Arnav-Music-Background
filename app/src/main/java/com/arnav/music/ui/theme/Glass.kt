package com.arnav.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arnav.music.core.settings.GlassLevel

/**
 * Glass as a material with distinct optical layers. Each layer controls opacity, tint, border
 * luminosity and elevation. When glass is off (or transparency is reduced) every layer falls
 * back to an opaque, equally-premium surface — readability first.
 */
@Immutable
enum class GlassMaterial(
    val fill: Float,
    val tint: Float,
    val border: Float,
    val highlight: Float,
    val elevation: Dp,
) {
    Thin(fill = 0.46f, tint = 0.10f, border = 0.55f, highlight = 0.45f, elevation = 0.dp),
    Regular(fill = 0.62f, tint = 0.16f, border = 0.7f, highlight = 0.6f, elevation = 2.dp),
    Thick(fill = 0.78f, tint = 0.22f, border = 0.8f, highlight = 0.7f, elevation = 6.dp),
    Elevated(fill = 0.86f, tint = 0.18f, border = 1f, highlight = 0.9f, elevation = 14.dp),
}

@Composable
fun Modifier.glass(
    material: GlassMaterial,
    shape: Shape,
    tint: Color = ArnavTheme.colors.accent,
    level: GlassLevel = ArnavTheme.glass,
): Modifier {
    val c = ArnavTheme.colors
    return when (level) {
        GlassLevel.OFF -> this
            .then(if (material.elevation > 0.dp) Modifier.shadow(material.elevation, shape, clip = false, ambientColor = Color.Black.copy(0.2f), spotColor = Color.Black.copy(0.25f)) else Modifier)
            .clip(shape)
            .background(if (material == GlassMaterial.Thin) c.surface else c.surfaceRaised)
            .border(1.dp, c.divider, shape)

        GlassLevel.SUBTLE, GlassLevel.FULL -> {
            val full = level == GlassLevel.FULL
            val fill = if (full) material.fill else (material.fill + 0.18f).coerceAtMost(0.95f)
            val base = tint.copy(alpha = material.tint * (if (full) 1f else 0.6f)).compositeOver(c.glassTint.copy(alpha = fill))
            val edge = Brush.verticalGradient(
                listOf(c.glassHighlight.copy(alpha = c.glassHighlight.alpha * material.border), c.glassHighlight.copy(alpha = 0.02f)),
            )
            this
                .then(if (material.elevation > 0.dp) Modifier.shadow(material.elevation, shape, clip = false, ambientColor = Color.Black.copy(0.25f), spotColor = Color.Black.copy(0.3f)) else Modifier)
                .clip(shape)
                .background(base)
                .then(
                    if (full) Modifier.drawWithContent {
                        // Soft specular sheen across the top third: light catching the pane.
                        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f * material.highlight), 0.35f to Color.Transparent))
                        drawContent()
                    } else Modifier,
                )
                .border(0.8.dp, edge, shape)
        }
    }
}
