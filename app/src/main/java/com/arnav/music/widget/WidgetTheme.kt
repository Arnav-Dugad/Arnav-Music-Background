package com.arnav.music.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import androidx.glance.material3.ColorProviders as material3ColorProviders

/**
 * Widget styling. With `AppSettings.widgetMaterialYou` on, widgets use Material 3 colours: the
 * wallpaper-based dynamic colours on Android 12+ (Glance's default [GlanceTheme] colours) and a
 * scheme derived from the app accent (#8C7CFF) before that. Off keeps the dark glass look.
 */
object Widgets {
    private val _materialYou = MutableStateFlow(true)

    /** Observed by running widget compositions (Glance doesn't re-run provideGlance for a live session). */
    internal val materialYou: StateFlow<Boolean> = _materialYou.asStateFlow()

    /**
     * Reads `AppSettings.widgetMaterialYou` for [androidx.glance.appwidget.GlanceAppWidget.provideGlance].
     * Right after a cold start it waits (at most 1 s) for the first DataStore read so a widget doesn't
     * draw with the default value and then flip.
     */
    internal suspend fun load(): Boolean {
        val repo = runCatching { GlobalContext.get().get<SettingsRepository>() }.getOrNull() ?: return _materialYou.value
        if (!repo.loaded.value) withTimeoutOrNull(1_000L) { repo.loaded.first { it } }
        return repo.settings.value.widgetMaterialYou.also { _materialYou.value = it }
    }

    /**
     * Re-renders every placed widget with the current widget style. Call it after
     * `AppSettings.widgetMaterialYou` changes ([materialYou] = the new value, or null to read it from
     * [SettingsRepository]). Only redraws: it never touches the player.
     */
    suspend fun refreshAll(context: Context, materialYou: Boolean? = null) {
        val app = context.applicationContext
        _materialYou.value = materialYou
            ?: runCatching { GlobalContext.get().get<SettingsRepository>().settings.value.widgetMaterialYou }.getOrNull()
            ?: _materialYou.value
        runCatching { ArnavWidget().updateAll(app) }
        runCatching { QueueWidget().updateAll(app) }
        runCatching { RecapWidget().updateAll(app) }
        runCatching { LyricsWidget().updateAll(app) }
    }
}

/** Colours the widget content draws with; provided by [WidgetTheme]. */
internal class WidgetPalette(
    val materialYou: Boolean,
    val background: ColorProvider,
    val title: ColorProvider,
    val body: ColorProvider,
    val faint: ColorProvider,
    val accent: ColorProvider,
    val row: ColorProvider,
    val rowTitle: ColorProvider,
    val rowBody: ColorProvider,
    val artPlaceholder: ColorProvider,
    /** Tint for the note on the artwork placeholder; null keeps the drawable's own colour. */
    val artPlaceholderIcon: ColorFilter?,
    /** Tint for plain icon buttons; null keeps the drawable's own colour. */
    val icon: ColorFilter?,
    /** Container behind the main (play/pause) button; null draws none. */
    val primaryButton: ColorProvider?,
    val primaryIcon: ColorFilter?,
) {
    /** Content padding: 16 dp for Material 3 widgets, the glass widgets keep their own [glass] value. */
    fun padding(glass: Dp): Dp = if (materialYou) 16.dp else glass

    companion object {
        /** The original dark glass look. */
        val Glass = WidgetPalette(
            materialYou = false,
            background = ColorProvider(Color(0xFF15121F)),
            title = ColorProvider(Color.White),
            body = ColorProvider(Color(0xB3FFFFFF)),
            faint = ColorProvider(Color(0x80FFFFFF)),
            accent = ColorProvider(Color(0xFFB9A6FF)),
            row = ColorProvider(Color(0x14FFFFFF)),
            rowTitle = ColorProvider(Color.White),
            rowBody = ColorProvider(Color(0xB3FFFFFF)),
            artPlaceholder = ColorProvider(Color(0xFF2A1F5C)),
            artPlaceholderIcon = null,
            icon = null,
            primaryButton = null,
            primaryIcon = null,
        )

        fun fromColors(c: ColorProviders) = WidgetPalette(
            materialYou = true,
            background = c.widgetBackground,
            title = c.onSurface,
            body = c.onSurfaceVariant,
            faint = c.onSurfaceVariant,
            accent = c.primary,
            row = c.secondaryContainer,
            rowTitle = c.onSecondaryContainer,
            rowBody = c.onSecondaryContainer,
            artPlaceholder = c.primaryContainer,
            artPlaceholderIcon = ColorFilter.tint(c.onPrimaryContainer),
            icon = ColorFilter.tint(c.onSurfaceVariant),
            primaryButton = c.primaryContainer,
            primaryIcon = ColorFilter.tint(c.onPrimaryContainer),
        )
    }
}

