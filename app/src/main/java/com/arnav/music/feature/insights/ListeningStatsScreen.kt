package com.arnav.music.feature.insights

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.stats.DiscoveryReport
import com.arnav.music.domain.stats.DiscoveryWindow
import com.arnav.music.domain.stats.ListeningClock
import com.arnav.music.domain.stats.SkipSpots
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.atan2
import kotlin.math.hypot

/** Listening clock by genre, discovery score, songs skipped at the same second and CSV export. */
@Composable
fun ListeningStatsScreen(vm: ListeningStatsViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val chrome = LocalChromePadding.current
    val clock by vm.clock.collectAsStateWithLifecycle()
    val discovery by vm.discovery.collectAsStateWithLifecycle()
    val spots by vm.skipSpots.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(6_000); vm.clearNotice() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) vm.exportCsv(uri)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back) }
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    Text("Listening stats", style = ArnavTheme.type.display, color = c.content)
                    Text("Computed on this device from your Arnav Music history. Nothing is uploaded.", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
                }
            }
        }
        notice?.let { n ->
            item {
                Text(
                    n, style = ArnavTheme.type.bodySmall, color = c.content,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.m)).background(c.accentSoft).padding(Space.m)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        // ---- 15. Listening clock by genre ----
        item {
            StatsSection("Your listening clock", "Last ${ListeningStatsViewModel.CLOCK_DAYS} days") {
                val k = clock
                when {
                    k == null -> Spacer(Modifier.height(Space.xl))
                    k.isEmpty -> Text("Play some music and your day's rhythm shows up here.", style = ArnavTheme.type.body, color = c.contentMuted)
                    else -> GenreClockCard(k)
                }
            }
        }
        // ---- 16. Discovery score ----
        item {
            StatsSection("Discovery score", "Last 7 days") {
                val d = discovery
                when {
                    d == null -> Spacer(Modifier.height(Space.xl))
                    d.thisWeek.isEmpty -> Text("No listening in the last 7 days yet.", style = ArnavTheme.type.body, color = c.contentMuted)
                    else -> DiscoveryCard(d)
                }
            }
        }
        // ---- 17. Songs you skip at the same second ----
        item {
            StatsSection("Songs you skip at the same second", null) {
                if (loaded && spots.isEmpty()) {
                    Text(
                        "Nothing yet. When you skip a song at about the same moment ${SkipSpots.MIN_SKIPS} times, it shows up here.",
                        style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                    )
                }
            }
        }
        items(spots, key = { it.spot.trackId }) { item ->
            SkipSpotRow(
                item,
                onPlay = { item.track?.let { app.play(listOf(it), 0) } },
                onTrim = { vm.trim(item) },
                onDismiss = { vm.dismiss(item) },
            )
        }
        // ---- 18. Export ----
        item {
            StatsSection("Export your stats", null) {
                Column(Modifier.fillMaxWidth().glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.l)) {
                    Text(
                        "Save your full play history as a CSV file (opens in any spreadsheet app): when you played each song, title, artist, album, source, time listened and where you skipped. It's written only to the place you choose.",
                        style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                    )
                    Spacer(Modifier.height(Space.m))
                    SecondaryButton(
                        if (export == CsvExportState.Working) "Exporting…" else "Export CSV",
                        onClick = {
                            val launched = runCatching { picker.launch(vm.suggestedFileName()) }.isSuccess
                            if (!launched) vm.exportUnavailable()
                        },
                        icon = Icons.Rounded.Download,
                        enabled = export != CsvExportState.Working,
                    )
                    val status = when (val e = export) {
                        is CsvExportState.Done -> "Saved ${e.rows} ${if (e.rows == 1) "play" else "plays"}."
                        is CsvExportState.Failed -> e.message
                        else -> null
                    }
                    if (status != null) {
                        Text(
                            status, style = ArnavTheme.type.caption,
                            color = if (export is CsvExportState.Failed) c.danger else c.contentMuted,
                            modifier = Modifier.padding(top = Space.s).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSection(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.xl)) {
        Text(title, style = ArnavTheme.type.title, color = ArnavTheme.colors.content)
        if (subtitle != null) Text(subtitle, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentSubtle)
        Spacer(Modifier.height(Space.m))
        content()
    }
}

/** Categorical genre colours (validated set, light/dark steps); "Other" is always neutral. */
@Composable
private fun genreColors(clock: ListeningClock): List<Color> {
    val c = ArnavTheme.colors
    val palette = if (c.isDark) DARK_SERIES else LIGHT_SERIES
    var next = 0
    return clock.genres.map { g -> if (g == ListeningClock.OTHER) c.contentSubtle.copy(alpha = 0.45f) else palette[next++ % palette.size] }
}

private val LIGHT_SERIES = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4))
private val DARK_SERIES = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181))

