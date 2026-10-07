package com.arnav.music.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arnav.music.core.audio.SingMode
import com.arnav.music.ui.theme.ArnavTheme
import kotlin.math.roundToInt

/**
 * "Sing" (vocal reduction) for songs on this device. Tap toggles off ↔ 85% (or the last level
 * picked); long-press opens a small slider. Place it in Now Playing for on-device songs only —
 * YouTube audio can't be processed. With [enabled] false it is shown dimmed and does nothing.
 */
@Composable
fun SingButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = ArnavTheme.colors.content,
    accent: Color = ArnavTheme.colors.accent,
    size: Dp = 40.dp,
) {
    val level by SingMode.level.collectAsState()
    val active = enabled && level > 0f
    val haptics = ArnavTheme.haptics
    var sliderOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(
        if (active) accent.copy(alpha = 0.24f) else Color.Transparent,
        tween(220),
        label = "singBg",
    )
    val iconTint = when {
        !enabled -> tint.copy(alpha = tint.alpha * 0.38f)
        active -> accent
        else -> tint
    }
    val percent = (level * 100).roundToInt()

    Box(modifier) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false, color = tint),
                    enabled = enabled,
                    role = Role.Switch,
                    onClickLabel = if (active) "Turn off Sing" else "Turn on Sing",
                    onLongClickLabel = "Adjust vocal level",
                    // combinedClickable already gives the long-press haptic.
                    onLongClick = { sliderOpen = true },
                    onClick = {
                        haptics.select()
                        SingMode.toggle()
                    },
                )
                .semantics {
                    stateDescription = when {
                        !enabled -> "Only for songs on this device"
                        active -> "On, vocals reduced $percent%"
                        else -> "Off"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (active) Icons.Rounded.Mic else Icons.Rounded.MicNone,
                contentDescription = "Sing",
                tint = iconTint,
                modifier = Modifier.size(size * 0.55f),
            )
        }
        DropdownMenu(expanded = sliderOpen && enabled, onDismissRequest = { sliderOpen = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sing · vocals", style = ArnavTheme.type.label, color = ArnavTheme.colors.content, modifier = Modifier.weight(1f))
                    Text(
                        if (level > 0f) "−$percent%" else "Off",
                        style = ArnavTheme.type.caption,
                        color = ArnavTheme.colors.contentMuted,
                    )
                }
                Slider(
                    value = level,
                    onValueChange = { SingMode.set(it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = ArnavTheme.colors.content.copy(alpha = 0.18f),
                    ),
                    modifier = Modifier.semantics { stateDescription = "Vocals reduced $percent%" },
                )
                Text(
                    "Lowers the lead vocal so you can sing along. Bass and drums stay.",
                    style = ArnavTheme.type.caption,
                    color = ArnavTheme.colors.contentMuted,
                )
            }
        }
    }
}