private val LocalWidgetPalette = staticCompositionLocalOf { WidgetPalette.Glass }

/** The palette of the enclosing [WidgetTheme]. */
@Composable
internal fun widgetPalette(): WidgetPalette = LocalWidgetPalette.current

/**
 * Wraps widget content. [materialYou] on: [GlanceTheme] with the system's dynamic colours on API 31+
 * (Glance's default theme colours) or [AccentColors] below; off: the dark glass palette.
 */
@Composable
internal fun WidgetTheme(materialYou: Boolean, content: @Composable () -> Unit) {
    if (materialYou) {
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else AccentColors
        GlanceTheme(colors = colors) {
            CompositionLocalProvider(LocalWidgetPalette provides WidgetPalette.fromColors(GlanceTheme.colors)) { content() }
        }
    } else {
        CompositionLocalProvider(LocalWidgetPalette provides WidgetPalette.Glass) { content() }
    }
}

/**
 * Root of every widget: marks the app-widget background (so launchers can clip/animate it), uses the
 * system widget corner radius on Android 12+ (rounded corners aren't supported before that) and
 * applies the content padding.
 */
internal fun GlanceModifier.widgetRoot(p: WidgetPalette, contentPadding: Dp): GlanceModifier {
    val base = fillMaxSize().appWidgetBackground().background(p.background)
    val rounded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        base
    }
    return rounded.padding(contentPadding)
}

/** Material 3 tonal-spot scheme generated from the app accent #8C7CFF (used before Android 12). */
private val AccentColors: ColorProviders by lazy {
    material3ColorProviders(
        light = lightColorScheme(
            primary = Color(0xFF5E5791),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE5DEFF),
            onPrimaryContainer = Color(0xFF463F77),
            inversePrimary = Color(0xFFC7BFFF),
            secondary = Color(0xFF5F5C71),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE5DFF9),
            onSecondaryContainer = Color(0xFF474459),
            tertiary = Color(0xFF7B5265),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD8E7),
            onTertiaryContainer = Color(0xFF613B4D),
            background = Color(0xFFFCF8FF),
            onBackground = Color(0xFF1C1B20),
            surface = Color(0xFFFCF8FF),
            onSurface = Color(0xFF1C1B20),
            surfaceVariant = Color(0xFFE5E0EC),
            onSurfaceVariant = Color(0xFF47464F),
            inverseSurface = Color(0xFF313036),
            inverseOnSurface = Color(0xFFF4EFF7),
            outline = Color(0xFF78767F),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFC7BFFF),
            onPrimary = Color(0xFF2F285F),
            primaryContainer = Color(0xFF463F77),
            onPrimaryContainer = Color(0xFFE5DEFF),
            inversePrimary = Color(0xFF5E5791),
            secondary = Color(0xFFC8C3DC),
            onSecondary = Color(0xFF312E41),
            secondaryContainer = Color(0xFF474459),
            onSecondaryContainer = Color(0xFFE5DFF9),
            tertiary = Color(0xFFECB8CE),
            onTertiary = Color(0xFF482537),
            tertiaryContainer = Color(0xFF613B4D),
            onTertiaryContainer = Color(0xFFFFD8E7),
            background = Color(0xFF141318),
            onBackground = Color(0xFFE5E1E9),
            surface = Color(0xFF141318),
            onSurface = Color(0xFFE5E1E9),
            surfaceVariant = Color(0xFF47464F),
            onSurfaceVariant = Color(0xFFC9C5D0),
            inverseSurface = Color(0xFFE5E1E9),
            inverseOnSurface = Color(0xFF313036),
            outline = Color(0xFF928F99),
        ),
    )
}
