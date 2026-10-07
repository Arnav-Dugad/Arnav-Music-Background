package com.arnav.music.feature.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.domain.intelligence.Milestone
import com.arnav.music.domain.intelligence.Milestones
import com.arnav.music.domain.intelligence.RecapPeriod
import com.arnav.music.domain.intelligence.StreakInfo
import com.arnav.music.domain.intelligence.TasteDna
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun InsightsScreen(vm: InsightsViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val dna by vm.dna.collectAsStateWithLifecycle()
    val recap by vm.recap.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val names by vm.artistNames.collectAsStateWithLifecycle()
    val topTracks by vm.topTracks.collectAsStateWithLifecycle()
    val machine by vm.timeMachine.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val milestones by vm.milestones.collectAsStateWithLifecycle()
    val heatmap by vm.heatmap.collectAsStateWithLifecycle()
    val db = koinInject<ArnavDatabase>()
    val analyzedFlow = remember(db) { db.audioFeatures().analyzedCount() }
    val analyzedCount by analyzedFlow.collectAsStateWithLifecycle(0)
    val chrome = LocalChromePadding.current
    LaunchedEffect(Unit) { vm.loadDna(); vm.loadMilestones() }
    fun name(k: String) = names[k] ?: k

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back) }
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    Text("Taste DNA", style = ArnavTheme.type.display, color = c.content)
                    Text("Your Arnav Music listening activity — computed on this device. Not official YouTube Music statistics.", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
                }
            }
        }
        val d = dna
        if (d == null) return@LazyColumn
        if (d.confidence == TasteDna.Confidence.NONE) {
            item { EmptyState(Icons.Rounded.Insights, "Your DNA is still forming", "Listen to a few songs through Arnav Music and your patterns will appear here.") }
            return@LazyColumn
        }
        item {
            Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LinkCard("Constellation", Icons.Rounded.Hub, Modifier.weight(1f)) { nav.go(Routes.CONSTELLATION) }
                LinkCard("Timeline", Icons.Rounded.Timeline, Modifier.weight(1f)) { nav.go(Routes.TIMELINE) }
            }
        }
        item {
            LinkCard("Listening stats", Icons.Rounded.BarChart, Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.l).fillMaxWidth()) {
                nav.go(Routes.LISTENING_STATS)
            }
        }
        // ---- Recap ----
        item {
            Column(Modifier.padding(horizontal = Space.gutter).fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.xl)).padding(Space.xl)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    RecapPeriod.entries.forEach { p -> Pill(p.label, p == period, { vm.setPeriod(p) }) }
                }
                Spacer(Modifier.height(Space.xl))
                val r = recap
                if (r == null || r.isEmpty) {
                    Text("No listening ${period.label.lowercase()} yet.", style = ArnavTheme.type.body, color = c.contentMuted)
                } else {
                    Row {
                        Stat("${r.minutesListened}", "minutes", Modifier.weight(1f))
                        Stat("${r.discoveryPercent}%", "discovery", Modifier.weight(1f))
                        Stat(r.favoriteHour?.let { InsightsViewModel.hourLabel(it) } ?: "—", "favourite hour", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Space.l))
                    if (r.topArtists.isNotEmpty()) {
                        Text("Top artists", style = ArnavTheme.type.label, color = c.contentMuted)
                        Spacer(Modifier.height(Space.s))
                        val max = r.topArtists.maxOf { it.second }.coerceAtLeast(1)
                        r.topArtists.forEach { (k, min) -> Bar(name(k), "$min min", min.toFloat() / max) }
                    }
                    if (topTracks.isNotEmpty()) {
                        Spacer(Modifier.height(Space.l))
                        Text("Most played", style = ArnavTheme.type.label, color = c.contentMuted)
                        topTracks.take(5).forEachIndexed { i, (t, n) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.s)).clickable { app.play(topTracks.map { it.first }, i) }.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(t.artworkUrl, t.id.value, Modifier.size(40.dp), RoundedCornerShape(Radius.xs), decodeSize = 120)
                                Spacer(Modifier.width(Space.m))
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.artist, style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1)
                                }
                                Text("×$n", style = ArnavTheme.type.numeric, color = c.contentSubtle)
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.s))
                    Text("${r.sessions} listening sessions", style = ArnavTheme.type.caption, color = c.contentSubtle)
                }
            }
        }
        // ---- Streaks & milestones ----
        val st = streak
        if (st != null && (st.longest > 0 || milestones.any { it.progress > 0f })) item {
            Section("Streaks & milestones") { StreaksAndMilestones(st, milestones) }
        }
        // ---- Listening calendar ----
        val grid = heatmap
        if (grid != null) item {
            Section("Listening calendar") { ListeningHeatmap(grid) }
        }
        // ---- Listening clock ----
        item {
            Section("When you listen") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ListeningClock(d.hourHistogram, Modifier.size(150.dp))
                    Spacer(Modifier.width(Space.xl))
                    Column(Modifier.weight(1f)) {
                        val peak = d.hourHistogram.indices.maxByOrNull { d.hourHistogram[it] }
                        Text(peak?.let { "Peak around ${InsightsViewModel.hourLabel(it)}" } ?: "", style = ArnavTheme.type.title, color = c.content)
                        Spacer(Modifier.height(Space.s))
                        WeekBars(d.dayHistogram, Modifier.fillMaxWidth().height(72.dp))
                    }
                }
            }
        }
        // ---- Character ----
        item {
            Section("Your listening character") {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Gauge("Discovery", d.discoveryRatio, Modifier.weight(1f))
                    Gauge("Repeat", d.repeatTendency, Modifier.weight(1f))
                    Gauge("Skips", d.skipRate, Modifier.weight(1f))
                }
                Spacer(Modifier.height(Space.m))
                Text(character(d), style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            }
        }
        if (d.topArtists.isNotEmpty()) item {
            Section("Artist affinity") {
                d.topArtists.forEach { (k, v) -> Bar(name(k), "", v) }
                if (d.topGenres.isNotEmpty()) {
                    Spacer(Modifier.height(Space.m))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        d.topGenres.forEach { (g, _) -> Text(g.replaceFirstChar { it.uppercase() }, style = ArnavTheme.type.caption, color = c.content, modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp)) }
                    }
                    Text("Style hints come from public titles and tags, so they're approximate.", style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(top = Space.s))
                }
            }
        }
        if (d.risingArtists.isNotEmpty() || d.fadingArtists.isNotEmpty()) item {
            Section("Recent shifts") {
                d.risingArtists.forEach { k -> ShiftRow(Icons.AutoMirrored.Rounded.TrendingUp, name(k), "More than usual lately", c.success) }
                d.fadingArtists.forEach { k -> ShiftRow(Icons.AutoMirrored.Rounded.TrendingDown, name(k), "Less than before", c.contentSubtle) }
            }
        }
        if (d.eras.isNotEmpty()) item {
            Section("Music age") {
                Row(Modifier.fillMaxWidth().height(90.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    val max = d.eras.maxOf { it.second }
                    d.eras.forEach { (decade, share) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height((70 * share / max).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(4.dp)).background(c.accent))
                            Text("${decade % 100}s".padStart(3, '0'), style = ArnavTheme.type.caption, color = c.contentMuted)
                        }
                    }
                }
                Text("From release years where known (upload year for some YouTube videos).", style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(top = Space.s))
            }
        }
        if (machine.isNotEmpty()) item {
            Section("Time machine") {
                machine.forEach { (insight, tracks) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable { app.play(tracks, 0) }.padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.first().artworkUrl, tracks.first().id.value, Modifier.size(48.dp), RoundedCornerShape(Radius.s), decodeSize = 140)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(insight.headline, style = ArnavTheme.type.titleSmall, color = c.content)
                            Text("${tracks.size} songs · tap to play", style = ArnavTheme.type.caption, color = c.contentMuted)
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Confidence: ${d.confidence.name.lowercase()} · ${d.totalMinutes} minutes analysed",
                style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(Space.gutter),
            )
        }
        if (analyzedCount > 0) item {
            Text(
                "Audio analysis: $analyzedCount local ${if (analyzedCount == 1) "song" else "songs"} measured for tempo and loudness, on this device.",
                style = ArnavTheme.type.caption, color = c.contentSubtle,
                modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, bottom = Space.gutter),
            )
        }
    }
}

