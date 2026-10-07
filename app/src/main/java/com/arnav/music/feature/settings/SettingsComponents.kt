package com.arnav.music.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass

@Composable
fun SettingsGroup(title: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    val c = ArnavTheme.colors
    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
        if (title != null) Text(title.uppercase(), style = ArnavTheme.type.overline, color = c.contentSubtle, modifier = Modifier.padding(start = Space.xs, bottom = Space.s, top = Space.m))
        Column(Modifier.fillMaxWidth().glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l))) { content() }
        if (footer != null) Text(footer, style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = Space.s))
    }
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = ArnavTheme.colors.accent, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = ArnavTheme.type.titleSmall, color = c.content)
            if (subtitle != null) Text(subtitle, style = ArnavTheme.type.caption, color = c.contentMuted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.contentSubtle)
    }
}

/** Custom switch with a springy thumb; whole row is the touch target. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, motion.responsive(), label = "thumb")
    val track by animateColorAsState(if (checked) c.accent else c.content.copy(alpha = 0.16f), motion.fast(), label = "track")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(enabled = enabled, role = Role.Switch) { haptics.select(); onChange(!checked) }
            .semantics { toggleableState = ToggleableState(checked); stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ArnavTheme.type.titleSmall, color = if (enabled) c.content else c.contentSubtle)
            if (subtitle != null) Text(subtitle, style = ArnavTheme.type.caption, color = c.contentMuted)
        }
        Spacer(Modifier.width(Space.m))
        Box(Modifier.size(48.dp, 28.dp).clip(CircleShape).background(track).padding(4.dp)) {
            Box(Modifier.offset(x = x).size(20.dp).clip(CircleShape).background(if (checked) c.onAccent else Color.White))
        }
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, subtitle: String? = null) {
    val c = ArnavTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Space.m)) {
        Text(title, style = ArnavTheme.type.titleSmall, color = c.content, modifier = Modifier.padding(horizontal = Space.l))
        if (subtitle != null) Text(subtitle, style = ArnavTheme.type.caption, color = c.contentMuted, modifier = Modifier.padding(horizontal = Space.l))
        Spacer(Modifier.height(Space.s))
        LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(options) { o -> Pill(label(o), o == selected, { onSelect(o) }) }
        }
    }
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, valueLabel: String, onChange: (Float) -> Unit) {
    val c = ArnavTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m)) {
        Row {
            Text(title, style = ArnavTheme.type.titleSmall, color = c.content, modifier = Modifier.weight(1f))
            Text(valueLabel, style = ArnavTheme.type.numeric, color = c.contentMuted)
        }
        Slider(value, onChange, valueRange = range, steps = steps, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.content.copy(alpha = 0.12f)))
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    val c = ArnavTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ArnavTheme.type.bodySmall, color = c.contentMuted, modifier = Modifier.weight(1f))
        Text(value, style = ArnavTheme.type.titleSmall, color = c.content)
    }
}

@Composable
fun ActionRowS(title: String, subtitle: String? = null, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = ArnavTheme.type.titleSmall, color = if (!enabled) c.contentSubtle else if (destructive) c.danger else c.accent)
        if (subtitle != null) Text(subtitle, style = ArnavTheme.type.caption, color = c.contentMuted)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(start = Space.l).height(1.dp).background(ArnavTheme.colors.divider))
