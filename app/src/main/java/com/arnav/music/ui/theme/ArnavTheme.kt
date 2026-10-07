package com.arnav.music.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.arnav.music.core.perf.EffectsBudget
import com.arnav.music.core.settings.AccentMode
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.core.settings.ThemeMode
import com.arnav.music.domain.color.ArtworkPalette
import com.arnav.music.domain.color.SurfaceMode

private val LocalColors = staticCompositionLocalOf { Palettes.dark(Palettes.DefaultAccent) }
private val LocalType = staticCompositionLocalOf { ArnavType.Default }
private val LocalMotion = staticCompositionLocalOf { ArnavMotion(com.arnav.music.core.settings.MotionLevel.FULL) }
private val LocalGlass = staticCompositionLocalOf { GlassLevel.SUBTLE }
private val LocalHaptics = staticCompositionLocalOf { ArnavHaptics(null, false) }
private val LocalBudget = staticCompositionLocalOf<EffectsBudget?> { null }
val LocalArtworkPalette = staticCompositionLocalOf<ArtworkPalette?> { null }

object ArnavTheme {
    val colors: ArnavColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: ArnavType @Composable @ReadOnlyComposable get() = LocalType.current
    val motion: ArnavMotion @Composable @ReadOnlyComposable get() = LocalMotion.current
    val glass: GlassLevel @Composable @ReadOnlyComposable get() = LocalGlass.current
    val haptics: ArnavHaptics @Composable @ReadOnlyComposable get() = LocalHaptics.current
    val budget: EffectsBudget? @Composable @ReadOnlyComposable get() = LocalBudget.current
}

@Composable
fun surfaceMode(settings: AppSettings): SurfaceMode {
    val systemDark = isSystemInDarkTheme()
    return when (settings.themeMode) {
        ThemeMode.LIGHT -> SurfaceMode.LIGHT
        ThemeMode.DARK -> SurfaceMode.DARK
        ThemeMode.OLED -> SurfaceMode.OLED
        ThemeMode.SYSTEM -> if (systemDark) SurfaceMode.DARK else SurfaceMode.LIGHT
    }
}

@Composable
fun ArnavMusicTheme(
    settings: AppSettings,
    budget: EffectsBudget?,
    artwork: ArtworkPalette?,
    content: @Composable () -> Unit,
) {
    val mode = surfaceMode(settings)
    val context = LocalContext.current
    val materialYou = remember(mode, settings.accentMode) {
        if (settings.accentMode == AccentMode.MATERIAL_YOU && Build.VERSION.SDK_INT >= 31) {
            if (mode == SurfaceMode.LIGHT) dynamicLightColorScheme(context).primary else dynamicDarkColorScheme(context).primary
        } else null
    }
    val targetAccent = when (settings.accentMode) {
        AccentMode.ARTWORK -> artwork?.accent?.let { Color(it) } ?: Palettes.DefaultAccent
        AccentMode.MATERIAL_YOU -> materialYou ?: Palettes.DefaultAccent
        AccentMode.PRESET -> Color(settings.presetAccent)
    }
    // Theme-wide accent glides between artworks rather than snapping.
    val accent by animateColorAsState(targetAccent, tween(if (budget?.reducedMotion == true) 0 else 700), label = "accent")
    val base = when (mode) {
        SurfaceMode.LIGHT -> Palettes.light(accent)
        SurfaceMode.DARK -> Palettes.dark(accent)
        SurfaceMode.OLED -> Palettes.oled(accent)
    }
    val colors = if (settings.highContrast) base.copy(
        contentMuted = base.content.copy(alpha = 0.9f),
        contentSubtle = base.content.copy(alpha = 0.75f),
        outline = base.content.copy(alpha = 0.5f),
        divider = base.content.copy(alpha = 0.25f),
    ) else base

    val view = LocalView.current
    val haptics = remember(view, settings.haptics) { ArnavHaptics(view, settings.haptics) }
    val motion = remember(budget?.motion ?: settings.motion) { ArnavMotion(budget?.motion ?: settings.motion) }
    val glass = budget?.glass ?: settings.glass

    val scheme = if (colors.isDark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.background, onBackground = colors.content,
        surface = colors.surface, onSurface = colors.content, surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.contentMuted,
        surfaceContainer = colors.surfaceRaised, surfaceContainerHigh = colors.surfaceRaised, surfaceContainerLow = colors.surface,
        outline = colors.outline, outlineVariant = colors.divider, error = colors.danger, secondary = colors.accent,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.background, onBackground = colors.content,
        surface = colors.surface, onSurface = colors.content, surfaceVariant = colors.surfaceSunken, onSurfaceVariant = colors.contentMuted,
        surfaceContainer = colors.surfaceRaised, surfaceContainerHigh = colors.surfaceRaised, surfaceContainerLow = colors.surface,
        outline = colors.outline, outlineVariant = colors.divider, error = colors.danger, secondary = colors.accent,
    )
    val t = ArnavType.Default
    val typography = Typography(
        displayLarge = t.display, displayMedium = t.display, displaySmall = t.headline,
        headlineLarge = t.headline, headlineMedium = t.headline, headlineSmall = t.title,
        titleLarge = t.title, titleMedium = t.titleSmall, titleSmall = t.label,
        bodyLarge = t.body, bodyMedium = t.bodySmall, bodySmall = t.caption,
        labelLarge = t.label, labelMedium = t.caption, labelSmall = t.overline,
    )
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides t,
        LocalMotion provides motion,
        LocalGlass provides glass,
        LocalHaptics provides haptics,
        LocalBudget provides budget,
        LocalArtworkPalette provides artwork,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/**
 * Re-tints the accent for one part of the UI (e.g. a playlist page takes its accent from its cover).
 * The change animates so moving between pages never snaps colours.
 */
@Composable
fun AccentScope(accent: Color?, content: @Composable () -> Unit) {
    val base = LocalColors.current
    if (accent == null) { content(); return }
    val motion = LocalMotion.current
    val animated by androidx.compose.animation.animateColorAsState(accent, androidx.compose.animation.core.tween(if (motion.reduced) 0 else 600), label = "accentScope")
    val onAccent = if (animated.luminance() > 0.55f) Color(0xFF0B0B0F) else Color.White
    CompositionLocalProvider(LocalColors provides base.copy(accent = animated, onAccent = onAccent, accentSoft = animated.copy(alpha = if (base.isDark) 0.22f else 0.16f))) {
        content()
    }
}