private fun character(d: TasteDna): String = buildString {
    append(when {
        d.discoveryRatio > 0.55f -> "You're an explorer — most of what you play is new to you."
        d.discoveryRatio < 0.2f -> "You're loyal to your favourites."
        else -> "You balance favourites with new finds."
    })
    if (d.repeatTendency > 0.3f) append(" When something clicks, you play it on repeat.")
    if (d.skipRate > 0.35f) append(" You know quickly what isn't for you.")
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.xl)) {
        Text(title, style = ArnavTheme.type.title, color = ArnavTheme.colors.content)
        Spacer(Modifier.height(Space.m))
        content()
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = ArnavTheme.type.headline, color = ArnavTheme.colors.content, maxLines = 1)
        Text(label, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted)
    }
}

@Composable
private fun Bar(label: String, trailing: String, fraction: Float) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val anim = remember(label) { Animatable(0f) }
    LaunchedEffect(fraction) { anim.animateTo(fraction.coerceIn(0f, 1f), motion.expressive()) }
    Column(Modifier.padding(vertical = 5.dp)) {
        Row {
            Text(label, style = ArnavTheme.type.bodySmall, color = c.content, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing, style = ArnavTheme.type.numeric, color = c.contentSubtle)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.content.copy(alpha = 0.07f))) {
            Box(Modifier.fillMaxWidth(anim.value.coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape).background(c.accent))
        }
    }
}

