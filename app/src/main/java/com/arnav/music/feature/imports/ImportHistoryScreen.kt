package com.arnav.music.feature.imports

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.importer.ImportMatcher
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ImportHistoryScreen(vm: ImportHistoryViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val chrome = LocalChromePadding.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back) }
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    Text("Import history", style = ArnavTheme.type.display, color = c.content)
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Playlists you brought in from YouTube, Spotify or CSV files. Undo hides the playlists an import added, and Redo brings them back with their songs. Playlists you made yourself are never touched.",
                        style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                    )
                    Spacer(Modifier.height(Space.l))
                }
            }
        }
        val list = rows
        when {
            list == null -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.5.dp)
                }
            }
            list.isEmpty() -> item {
                EmptyState(
                    Icons.Rounded.History, "No imports yet",
                    "When you bring playlists in from YouTube, Spotify or a CSV file, they're listed here so you can undo an import later.",
                )
            }
            else -> items(list, key = { it.id }) { row ->
                ImportCard(row, notes[row.id], row.id in busy, onUndo = { vm.undo(row.id) }, onRedo = { vm.redo(row.id) })
            }
        }
    }
}

@Composable
private fun ImportCard(row: ImportRow, note: String?, busy: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    val c = ArnavTheme.colors
    val e = row.entry
    val format = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    val zone = remember { ZoneId.systemDefault() }
    val date = remember(e.createdAt) { Instant.ofEpochMilli(e.createdAt).atZone(zone).format(format) }
    Column(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth()
            .glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).animateContentSize().padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(Radius.s)).background(if (e.undone) c.content.copy(alpha = 0.06f) else c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(sourceIcon(e.source), null, tint = if (e.undone) c.contentSubtle else c.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).alpha(if (e.undone) 0.7f else 1f)) {
                Text(e.label, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${sourceName(e.source)} · $date", style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1)
                Text(countsText(row), style = ArnavTheme.type.caption, color = c.contentMuted)
                playlistsText(row)?.let { Text(it, style = ArnavTheme.type.caption, color = c.contentSubtle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (e.undone) {
                Spacer(Modifier.width(Space.s))
                Text(
                    "Undone", style = ArnavTheme.type.caption, color = c.contentMuted,
                    modifier = Modifier.clip(CircleShape).background(c.content.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note ?: if (e.undone) "Its playlists are hidden from your library." else "",
                style = ArnavTheme.type.caption, color = if (note != null) c.content else c.contentSubtle,
                modifier = Modifier.weight(1f).padding(end = Space.s),
            )
            if (e.undone) SecondaryButton("Redo", onRedo, icon = Icons.AutoMirrored.Rounded.Redo, enabled = !busy)
            else SecondaryButton("Undo", onUndo, icon = Icons.AutoMirrored.Rounded.Undo, enabled = !busy)
        }
    }
}

private fun sourceIcon(source: String) = when (source) {
    ImportMatcher.SOURCE_YOUTUBE -> Icons.Rounded.OndemandVideo
    ImportMatcher.SOURCE_CSV -> Icons.Rounded.TableChart
    else -> Icons.Rounded.LibraryMusic
}

private fun sourceName(source: String) = when (source) {
    ImportMatcher.SOURCE_YOUTUBE -> "YouTube"
    ImportMatcher.SOURCE_CSV -> "CSV file"
    ImportMatcher.SOURCE_SPOTIFY -> "Spotify"
    else -> "Import"
}

private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun countsText(row: ImportRow): String {
    val e = row.entry
    return if (row.fromFile) {
        "${row.matched} of ${songs(e.songCount)} matched"
    } else {
        val unavailable = e.songCount - e.matchedCount
        if (unavailable > 0) "${songs(e.matchedCount)} · $unavailable unavailable on YouTube" else songs(e.matchedCount)
    }
}

private fun playlistsText(row: ImportRow): String? {
    val names = row.playlists.map { it.name }
    if (names.isEmpty()) return null
    val shown = names.take(3).joinToString(", ")
    val more = names.size - 3
    val label = if (names.size == 1) "Playlist" else "Playlists"
    return "$label: $shown" + if (more > 0) " and $more more" else ""
}