@Composable
private fun GenreClockCard(clock: ListeningClock) {
    val c = ArnavTheme.colors
    val colors = genreColors(clock)
    var selected by remember(clock) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.xl)).padding(Space.l)) {
        val peak = clock.peakHour
        Text(
            clock.summary ?: peak?.let { "Most of your listening happens around ${ListeningClock.hourLabel(it)}" }.orEmpty(),
            style = ArnavTheme.type.titleSmall, color = c.content,
        )
        Spacer(Modifier.height(Space.m))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GenreClock(clock, colors, selected, onSelect = { selected = if (selected == it) null else it }, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
        }
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val total = clock.totalMs.coerceAtLeast(1)
            clock.genres.forEachIndexed { i, g ->
                val pct = (clock.genreTotals[i] * 100 / total).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(6.dp))
                    Text("$g $pct%", style = ArnavTheme.type.caption, color = c.contentMuted)
                }
            }
        }
        val sel = selected
        val detail = if (sel != null) {
            val h = clock.hours[sel]
            val parts = h.genreMs.indices.filter { h.genreMs[it] > 0 }.sortedByDescending { h.genreMs[it] }
                .take(3).joinToString(", ") { "${clock.genres[it]} ${Formatters.longDuration(h.genreMs[it])}" }
            "${ListeningClock.hourLabel(sel)}: ${if (h.totalMs > 0) Formatters.longDuration(h.totalMs) + (if (parts.isNotEmpty()) " · $parts" else "") else "no listening"}"
        } else "Tap an hour for details."
        Text(
            detail, style = ArnavTheme.type.caption, color = c.contentSubtle,
            modifier = Modifier.padding(top = Space.s).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            "Genres are hints from public titles and tags (or the artist's other songs), so they're approximate.",
            style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(top = Space.xs),
        )
    }
}

/**
 * 24-hour radial clock (midnight at the top, clockwise). Each hour is a wedge whose length is that hour's
 * listening; the wedge is split into genre-coloured bands, innermost = biggest genre overall.
 */