@Composable
private fun Gauge(label: String, value: Float, modifier: Modifier) {
    val c = ArnavTheme.colors
    Column(modifier.glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).semantics { contentDescription = "$label ${(value * 100).toInt()} percent" }) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 6.dp.toPx()
                drawArc(c.content.copy(alpha = 0.08f), 135f, 270f, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s, s), size = Size(size.width - 2 * s, size.height - 2 * s))
                drawArc(c.accent, 135f, 270f * value.coerceIn(0f, 1f), false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s, s), size = Size(size.width - 2 * s, size.height - 2 * s))
            }
            Text("${(value * 100).toInt()}%", style = ArnavTheme.type.titleSmall, color = c.content)
        }
        Text(label, style = ArnavTheme.type.caption, color = c.contentMuted)
    }
}

/** 24-hour radial chart: listening minutes as petals around a clock face. */
@Composable
private fun ListeningClock(hours: List<Float>, modifier: Modifier) {
    val c = ArnavTheme.colors
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "Listening by hour of day" }) {
        val max = hours.maxOrNull()?.takeIf { it > 0 } ?: 1f
        val center = Offset(size.width / 2, size.height / 2)
        val inner = size.minDimension * 0.18f
        val outer = size.minDimension * 0.48f
        drawCircle(c.content.copy(alpha = 0.06f), outer, center, style = Stroke(1.dp.toPx()))
        hours.forEachIndexed { h, v ->
            val a = (h / 24f) * 2 * PI - PI / 2
            val len = inner + (outer - inner) * (v / max)
            drawLine(
                if (v == max) c.accent else c.accent.copy(alpha = 0.35f + 0.5f * v / max),
                center + Offset((cos(a) * inner).toFloat(), (sin(a) * inner).toFloat()),
                center + Offset((cos(a) * len).toFloat(), (sin(a) * len).toFloat()),
                strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun WeekBars(days: List<Float>, modifier: Modifier) {
    val c = ArnavTheme.colors
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    val max = days.maxOrNull()?.takeIf { it > 0 } ?: 1f
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().fillMaxHeight((v / max * 0.75f).coerceAtLeast(0.04f)).clip(RoundedCornerShape(4.dp)).background(if (v == max) c.accent else c.content.copy(alpha = 0.15f)))
                Text(labels[i], style = ArnavTheme.type.caption, color = c.contentSubtle)
            }
        }
    }
}

