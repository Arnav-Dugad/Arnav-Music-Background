package com.arnav.music.feature.collection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.intelligence.ArtistHistory
import com.arnav.music.domain.intelligence.MonthMinutes
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.ArtOrigin
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.artOrigin
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Listening history of one artist for the artist page; [topTracks] pairs each song with its play count. */
data class ArtistHistoryUi(
    val loading: Boolean = true,
    val artistKey: String = "",
    val history: ArtistHistory? = null,
    val topTracks: List<Pair<Track, Int>> = emptyList(),
)

/**
 * "Your history with <artist>": plays and listening time, first and last listen, a 12-month
 * sparkline of minutes, the most played songs (tap to play) and the time the user usually plays
 * them. Data stays on this device (play events). Shows one quiet line when never played.
 *
 * Shares the artist page's [CollectionViewModel] (same navigation entry), so it can sit anywhere
 * inside `ArtistScreen`.
 */
@Composable
fun ArtistHistorySection(artistName: String, modifier: Modifier = Modifier) {
    val vm: CollectionViewModel = koinViewModel()
    LaunchedEffect(artistName) { vm.loadArtistHistory(artistName) }
    val ui by vm.artistHistory.collectAsStateWithLifecycle()
    val c = ArnavTheme.colors
    val history = ui.history
    if (ui.loading || history == null) return

    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m)) {
        Text("Your history", style = ArnavTheme.type.title, color = c.content)
        Spacer(Modifier.height(Space.s))
        if (history.isEmpty) {
            Text(
                "You haven't played $artistName in Arnav yet. Your plays will show up here — they never leave this phone.",
                style = ArnavTheme.type.bodySmall, color = c.contentSubtle,
            )
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Stats(history)
            MonthBars(history.months)
            history.pattern?.sentence()?.let { line ->
                Row(
                    Modifier.clip(RoundedCornerShape(Radius.m)).background(c.accentSoft).padding(horizontal = Space.m, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.s))
                    Text(line, style = ArnavTheme.type.bodySmall, color = c.content)
                }
            }
            if (ui.topTracks.isNotEmpty()) TopSongs(ui.topTracks)
        }
    }
}

@Composable
private fun Stats(h: ArtistHistory) {
    val c = ArnavTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val first = remember(h.firstListened) {
        h.firstListened?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.fillMaxWidth()) {
            Stat(h.plays.toString(), if (h.plays == 1) "play" else "plays", Modifier.weight(1f))
            Stat(Formatters.longDuration(h.listenedMs), "listened", Modifier.weight(1f))
            Stat(first ?: "–", "first listen", Modifier.weight(1f))
        }
        h.lastPlayed?.let { last ->
            Text("Last played " + Formatters.relative(last, System.currentTimeMillis()), style = ArnavTheme.type.caption, color = c.contentMuted)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = ArnavTheme.colors
    Column(modifier) {
        Text(value, style = ArnavTheme.type.headline, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1)
    }
}

/** 12 bars, one per month (oldest first), height relative to the busiest month. */
@Composable
private fun MonthBars(months: List<MonthMinutes>) {
    val c = ArnavTheme.colors
    if (months.isEmpty()) return
    val max = months.maxOf { it.minutes }.coerceAtLeast(1)
    val locale = Locale.getDefault()
    val description = remember(months) {
        "Minutes per month: " + months.joinToString(", ") { "${it.month.month.getDisplayName(TextStyle.SHORT, locale)} ${it.minutes}" }
    }
    val accent = c.accent
    val empty = c.content.copy(alpha = 0.08f)
    Column {
        Text("Minutes per month", style = ArnavTheme.type.caption, color = c.contentMuted)
        Spacer(Modifier.height(Space.s))
        Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = description }) {
            val n = months.size
            val gap = 4.dp.toPx()
            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val minH = 3.dp.toPx()
            months.forEachIndexed { i, m ->
                val h = if (m.minutes == 0) minH else (minH + (size.height - minH) * m.minutes / max.toFloat())
                val x = i * (barW + gap)
                val alpha = if (i == n - 1) 1f else 0.55f + 0.45f * m.minutes / max.toFloat()
                drawRoundRect(
                    color = if (m.minutes == 0) empty else accent.copy(alpha = alpha),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 3f, barW / 3f),
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth()) {
            Text(months.first().month.month.getDisplayName(TextStyle.SHORT, locale), style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.weight(1f))
            Text(months.last().month.month.getDisplayName(TextStyle.SHORT, locale), style = ArnavTheme.type.caption, color = c.contentSubtle)
        }
    }
}

@Composable
private fun TopSongs(top: List<Pair<Track, Int>>) {
    val c = ArnavTheme.colors
    val app = LocalAppViewModel.current
    val nav = LocalNavigator.current
    val tracks = remember(top) { top.map { it.first } }
    Column {
        Text("Most played", style = ArnavTheme.type.caption, color = c.contentMuted)
        Spacer(Modifier.height(Space.xs))
        top.forEachIndexed { i, (t, plays) ->
            // The tapped song's cover flies into Now Playing.
            val art = remember(t.id) { ArtOrigin() }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.s))
                    .clickable(role = Role.Button, onClickLabel = "Play ${t.title}") { app.play(tracks, i); art.rect()?.let(nav.flyFrom) }
                    .padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", style = ArnavTheme.type.numeric, color = c.contentSubtle, modifier = Modifier.width(20.dp))
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.xs)).artOrigin(art)) {
                    Artwork(t.artworkUrl, t.id.value, Modifier.size(40.dp), RoundedCornerShape(Radius.xs), decodeSize = 120)
                }
                Spacer(Modifier.width(Space.m))
                Text(t.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                Box(Modifier.clip(CircleShape).background(c.content.copy(alpha = 0.06f)).padding(horizontal = Space.s, vertical = Space.xxs)) {
                    Text("$plays ${if (plays == 1) "play" else "plays"}", style = ArnavTheme.type.numeric, color = c.contentMuted)
                }
            }
        }
    }
}
