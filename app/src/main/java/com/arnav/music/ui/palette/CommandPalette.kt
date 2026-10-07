package com.arnav.music.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.core.settings.ThemeMode
import com.arnav.music.domain.model.Moments
import com.arnav.music.domain.search.QueryNormalizer
import com.arnav.music.ui.CollectionKind
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass

private data class Command(val title: String, val hint: String, val icon: ImageVector, val keywords: String = "", val run: () -> Unit)

/**
 * Spotlight-speed command palette: everything reachable by typing. Arrow keys + Enter on
 * hardware keyboards; free text falls through to "Search for …" and "Ask Arnav AI …".
 */
@Composable
fun CommandPalette(onDismiss: () -> Unit) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val vm = LocalAppViewModel.current
    val reveal = com.arnav.music.ui.LocalThemeReveal.current
    fun animated(change: () -> Unit) { reveal?.run(change) ?: change() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val base = remember {
        buildList {
            add(Command("Open Arnav AI", "Ask for a mood, a moment, a session", Icons.Rounded.AutoAwesome, "ai assistant lumen") { nav.topLevel(Routes.AI.substringBefore('?')) })
            add(Command("Liked songs", "Your favourites", Icons.Rounded.Favorite, "favorites hearts") { nav.go(Routes.collection(CollectionKind.LIKED)) })
            add(Command("On-device music", "Local library, plays in background", Icons.Rounded.PhoneAndroid, "local downloads files") { nav.go(Routes.collection(CollectionKind.LOCAL)) })
            add(Command("Taste DNA", "Listening stats & recaps", Icons.Rounded.Insights, "stats dna recap") { nav.go(Routes.INSIGHTS) })
            add(Command("Listening timeline", "Take me back to a day", Icons.Rounded.Timeline, "history calendar") { nav.go(Routes.TIMELINE) })
            add(Command("Listening stats", "Genre clock, discovery, skips, CSV", Icons.Rounded.BarChart, "stats export csv skip") { nav.go(Routes.LISTENING_STATS) })
            add(Command("Taste constellation", "Your musical universe", Icons.Rounded.Hub, "graph artists") { nav.go(Routes.CONSTELLATION) })
            add(Command("Enable Glass", "Translucent, layered UI", Icons.Rounded.BlurOn, "glass on") { animated { vm.setGlass(GlassLevel.FULL) } })
            add(Command("Disable Glass", "Pure, opaque surfaces", Icons.Rounded.BlurOff, "glass off pure") { animated { vm.setGlass(GlassLevel.OFF) } })
            add(Command("Dark theme", "", Icons.Rounded.DarkMode, "theme night") { animated { vm.setTheme(ThemeMode.DARK) } })
            add(Command("OLED black theme", "", Icons.Rounded.DarkMode, "theme amoled black") { animated { vm.setTheme(ThemeMode.OLED) } })
            add(Command("Light theme", "", Icons.Rounded.LightMode, "theme day") { animated { vm.setTheme(ThemeMode.LIGHT) } })
            add(Command("Shuffle liked songs", "", Icons.Rounded.Shuffle, "random play") { nav.go(Routes.collection(CollectionKind.LIKED, "shuffle")) })
            add(Command("Profile", "", Icons.Rounded.Person, "account") { nav.go(Routes.PROFILE) })
            add(Command("Settings", "", Icons.Rounded.Settings, "preferences") { nav.go(Routes.settings()) })
            add(Command("Appearance settings", "Theme, accent, glass, motion", Icons.Rounded.Tune, "theme accent") { nav.go(Routes.settings("appearance")) })
            add(Command("Usage & quotas", "YouTube, AI and sync usage today", Icons.Rounded.Insights, "quota api debug diagnostics") { nav.go(Routes.settings("usage")) })
            Moments.all.forEach { m -> add(Command("Start ${m.title}", m.subtitle, Icons.Rounded.AutoAwesome, "moment ${m.id}") { nav.go(Routes.moment(m.id)) }) }
        }
    }
    val filtered = remember(query) {
        if (query.isBlank()) base.take(9) else {
            val ranked = base.map { it to maxOf(QueryNormalizer.matchScore(query, it.title), QueryNormalizer.matchScore(query, it.keywords) * 0.9f) }
                .filter { it.second > 0.3f }.sortedByDescending { it.second }.map { it.first }
            ranked + listOf(
                Command("Search for “$query”", "Tracks, artists, playlists", Icons.Rounded.Search) { nav.go(Routes.search(query)) },
                Command("Ask Arnav AI: “$query”", "Build a session from this", Icons.Rounded.AutoAwesome) { nav.go(Routes.ai(query)) },
            )
        }
    }
    LaunchedEffect(filtered.size) { selected = selected.coerceIn(0, (filtered.size - 1).coerceAtLeast(0)) }
    fun runAt(i: Int) { filtered.getOrNull(i)?.let { onDismiss(); it.run() } }

    Box(
        Modifier.fillMaxSize().background(c.scrim).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss)
            .statusBarsPadding().imePadding().padding(Space.l),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(top = Space.xxxl)
                .glass(GlassMaterial.Elevated, RoundedCornerShape(Radius.xl))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(filtered.lastIndex); true }
                        Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = c.contentMuted)
                Spacer(Modifier.width(Space.m))
                BasicTextField(
                    value = query, onValueChange = { query = it; selected = 0 }, singleLine = true,
                    textStyle = ArnavTheme.type.title.copy(color = c.content), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { runAt(selected) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Type a command, song or feeling…", style = ArnavTheme.type.title, color = c.contentSubtle)
                        inner()
                    },
                )
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.l)) { Box(Modifier.fillMaxWidth().size(1.dp).background(c.divider)) }
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(vertical = Space.s)) {
                itemsIndexed(filtered) { i, cmd ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.s).clip(RoundedCornerShape(Radius.m))
                            .background(if (i == selected) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { runAt(i) }.padding(horizontal = Space.m, vertical = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(cmd.icon, null, tint = if (i == selected) c.accent else c.contentMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(cmd.title, style = ArnavTheme.type.titleSmall, color = c.content)
                            if (cmd.hint.isNotEmpty()) Text(cmd.hint, style = ArnavTheme.type.caption, color = c.contentSubtle)
                        }
                        if (i == selected) Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, null, tint = c.contentSubtle, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