@Composable
private fun ShiftRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String, tint: Color) {
    Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.m))
        Text(name, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content, modifier = Modifier.weight(1f))
        Text(detail, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted)
    }
}

@Composable
private fun LinkCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(56.dp).glass(GlassMaterial.Thin, RoundedCornerShape(Radius.m)).clickable(onClick = onClick).padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ArnavTheme.colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(title, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content)
    }
}

@Composable
private fun StreaksAndMilestones(streak: StreakInfo, milestones: List<Milestone>) {
    val c = ArnavTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val achieved = remember(milestones) { milestones.filter { it.achieved }.sortedByDescending { it.achievedAt ?: 0L } }
    val next = remember(milestones) { Milestones.upcoming(milestones, 2) }
    Column(Modifier.fillMaxWidth().glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat(days(streak.current), "current streak", Modifier.weight(1f))
            Stat(days(streak.longest), "longest streak", Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.m))
        WeekStrip(streak.last7)
        streakNote(streak)?.let { note ->
            Text(note, style = ArnavTheme.type.caption, color = c.contentMuted, modifier = Modifier.padding(top = Space.s))
        }
        if (achieved.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            Text("Reached", style = ArnavTheme.type.label, color = c.contentMuted)
            Spacer(Modifier.height(Space.xs))
            achieved.forEach { m ->
                val date = m.achievedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(dateFormat) } ?: ""
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.EmojiEvents, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1)
                        Text(m.detail, style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(Space.s))
                    Text(date, style = ArnavTheme.type.caption, color = c.contentSubtle)
                }
            }
        }
        if (next.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            Text("Coming up", style = ArnavTheme.type.label, color = c.contentMuted)
            next.forEach { m ->
                Column(Modifier.padding(vertical = 5.dp).semantics { contentDescription = "${m.title}, ${(m.progress * 100).toInt()} percent" }) {
                    Row {
                        Text(m.title, style = ArnavTheme.type.bodySmall, color = c.content, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${(m.progress * 100).toInt()}%", style = ArnavTheme.type.numeric, color = c.contentSubtle)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.content.copy(alpha = 0.07f))) {
                        Box(Modifier.fillMaxWidth(m.progress.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(c.accent.copy(alpha = 0.55f)))
                    }
                }
            }
        }
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

/** A quiet acknowledgement — never a warning about losing a streak. */
private fun streakNote(s: StreakInfo): String? = when {
    s.current >= 2 && s.current >= s.longest -> "Your longest run so far. Nice rhythm."
    s.current >= 7 -> "A week or more of music, day after day."
    else -> null
}

/** The last seven days, oldest first; filled dots are days with music. */
@Composable
private fun WeekStrip(active: List<Boolean>) {
    val c = ArnavTheme.colors
    val today = remember { LocalDate.now() }
    val labels = remember(today) {
        (6 downTo 0).map { today.minusDays(it.toLong()).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) }
    }
    val count = active.count { it }
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Music on $count of the last 7 days" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        active.forEachIndexed { i, on ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) c.accent else c.content.copy(alpha = 0.1f)))
                Spacer(Modifier.height(4.dp))
                Text(labels.getOrElse(i) { "" }, style = ArnavTheme.type.caption, color = if (i == active.lastIndex) c.content else c.contentSubtle)
            }
        }
    }
}
