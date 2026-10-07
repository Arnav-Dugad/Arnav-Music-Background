package com.arnav.music.feature.credits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.metadata.CreditsState
import com.arnav.music.domain.color.SurfaceMode
import com.arnav.music.domain.metadata.CreditEntry
import com.arnav.music.domain.metadata.CreditGroup
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.artwork.rememberArtworkPalette
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.components.shimmer
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Who made a song: performers, writers, producers, engineers, label and identifiers, read from the
 * file's own tags (on-device songs) or the YouTube description. People link to their artist page.
 */
@Composable
fun CreditsScreen(trackId: String, vm: CreditsViewModel = koinViewModel()) {
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    val chrome = LocalChromePadding.current
    val track by vm.track.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(trackId) { vm.load(trackId) }
    val t = track
    val palette = rememberArtworkPalette(t?.artworkUrl, if (c.isOled) SurfaceMode.OLED else if (c.isDark) SurfaceMode.DARK else SurfaceMode.LIGHT)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(Color(palette.backdrop).copy(alpha = 0.85f), c.background))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
            item(key = "top") {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back)
                }
            }
            item(key = "header") { Header(t) }
            when (val s = state) {
                CreditsState.Loading -> items(5) { SkeletonRow() }
                CreditsState.Unavailable -> item(key = "unavailable") {
                    EmptyState(
                        Icons.Rounded.CloudOff, "Credits aren't available right now",
                        "Check your connection, or try again later — YouTube's free daily allowance may be used up.",
                        action = "Try again", onAction = vm::retry,
                    )
                }
                is CreditsState.Ready -> {
                    val groups = s.credits.grouped()
                    if (groups.isEmpty()) item(key = "empty") {
                        EmptyState(
                            Icons.Rounded.Groups, "No credits for this song yet",
                            if (t?.source == SourceType.LOCAL) "This file doesn't name its writers, producers or label. You can still fix its title, artist and album from the song's menu."
                            else "The upload's description doesn't say who wrote or produced this song.",
                        )
                    } else {
                        groups.forEach { (group, entries) ->
                            item(key = "g_${group.name}") {
                                Text(
                                    group.title, style = ArnavTheme.type.title, color = c.content,
                                    modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.xs),
                                )
                            }
                            items(entries, key = { e -> "e_${group.name}_${e.role}_${e.name}" }) { e ->
                                if (e.person) PersonRow(e) { nav.go(Routes.artist(e.name)) } else FactRow(e)
                            }
                        }
                        item(key = "source") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                                if (s.fromDescription) { SourceBadge(youtube = true); Spacer(Modifier.width(Space.s)) }
                                Text(
                                    if (s.fromDescription) "From the video's description on YouTube." else "From the tags in this file.",
                                    style = ArnavTheme.type.caption, color = c.contentSubtle,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(track: Track?) {
    val c = ArnavTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track?.artworkUrl, track?.id?.value ?: "credits", Modifier.size(84.dp), RoundedCornerShape(Radius.m), decodeSize = 240)
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            Text("CREDITS", style = ArnavTheme.type.overline, color = c.contentMuted)
            Spacer(Modifier.height(Space.xxs))
            Text(track?.title?.ifBlank { null } ?: "This song", style = ArnavTheme.type.headline, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(track?.artist?.ifBlank { null }, track?.album?.ifBlank { null }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PersonRow(e: CreditEntry, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open ${e.name}", onClick = onClick)
            .padding(horizontal = Space.gutter, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.name, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(e.role, style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.contentSubtle, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FactRow(e: CreditEntry) {
    val c = ArnavTheme.colors
    val value = remember(e) { if (e.role == "Released") prettyDate(e.name) else e.name }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
        Text(e.role, style = ArnavTheme.type.caption, color = c.contentMuted)
        // Identifiers (ISRC, UPC…) can be selected and copied.
        if (e.group == CreditGroup.IDENTIFIERS) SelectionContainer { Text(value, style = ArnavTheme.type.body, color = c.content) }
        else Text(value, style = ArnavTheme.type.body, color = c.content)
    }
}

@Composable
private fun SkeletonRow() {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
        Box(Modifier.width(160.dp).height(14.dp).clip(RoundedCornerShape(Radius.xs)).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(96.dp).height(10.dp).clip(RoundedCornerShape(Radius.xs)).shimmer())
    }
}

/** "2019-05-03" → "May 3, 2019" (locale style); years and partial dates stay as they are. */
private fun prettyDate(raw: String): String =
    runCatching { LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }.getOrDefault(raw)