@Composable
private fun GenreClock(clock: ListeningClock, colors: List<Color>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val reveal = remember(clock) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(clock, motion.reduced) {
        if (motion.reduced) reveal.snapTo(1f) else reveal.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing))
    }
    val description = remember(clock) { clock.describe() }
    val max = clock.maxHourMs.coerceAtLeast(1).toFloat()
    Box(modifier.aspectRatio(1f).clearAndSetSemantics { contentDescription = description }) {
        Canvas(
            Modifier.fillMaxSize().padding(30.dp).pointerInput(clock) {
                detectTapGestures { o ->
                    val dx = o.x - size.width / 2f
                    val dy = o.y - size.height / 2f
                    if (hypot(dx, dy) < size.width * 0.12f) return@detectTapGestures
                    val deg = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0 + 360.0) % 360.0
                    onSelect((deg / 15.0).toInt().coerceIn(0, 23))
                }
            },
        ) {
            val outer = size.minDimension / 2f
            val inner = outer * 0.32f
            val center = Offset(size.width / 2f, size.height / 2f)
            val gapDeg = 2.2f
            val bandGap = 1.dp.toPx()
            drawCircle(c.content.copy(alpha = 0.06f), outer, center, style = Stroke(1.dp.toPx()))
            drawCircle(c.content.copy(alpha = 0.08f), inner - 3.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            val p = reveal.value
            for (h in clock.hours) {
                val start = -90f + h.hour * 15f + gapDeg / 2f
                val sweep = 15f - gapDeg
                // Staggered draw-in around the dial.
                val local = ((p * 1.5f) - h.hour / 24f * 0.5f).coerceIn(0f, 1f)
                val dim = selected != null && selected != h.hour
                // Empty-hour stub so the dial reads as a clock.
                val stub = 3.dp.toPx()
                drawArc(c.content.copy(alpha = 0.07f), start, sweep, false, topLeft = center - Offset(inner + stub / 2, inner + stub / 2), size = Size(2 * inner + stub, 2 * inner + stub), style = Stroke(stub))
                if (h.totalMs <= 0 || local <= 0f) continue
                val length = (outer - inner) * (h.totalMs / max) * local
                var r = inner
                for (i in h.genreMs.indices) {
                    val ms = h.genreMs[i]
                    if (ms <= 0) continue
                    val band = length * ms / h.totalMs
                    val w = (band - bandGap).coerceAtLeast(0f)
                    if (w > 0.5f) {
                        val mid = r + w / 2f
                        drawArc(
                            colors[i].copy(alpha = if (dim) 0.3f else 1f), start, sweep, false,
                            topLeft = center - Offset(mid, mid), size = Size(2 * mid, 2 * mid), style = Stroke(w),
                        )
                    }
                    r += band
                }
            }
        }
        val label = ArnavTheme.type.caption
        Text("12 AM", style = label, color = c.contentSubtle, modifier = Modifier.align(Alignment.TopCenter))
        Text("6 AM", style = label, color = c.contentSubtle, modifier = Modifier.align(Alignment.CenterEnd))
        Text("12 PM", style = label, color = c.contentSubtle, modifier = Modifier.align(Alignment.BottomCenter))
        Text("6 PM", style = label, color = c.contentSubtle, modifier = Modifier.align(Alignment.CenterStart))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val hour = selected ?: clock.peakHour
            Text(if (selected != null) "At" else "Peak", style = ArnavTheme.type.caption, color = c.contentSubtle)
            Text(hour?.let { ListeningClock.hourLabel(it) } ?: "—", style = ArnavTheme.type.titleSmall, color = c.content, textAlign = TextAlign.Center)
            val g = hour?.let { clock.dominantGenre(it) }
            if (g != null) Text(g, style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DiscoveryCard(report: DiscoveryReport) {
    val c = ArnavTheme.colors
    val w = report.thisWeek
    Column(
        Modifier.fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.xl)).padding(Space.xl)
            .clearAndSetSemantics { contentDescription = discoveryDescription(report) },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${w.percentNew}%", style = ArnavTheme.type.display, color = c.content)
            Spacer(Modifier.width(Space.m))
            Text("new to you", style = ArnavTheme.type.body, color = c.contentMuted, modifier = Modifier.padding(bottom = Space.s))
        }
        val delta = report.deltaPoints
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                delta == null || delta == 0 -> Icons.AutoMirrored.Rounded.TrendingFlat
                delta > 0 -> Icons.AutoMirrored.Rounded.TrendingUp
                else -> Icons.AutoMirrored.Rounded.TrendingDown
            }
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.s))
            Text(
                when {
                    delta == null -> "Nothing to compare with last week yet"
                    delta == 0 -> "Same as last week (${report.lastWeek.percentNew}%)"
                    delta > 0 -> "+$delta points vs last week (${report.lastWeek.percentNew}%)"
                    else -> "−${-delta} points vs last week (${report.lastWeek.percentNew}%)"
                },
                style = ArnavTheme.type.caption, color = c.contentMuted,
            )
        }
        Spacer(Modifier.height(Space.l))
        val parts = listOf(
            Triple(w.newArtistMs, c.accent, "New artists"),
            Triple(w.newSongMs, c.accent.copy(alpha = 0.55f), "New songs"),
            Triple(w.familiarMs, c.content.copy(alpha = 0.16f), "Familiar"),
        )
        DiscoveryBar(parts.map { it.first to it.second })
        Spacer(Modifier.height(Space.m))
        DiscoveryLine(parts[0].second, "New artists", count(w.newArtists, "artist"), w.newArtistMs, w)
        DiscoveryLine(parts[1].second, "New songs", count(w.newSongs, "song"), w.newSongMs, w)
        DiscoveryLine(parts[2].second, "Familiar", count(w.familiarSongs, "song"), w.familiarMs, w)
        Text(
            "New = never played before this week. New songs are by artists you already knew.",
            style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(top = Space.s),
        )
    }
}

