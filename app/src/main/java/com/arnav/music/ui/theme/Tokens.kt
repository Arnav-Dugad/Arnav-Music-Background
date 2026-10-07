package com.arnav.music.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arnav.music.R

/** Spacing scale. No ad-hoc dp values in feature code — use these. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val huge = 72.dp
    /** Horizontal page gutter. */
    val gutter = 20.dp
    /** Minimum accessible touch target. */
    val touch = 48.dp
}

object Radius {
    val xs = 6.dp
    val s = 10.dp
    val m = 16.dp
    val l = 22.dp
    val xl = 30.dp
    val artwork = 14.dp
    val heroArtwork = 22.dp
}

object Size {
    val artworkXs = 44.dp
    val artworkS = 56.dp
    val artworkM = 132.dp
    val artworkL = 164.dp
    val miniPlayer = 68.dp
    val navBar = 72.dp
    val iconS = 18.dp
    val icon = 22.dp
    val iconL = 28.dp
    val playButton = 76.dp
}

@Immutable
data class ArnavColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val content: Color,
    val contentMuted: Color,
    val contentSubtle: Color,
    val outline: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val scrim: Color,
    /** Base tint that glass materials mix into. */
    val glassTint: Color,
    val glassHighlight: Color,
    val isDark: Boolean,
    val isOled: Boolean,
)

object Palettes {
    val DefaultAccent = Color(0xFF8C7CFF)

    fun dark(accent: Color) = ArnavColors(
        background = Color(0xFF0B0B0F),
        surface = Color(0xFF14141A),
        surfaceRaised = Color(0xFF1C1C24),
        surfaceSunken = Color(0xFF09090C),
        content = Color(0xFFF4F4F7),
        contentMuted = Color(0xFFB4B4C0),
        contentSubtle = Color(0xFF7C7C8A),
        outline = Color(0x29FFFFFF),
        divider = Color(0x14FFFFFF),
        accent = accent,
        onAccent = if (accent.luminanceCompat() > 0.45f) Color(0xFF0B0B0F) else Color.White,
        accentSoft = accent.copy(alpha = 0.16f),
        danger = Color(0xFFFF6B6B),
        success = Color(0xFF4ADE9B),
        warning = Color(0xFFFFC266),
        scrim = Color(0xB3000000),
        glassTint = Color(0xFF1A1A22),
        glassHighlight = Color(0x33FFFFFF),
        isDark = true,
        isOled = false,
    )

    fun oled(accent: Color) = dark(accent).copy(
        background = Color.Black,
        surface = Color(0xFF0A0A0C),
        surfaceRaised = Color(0xFF121216),
        surfaceSunken = Color.Black,
        glassTint = Color(0xFF0A0A0E),
        glassHighlight = Color(0x1FFFFFFF),
        isOled = true,
    )

    fun light(accent: Color) = ArnavColors(
        background = Color(0xFFF7F7FA),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFFFFFFF),
        surfaceSunken = Color(0xFFEDEDF2),
        content = Color(0xFF111116),
        contentMuted = Color(0xFF4F4F5C),
        contentSubtle = Color(0xFF80808E),
        outline = Color(0x1F000000),
        divider = Color(0x12000000),
        accent = accent,
        onAccent = if (accent.luminanceCompat() > 0.5f) Color(0xFF111116) else Color.White,
        accentSoft = accent.copy(alpha = 0.12f),
        danger = Color(0xFFD93A3A),
        success = Color(0xFF138A55),
        warning = Color(0xFFB86E00),
        scrim = Color(0x66000000),
        glassTint = Color(0xFFFFFFFF),
        glassHighlight = Color(0x99FFFFFF),
        isDark = false,
        isOled = false,
    )
}

internal fun Color.luminanceCompat(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/** Type scale: one family, deliberate weights, tight display tracking, generous body leading. */
@Immutable
data class ArnavType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val numeric: TextStyle,
) {
    companion object {
        private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val Default = ArnavType(
            display = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.03).em, lineHeightStyle = trim),
            headline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em),
            title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em),
            titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
            body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
            label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.01.em),
            caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            overline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em),
            numeric = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
        )
    }
}
