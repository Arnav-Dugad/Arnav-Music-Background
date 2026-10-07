package com.arnav.music.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import com.arnav.music.domain.color.ArtworkPalette
import com.arnav.music.domain.color.ColorMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Apple Music-style animated backdrop: four large, soft colour fields taken from the cover drift on
 * slow, unrelated orbits (21–39 s periods) and melt into each other like a mesh gradient. Android 13+
 * renders it in one AGSL pass with a gentle domain warp and noise dither (no banding); older
 * versions draw the same fields as radial gradients. Colours are clamped to the palette's backdrop
 * lightness band so text keeps its contrast. Static when [animate] is false.
 */
@Composable
fun MovingGradient(
    palette: ArtworkPalette,
    oled: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    /** 0..1 beat pulse; read during drawing only. */
    pulse: () -> Float = { 0f },
    /** Gyroscope tilt (−1..1, −1..1); the fields part very slightly with it. */
    tilt: Pair<Float, Float> = 0f to 0f,
) {
    val light = ColorMath.luminance(palette.onBackdrop) < 0.2
    val tones = remember(palette, light, oled) { meshTones(palette, light, oled) }
    // Seconds of drift; advances only while animating, so pausing never jumps.
    val time = remember { mutableFloatStateOf(11f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        var pending = 0f
        while (true) {
            withFrameNanos { now ->
                pending += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                // The motion is very slow: ~30 updates a second look identical and cost half.
                if (pending >= 1f / 30f) { time.floatValue += pending; pending = 0f }
            }
        }
    }
    val mesh = remember { if (Build.VERSION.SDK_INT >= 33) runCatching { MeshShader() }.getOrNull() else null }
    val blobs = remember { FloatArray(BLOBS * 4) }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val t = time.floatValue
        val beat = pulse().coerceIn(0f, 1f)
        val shift = 24.dp.toPx()
        val span = (w + h) / 2f
        for (i in 0 until BLOBS) {
            val o = i * ORBIT_STRIDE
            val ph = ORBITS[o + 6]
            val ax = 2f * PI.toFloat() * (t / ORBITS[o + 4]) + ph
            val ay = 2f * PI.toFloat() * (t / ORBITS[o + 5]) + ph * 1.3f
            val sign = if (i % 2 == 0) 1f else -1f
            blobs[i * 4] = (ORBITS[o] + ORBITS[o + 2] * sin(ax)) * w + sign * tilt.first * shift
            blobs[i * 4 + 1] = (ORBITS[o + 1] + ORBITS[o + 3] * cos(ay)) * h + sign * tilt.second * shift
            // Each field also breathes a little in size on its own, slower rhythm.
            val breathe = 1f + 0.09f * sin(ax * 0.5f + ay * 0.5f)
            blobs[i * 4 + 2] = ORBITS[o + 7] * span * breathe * (1f + 0.05f * beat)
            blobs[i * 4 + 3] = intensity.coerceIn(0.4f, 1.6f)
        }
        val shaded = mesh != null && Build.VERSION.SDK_INT >= 33 && runCatching {
            val wa = ((t * 0.19f) % (2f * PI.toFloat()))
            val wb = ((t * 0.13f) % (2f * PI.toFloat()))
            mesh.update(w, h, wa, wb, tones, blobs)
        }.isSuccess
        if (shaded && mesh != null && Build.VERSION.SDK_INT >= 33) {
            drawRect(mesh.brush)
        } else {
            drawRect(Color(tones[0]))
            for (i in 0 until BLOBS) {
                val col = Color(tones[i + 1])
                val center = Offset(blobs[i * 4], blobs[i * 4 + 1])
                val r = blobs[i * 4 + 2] * 1.25f
                val k = min(1f, 0.85f * blobs[i * 4 + 3])
                drawCircle(
                    Brush.radialGradient(
                        0f to col.copy(alpha = k),
                        0.4f to col.copy(alpha = 0.55f * k),
                        0.75f to col.copy(alpha = 0.16f * k),
                        1f to Color.Transparent,
                        center = center, radius = r,
                    ),
                    radius = r, center = center,
                )
            }
        }
    }
}

