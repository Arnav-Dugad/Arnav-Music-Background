package com.arnav.music.ui.artwork

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.arnav.music.domain.color.ArtworkPalette

/**
 * Crossfades every colour of the artwork palette when the song changes, so the accent, backdrop,
 * text colours and tints across the whole app glide together instead of snapping.
 */
@Composable
fun animatePalette(target: ArtworkPalette, durationMs: Int = 900): ArtworkPalette {
    val spec = tween<Color>(durationMs, easing = FastOutSlowInEasing)
    val dominant by animateColorAsState(Color(target.dominant), spec, label = "pDominant")
    val secondary by animateColorAsState(Color(target.secondary), spec, label = "pSecondary")
    val accent by animateColorAsState(Color(target.accent), spec, label = "pAccent")
    val backdrop by animateColorAsState(Color(target.backdrop), spec, label = "pBackdrop")
    val backdropSecondary by animateColorAsState(Color(target.backdropSecondary), spec, label = "pBackdrop2")
    val onBackdrop by animateColorAsState(Color(target.onBackdrop), spec, label = "pOn")
    val onBackdropMuted by animateColorAsState(Color(target.onBackdropMuted), spec, label = "pOnMuted")
    return target.copy(
        dominant = dominant.toArgb(),
        secondary = secondary.toArgb(),
        accent = accent.toArgb(),
        backdrop = backdrop.toArgb(),
        backdropSecondary = backdropSecondary.toArgb(),
        onBackdrop = onBackdrop.toArgb(),
        onBackdropMuted = onBackdropMuted.toArgb(),
    )
}
