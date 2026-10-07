package com.arnav.music.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.ui.components.Artwork

/**
 * Liquid glass (Android 13+): the artwork behind a pane is blurred, then bent by an AGSL lens —
 * stronger refraction and a hint of chromatic dispersion near the rounded edges, plus a soft
 * specular rim. Falls back to nothing (the regular glass material) where unsupported.
 */
private const val LIQUID_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 q = abs(p - c) - (c - float2(radius));
    float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float band = clamp(1.0 + d / 22.0, 0.0, 1.0);
    float2 n = normalize(p - c + float2(0.001, 0.001));
    float bend = band * band * 12.0;
    float2 uv = p - n * bend;
    half4 g = content.eval(uv);
    half r = content.eval(uv + n * (2.5 * band)).r;
    half b = content.eval(uv - n * (2.5 * band)).b;
    float rim = smoothstep(0.55, 1.0, band) * clamp(dot(n, normalize(float2(-0.45, -1.0))), 0.0, 1.0) * 0.28;
    half s = half(rim);
    return half4(r + s, g.g + s, b + s, g.a);
}
"""

@RequiresApi(33)
private class LiquidLens {
    val shader: RuntimeShader = RuntimeShader(LIQUID_AGSL)
    fun effect(w: Float, h: Float, radiusPx: Float, blurPx: Float): RenderEffect {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", radiusPx)
        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"),
            RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP),
        )
    }
}

fun liquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= 33

/** Draws the blurred, refracted artwork as a pane backdrop. Place it first inside the pane. */
@Composable
fun LiquidGlassBackdrop(artworkUrl: String?, seed: String, cornerRadius: Dp, modifier: Modifier = Modifier, alpha: Float = 0.55f) {
    val budget = ArnavTheme.budget
    if (!liquidGlassSupported() || budget?.realBlur != true || ArnavTheme.glass == GlassLevel.OFF) return
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) runCatching { LiquidLens() }.getOrNull() else null } ?: return
    Box(
        modifier.fillMaxSize().graphicsLayer {
            this.alpha = alpha
            if (Build.VERSION.SDK_INT >= 33) {
                renderEffect = runCatching { lens.effect(size.width, size.height, cornerRadius.toPx(), 28f).asComposeRenderEffect() }.getOrNull()
            }
            clip = true
        },
    ) {
        Artwork(artworkUrl, seed, Modifier.fillMaxSize(), shape = RectangleShape, decodeSize = 160)
    }
}