private const val BLOBS = 4
private const val ORBIT_STRIDE = 8

/** Per field: centre x, centre y, orbit x, orbit y (fractions), period x, period y (s), phase, radius. */
private val ORBITS = floatArrayOf(
    0.20f, 0.20f, 0.22f, 0.10f, 31f, 23f, 0.0f, 0.62f,
    0.82f, 0.32f, 0.16f, 0.14f, 37f, 29f, 1.7f, 0.55f,
    0.28f, 0.70f, 0.18f, 0.12f, 27f, 39f, 3.1f, 0.60f,
    0.76f, 0.86f, 0.14f, 0.11f, 34f, 21f, 4.4f, 0.58f,
)

/** [0] = base, [1..4] = the four fields, all clamped to a lightness band that keeps text readable. */
private fun meshTones(p: ArtworkPalette, light: Boolean, oled: Boolean): IntArray {
    fun tone(c: Int): Int {
        val hsl = ColorMath.toHsl(c)
        val s: Float; val l: Float
        when {
            light -> { s = min(hsl[1], 0.5f); l = hsl[2].coerceIn(0.74f, 0.86f) }
            oled -> { s = min(hsl[1], 0.55f); l = hsl[2].coerceIn(0.05f, 0.13f) }
            else -> { s = min(hsl[1], 0.62f); l = hsl[2].coerceIn(0.15f, 0.27f) }
        }
        return ColorMath.fromHsl(hsl[0], s, l)
    }
    val dHsl = ColorMath.toHsl(p.dominant)
    // A neighbour of the dominant hue gives the mesh depth when a cover is mostly one colour.
    val neighbour = ColorMath.fromHsl((dHsl[0] + 28f) % 360f, max(dHsl[1], 0.25f), dHsl[2])
    return intArrayOf(
        p.backdrop,
        tone(p.dominant),
        tone(p.accent),
        tone(p.secondary),
        tone(ColorMath.blend(neighbour, p.accent, 0.35f)),
    )
}

private const val MESH_AGSL = """
uniform float2 size;
uniform float2 warp;
uniform float3 base;
uniform float4 b0;
uniform float4 b1;
uniform float4 b2;
uniform float4 b3;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;

float blob(float2 p, float4 b) {
    float2 d = (p - b.xy) / b.z;
    return b.w * exp(-dot(d, d) * 2.2);
}

half4 main(float2 fragCoord) {
    float m = min(size.x, size.y);
    float2 p = fragCoord + m * 0.06 * float2(sin(fragCoord.y / m * 2.7 + warp.x), cos(fragCoord.x / m * 2.3 + warp.y));
    float w0 = blob(p, b0);
    float w1 = blob(p, b1);
    float w2 = blob(p, b2);
    float w3 = blob(p, b3);
    float3 col = (base * 0.35 + c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3) / (0.35 + w0 + w1 + w2 + w3);
    float n = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);
    col += (n - 0.5) / 255.0;
    return half4(half3(col), 1.0);
}
"""

@RequiresApi(33)
private class MeshShader {
    private val shader = RuntimeShader(MESH_AGSL)
    val brush = ShaderBrush(shader)

    fun update(w: Float, h: Float, warpA: Float, warpB: Float, tones: IntArray, blobs: FloatArray) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("warp", warpA, warpB)
        rgb("base", tones[0])
        for (i in 0 until BLOBS) {
            shader.setFloatUniform(BLOB_NAMES[i], blobs[i * 4], blobs[i * 4 + 1], max(blobs[i * 4 + 2], 1f), blobs[i * 4 + 3])
            rgb(COLOR_NAMES[i], tones[i + 1])
        }
    }

    private fun rgb(name: String, c: Int) {
        shader.setFloatUniform(name, ColorMath.red(c) / 255f, ColorMath.green(c) / 255f, ColorMath.blue(c) / 255f)
    }

    private companion object {
        val BLOB_NAMES = arrayOf("b0", "b1", "b2", "b3")
        val COLOR_NAMES = arrayOf("c0", "c1", "c2", "c3")
    }
}
