package com.arnav.music.feature.duplicates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DuplicatesScreen(vm: DuplicatesViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val chrome = LocalChromePadding.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val outcomes by vm.outcomes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    /** Group key + version waiting for "Replace" to be confirmed. */
    var confirming by remember { mutableStateOf<Pair<String, TrackId>?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back) }
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    Text("Duplicate songs", style = ArnavTheme.type.display, color = c.content)
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "The same song saved more than once, like a YouTube upload and a file on this phone. Keep one version and Arnav puts it in place of the others in your playlists and likes.",
                        style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                    )
                    Spacer(Modifier.height(Space.l))
                }
            }
        }
        when (val s = ui) {
            DuplicatesUi.Loading -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(Space.m))
                    Text("Looking through your library…", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
                }
            }
            DuplicatesUi.Failed -> item {
                EmptyState(
                    Icons.Rounded.ErrorOutline, "Couldn't check your library", "Something went wrong while reading your songs. Your playlists weren't changed.",
                    action = "Try again", onAction = { vm.load() },
                )
            }
            is DuplicatesUi.Ready -> {
                if (s.groups.isEmpty()) item {
                    EmptyState(
                        Icons.Rounded.CheckCircle, "No duplicates found",
                        "Every song in your playlists, your likes and on this phone appears just once.",
                    )
                } else item {
                    val n = s.groups.size
                    Text(
                        if (n == 1) "1 song is saved more than once" else "$n songs are saved more than once",
                        style = ArnavTheme.type.label, color = c.contentMuted,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                    )
                }
                items(s.groups, key = { it.key }) { entry ->
                    DuplicateCard(
                        entry = entry,
                        outcome = outcomes[entry.key],
                        busy = busy == entry.key,
                        confirming = confirming?.takeIf { it.first == entry.key }?.second,
                        onAskKeep = { id -> confirming = entry.key to id },
                        onCancel = { confirming = null },
                        onConfirm = { id ->
                            entry.versions.firstOrNull { it.track.id == id }?.let { vm.keep(entry, it.track) }
                            confirming = null
                        },
                        onIgnore = { confirming = null; vm.ignore(entry) },
                        onUnignore = { vm.unignore(entry) },
                    )
                }
                if (s.ignoredCount > 0) item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.VisibilityOff, null, tint = c.contentSubtle, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.s))
                        Text(
                            if (s.ignoredCount == 1) "1 group hidden" else "${s.ignoredCount} groups hidden",
                            style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.weight(1f),
                        )
                        TextAction("Show again", c.accent) { vm.showIgnored() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateCard(
    entry: DuplicateEntry,
    outcome: GroupOutcome?,
    busy: Boolean,
    confirming: TrackId?,
    onAskKeep: (TrackId) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (TrackId) -> Unit,
    onIgnore: () -> Unit,
    onUnignore: () -> Unit,
) {
    val c = ArnavTheme.colors
    val first = entry.versions.first().track
    Column(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth()
            .glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.l),
    ) {
        Text(first.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${first.artist} · ${entry.versions.size} versions", style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (outcome) {
            is GroupOutcome.Kept -> {
                Spacer(Modifier.height(Space.m))
                Notice(Icons.Rounded.CheckCircle, c.success, keptText(outcome))
            }
            GroupOutcome.Ignored -> {
                Spacer(Modifier.height(Space.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Notice(Icons.Rounded.VisibilityOff, c.contentMuted, "Ignored. It won't show here again unless another version turns up.")
                    }
                    TextAction("Undo", c.accent, onUnignore)
                }
            }
            null, GroupOutcome.Failed -> {
                if (outcome == GroupOutcome.Failed) {
                    Spacer(Modifier.height(Space.m))
                    Notice(Icons.Rounded.ErrorOutline, c.danger, "Couldn't update your playlists. Nothing was lost; try again.")
                }
                Spacer(Modifier.height(Space.s))
                val mostPlayed = entry.mostPlayed
                entry.versions.forEach { v ->
                    VersionRow(v, mostPlayed = v.track.id == mostPlayed)
                    if (confirming == v.track.id) {
                        val others = entry.versions.size - 1
                        Column(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(
                                "Use this version instead of the other ${if (others == 1) "one" else "$others"} in your playlists and likes? Your play history stays as it is.",
                                style = ArnavTheme.type.bodySmall, color = c.content,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                PrimaryButton("Replace", { onConfirm(v.track.id) }, Modifier.weight(1f), loading = busy)
                                SecondaryButton("Cancel", onCancel, Modifier.weight(1f), enabled = !busy)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(start = 60.dp, bottom = Space.xs)) {
                            TextAction("Keep this version", if (busy) c.contentSubtle else c.accent) { if (!busy) onAskKeep(v.track.id) }
                        }
                    }
                }
                if (entry.versions.any { it.track.source == SourceType.LOCAL }) {
                    Text(
                        "Files on this phone are never deleted, and they aren't synced to your other devices.",
                        style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(top = Space.xs),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.End) {
                    TextAction("Ignore", c.contentMuted) { if (!busy) onIgnore() }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(v: DuplicateVersion, mostPlayed: Boolean) {
    val c = ArnavTheme.colors
    val app = LocalAppViewModel.current
    val t = v.track
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m))
            .clickable(role = Role.Button, onClickLabel = "Play this version") { app.play(listOf(t), 0) }
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.artworkUrl, t.id.value, Modifier.size(48.dp), RoundedCornerShape(Radius.s), decodeSize = 144)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = ArnavTheme.type.bodySmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(t.source == SourceType.YOUTUBE)
                Spacer(Modifier.width(Space.s))
                Text(
                    listOfNotNull(variantLabel(t), t.durationMs?.let { Formatters.duration(it) }).joinToString(" · "),
                    style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1,
                )
            }
            Text(usageText(v), style = ArnavTheme.type.caption, color = c.contentSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (mostPlayed) {
            Spacer(Modifier.width(Space.s))
            Text(
                "Most played", style = ArnavTheme.type.caption, color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s))
        Text(text, style = ArnavTheme.type.bodySmall, color = ArnavTheme.colors.content)
    }
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, style = ArnavTheme.type.label, color = color,
        modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.s, vertical = Space.xs),
    )
}

private fun variantLabel(t: Track): String? = when {
    t.source != SourceType.YOUTUBE -> null
    t.variant == MediaVariant.SONG -> "Audio"
    t.variant == MediaVariant.VIDEO -> "Music video"
    else -> null
}

private fun usageText(v: DuplicateVersion): String {
    val u = v.usage
    val parts = ArrayList<String>()
    parts += when (u.playlists) {
        0 -> "In no playlists"
        1 -> "In 1 playlist"
        else -> "In ${u.playlists} playlists"
    }
    parts += when (u.plays) {
        0 -> "not played yet"
        1 -> "1 play"
        else -> "${u.plays} plays"
    }
    if (u.liked) parts += "liked"
    return parts.joinToString(" · ")
}

private fun keptText(k: GroupOutcome.Kept): String {
    val where = when {
        k.track.source == SourceType.LOCAL -> "the version on this phone"
        k.track.variant == MediaVariant.SONG -> "the YouTube audio version"
        k.track.variant == MediaVariant.VIDEO -> "the YouTube music video"
        else -> "the YouTube version"
    }
    val changes = buildList {
        if (k.playlists > 0) add(if (k.playlists == 1) "1 playlist" else "${k.playlists} playlists")
        if (k.likeMoved) add("your likes")
    }
    return if (changes.isEmpty()) "Keeping $where. The other versions weren't in any playlist or your likes, so nothing else changed."
    else "Keeping $where. Updated ${changes.joinToString(" and ")}."
}
