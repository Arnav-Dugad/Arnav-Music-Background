package com.arnav.music.domain.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Deterministic colour correction for the artwork engine. Pure math on ARGB ints so it can be
 * unit-tested off-device. Guarantees readable text regardless of the album cover.
 */
object ColorMath {
    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(c: Int): Double = 0.2126 * channel(red(c)) + 0.7152 * channel(green(c)) + 0.0722 * channel(blue(c))

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** HSL with h in 0..360, s/l in 0..1. */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Int = 255): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return argb(ch(r), ch(g), ch(b), alpha)
    }

    /** 0 = cool (blue), 1 = warm (orange/red); greys are neutral 0.5. */
    fun temperature(c: Int): Float {
        val (h, s, _) = toHsl(c).let { Triple(it[0], it[1], it[2]) }
        if (s < 0.12f) return 0.5f
        val warmness = (kotlin.math.cos(Math.toRadians((h - 30f).toDouble())) + 1) / 2
        return (0.5f + (warmness.toFloat() - 0.5f) * s).coerceIn(0f, 1f)
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x + (y - x) * k).toInt().coerceIn(0, 255)
        return argb(mix(red(a), red(b)), mix(green(a), green(b)), mix(blue(a), blue(b)))
    }

    /** Lightens/darkens [fg] along lightness until it reaches [minRatio] against [bg]. */
    fun ensureContrast(fg: Int, bg: Int, minRatio: Double = 4.5): Int {
        if (contrast(fg, bg) >= minRatio) return fg
        val hsl = toHsl(fg)
        val lighten = luminance(bg) < 0.4
        var l = hsl[2]
        repeat(40) {
            l = if (lighten) min(1f, l + 0.025f) else max(0f, l - 0.025f)
            val c = fromHsl(hsl[0], hsl[1], l)
            if (contrast(c, bg) >= minRatio) return c
        }
        return if (lighten) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
}

enum class SurfaceMode { LIGHT, DARK, OLED }

/** Final, contrast-safe palette handed to the UI. */
data class ArtworkPalette(
    val dominant: Int,
    val secondary: Int,
    val accent: Int,
    /** Backdrop base colour, already clamped to keep foreground text readable. */
    val backdrop: Int,
    val backdropSecondary: Int,
    val onBackdrop: Int,
    val onBackdropMuted: Int,
    val luminance: Float,
    val temperature: Float,
) {
    companion object {
        /** Neutral palette used before artwork resolves. */
        fun neutral(mode: SurfaceMode): ArtworkPalette = ArtworkCorrector.correct(
            0xFF5B5F97.toInt(), 0xFF2B2D42.toInt(), 0xFF8D99AE.toInt(), mode,
        )
    }
}

object ArtworkCorrector {
    fun correct(dominant: Int, secondary: Int, vibrant: Int?, mode: SurfaceMode): ArtworkPalette {
        val dHsl = ColorMath.toHsl(dominant)
        val sHsl = ColorMath.toHsl(secondary)
        val backdrop = when (mode) {
            // Rich but dark: lightness clamped to 10–20%, saturation tamed for "expensive" calm.
            SurfaceMode.DARK -> ColorMath.fromHsl(dHsl[0], min(dHsl[1], 0.55f), dHsl[2].coerceIn(0.10f, 0.20f))
            // OLED: near-black; artwork tint survives only as a whisper.
            SurfaceMode.OLED -> ColorMath.fromHsl(dHsl[0], min(dHsl[1], 0.5f), min(dHsl[2], 0.07f))
            // Light: pastel wash.
            SurfaceMode.LIGHT -> ColorMath.fromHsl(dHsl[0], min(dHsl[1], 0.45f), dHsl[2].coerceIn(0.86f, 0.93f))
        }
        val backdrop2 = when (mode) {
            SurfaceMode.DARK -> ColorMath.fromHsl(sHsl[0], min(sHsl[1], 0.5f), sHsl[2].coerceIn(0.06f, 0.14f))
            SurfaceMode.OLED -> 0xFF000000.toInt()
            SurfaceMode.LIGHT -> ColorMath.fromHsl(sHsl[0], min(sHsl[1], 0.4f), sHsl[2].coerceIn(0.92f, 0.97f))
        }
        val onBackdrop = if (mode == SurfaceMode.LIGHT) 0xFF0E0F12.toInt() else 0xFFF6F6F8.toInt()
        val muted = ColorMath.ensureContrast(ColorMath.blend(onBackdrop, backdrop, 0.38f), backdrop, 4.5)
        val accentSeed = vibrant ?: if (dHsl[1] > 0.25f) dominant else secondary
        val aHsl = ColorMath.toHsl(accentSeed)
        // Vivid accents only: low-saturation covers get a gentle saturation lift for controls.
        val accentRaw = ColorMath.fromHsl(aHsl[0], aHsl[1].coerceIn(0.35f, 0.85f), aHsl[2].coerceIn(0.45f, 0.7f))
        val accent = ColorMath.ensureContrast(accentRaw, backdrop, 3.0)
        return ArtworkPalette(
            dominant = dominant,
            secondary = secondary,
            accent = accent,
            backdrop = backdrop,
            backdropSecondary = backdrop2,
            onBackdrop = onBackdrop,
            onBackdropMuted = muted,
            luminance = ColorMath.luminance(dominant).toFloat(),
            temperature = ColorMath.temperature(dominant),
        )
    }
}