/** Stacked share bar with a 2 dp gap between segments; animates in unless motion is reduced. */
@Composable
private fun DiscoveryBar(parts: List<Pair<Long, Color>>) {
    val motion = ArnavTheme.motion
    val grow = remember(parts.map { it.first }) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(parts.map { it.first }) { if (motion.reduced) grow.snapTo(1f) else grow.animateTo(1f, motion.expressive()) }
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
        val full = maxWidth * grow.value.coerceIn(0f, 1f)
        Row(Modifier.width(full).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.first > 0 }.forEach { (ms, color) ->
                Box(Modifier.weight(ms.toFloat() / total).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}

@Composable
private fun DiscoveryLine(color: Color, label: String, detail: String, ms: Long, w: DiscoveryWindow) {
    val c = ArnavTheme.colors
    val pct = if (w.totalMs > 0) (ms * 100 / w.totalMs).toInt() else 0
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Space.s))
        Text(label, style = ArnavTheme.type.bodySmall, color = c.content, modifier = Modifier.weight(1f))
        Text("$detail · ${Formatters.longDuration(ms)} · $pct%", style = ArnavTheme.type.numeric, color = c.contentSubtle)
    }
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun discoveryDescription(r: DiscoveryReport): String {
    val w = r.thisWeek
    return buildString {
        append("Discovery score: ${w.percentNew} percent of the last 7 days' listening was new to you. ")
        append("${count(w.newArtists, "new artist")}, ${count(w.newSongs, "new song")}, ${count(w.familiarSongs, "familiar song")}. ")
        r.deltaPoints?.let { d -> append(if (d == 0) "Same as last week." else "${if (d > 0) "Up" else "Down"} ${kotlin.math.abs(d)} points from last week.") }
    }
}

@Composable
private fun SkipSpotRow(item: SkipSpotItem, onPlay: () -> Unit, onTrim: () -> Unit, onDismiss: () -> Unit) {
    val c = ArnavTheme.colors
    val s = item.spot
    val title = item.track?.title ?: "This song"
    val at = SkipSpots.formatPosition(s.positionMs)
    Column(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.xs).fillMaxWidth()
            .glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.s)).clickable(enabled = item.track != null, onClick = onPlay),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(item.track?.artworkUrl, s.trackId, Modifier.size(48.dp), RoundedCornerShape(Radius.s), decodeSize = 140)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text("You usually skip “$title” at $at", style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.count} of ${s.totalSkips} skips between ${SkipSpots.formatPosition(s.fromMs)} and ${SkipSpots.formatPosition(s.toMs)}" +
                            (item.track?.artist?.let { " · $it" } ?: ""),
                        style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ArnavIconButton(Icons.Rounded.Close, "Dismiss", onDismiss, tint = c.contentMuted)
        }
        val duration = s.durationMs
        if (duration != null && duration > 0) {
            Spacer(Modifier.height(Space.s))
            BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
                val frac = (s.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.content.copy(alpha = 0.08f)))
                Box(Modifier.align(Alignment.CenterStart).width(maxWidth * frac).height(4.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.5f)))
                Box(Modifier.align(Alignment.CenterStart).offset(x = (maxWidth * frac - 5.dp).coerceAtLeast(0.dp)).size(10.dp).clip(CircleShape).background(c.accent))
            }
        }
        Spacer(Modifier.height(Space.s))
        when (item.trim) {
            TrimAvailability.AVAILABLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("Trim it here", onTrim, icon = Icons.Rounded.ContentCut)
                Spacer(Modifier.width(Space.m))
                Text("Fades out at $at and moves on", style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.weight(1f))
            }
            else -> Text(trimHint(item.trim), style = ArnavTheme.type.caption, color = c.contentSubtle)
        }
    }
}

private fun trimHint(t: TrimAvailability): String = when (t) {
    TrimAvailability.AVAILABLE -> ""
    TrimAvailability.NEEDS_SMART_TRANSITIONS -> "Turn on Smart transitions in Settings to trim songs."
    TrimAvailability.NOT_ANALYSED -> "Trimming needs this song's on-device audio analysis first."
    TrimAvailability.TOO_EARLY -> "Trimming works from the second half of a song."
    TrimAvailability.NOT_LOCAL -> "Trimming works for songs on this device."
}
