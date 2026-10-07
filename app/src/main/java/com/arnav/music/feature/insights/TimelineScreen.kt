package com.arnav.music.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TimelineScreen(vm: InsightsViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val scale by vm.scale.collectAsStateWithLifecycle()
    val buckets by vm.buckets.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    LaunchedEffect(Unit) { vm.setScale(vm.scale.value) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back) }
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    Text("Listening timeline", style = ArnavTheme.type.display, color = c.content)
                    Text("Everything you've played through Arnav Music, on this device.", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
                    Spacer(Modifier.height(Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { TimelineScale.entries.forEach { s -> Pill(s.label, s == scale, { vm.setScale(s) }) } }
                }
            }
        }
        item {
            val max = buckets.maxOfOrNull { it.minutes }?.takeIf { it > 0 } ?: 1L
            val total = buckets.sumOf { it.minutes }
            Column(Modifier.padding(Space.gutter).fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.xl)).padding(Space.l)) {
                Text("$total minutes", style = ArnavTheme.type.headline, color = c.content)
                Text(if (total == 0L) "Nothing played in this range yet." else "Tap a bar to see what was playing.", style = ArnavTheme.type.caption, color = c.contentMuted)
                Spacer(Modifier.height(Space.l))
                Row(Modifier.fillMaxWidth().height(140.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 14) 2.dp else 6.dp)) {
                    buckets.forEach { b ->
                        val isSel = selected?.first == b
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable(enabled = b.minutes > 0) { vm.select(b) }
                                .semantics { contentDescription = "${b.label}: ${b.minutes} minutes" },
                            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight((b.minutes.toFloat() / max * 0.82f).coerceAtLeast(0.02f)).clip(RoundedCornerShape(3.dp))
                                .background(if (isSel) c.accent else if (b.minutes > 0) c.accent.copy(alpha = 0.45f) else c.content.copy(alpha = 0.06f)))
                            if (buckets.size <= 12 || buckets.indexOf(b) % 5 == 0) Text(b.label, style = ArnavTheme.type.caption, color = c.contentSubtle, maxLines = 1)
                        }
                    }
                }
            }
        }
        selected?.let { (b, tracks) ->
            item {
                Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.label, style = ArnavTheme.type.title, color = c.content)
                        Text("${b.minutes} minutes · ${tracks.size} songs", style = ArnavTheme.type.caption, color = c.contentMuted)
                    }
                    PrimaryButton("Take me back", { app.play(tracks, 0) }, enabled = tracks.isNotEmpty(), icon = Icons.Rounded.PlayArrow)
                }
            }
            itemsIndexed(tracks, key = { _, t -> "tl_" + t.id.value }) { i, t ->
                TrackRow(t, { app.play(tracks, i) }, onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) })
            }
        }
    }
}
