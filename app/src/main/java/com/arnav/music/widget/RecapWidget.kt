package com.arnav.music.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.arnav.music.MainActivity
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** This week's listening (Mon–Sun, local time zone), computed on device from play events. */
internal data class WeekRecap(
    val minutes: Long,
    val plays: Int,
    val topArtist: String?,
    val topSong: String?,
    val rangeLabel: String,
) {
    val isEmpty: Boolean get() = plays == 0
}

/** "Your week" widget: minutes, top artist, top song and plays. Tap opens Insights. */
class RecapWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 110.dp), DpSize(250.dp, 180.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = compute()
        latest.value = initial
        Widgets.load()
        provideContent {
            val r by latest.collectAsState()
            val materialYou by Widgets.materialYou.collectAsState()
            WidgetTheme(materialYou) {
                Content(context, r ?: initial)
            }
        }
    }

    @Composable
    private fun Content(context: Context, r: WeekRecap) {
        val large = LocalSize.current.height >= 170.dp
        val p = widgetPalette()
        val white = p.title
        val muted = p.body
        val faint = p.faint
        val accent = p.accent
        val open = actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("arnavmusic://insights"), context, MainActivity::class.java))
        Column(GlanceModifier.widgetRoot(p, if (large) p.padding(14.dp) else 14.dp).clickable(open)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR WEEK", style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Text(r.rangeLabel, style = TextStyle(color = faint, fontSize = 11.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(6.dp))
            if (r.isEmpty) {
                Text("Play something — your week will show up here", style = TextStyle(color = muted, fontSize = 13.sp), maxLines = 3)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(NumberFormat.getIntegerInstance().format(r.minutes), style = TextStyle(color = white, fontSize = 30.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(if (r.minutes == 1L) "minute listened" else "minutes listened", style = TextStyle(color = muted, fontSize = 12.sp), modifier = GlanceModifier.padding(bottom = 6.dp), maxLines = 1)
                }
                if (large) {
                    Spacer(GlanceModifier.height(10.dp))
                    Stat("Top artist", r.topArtist ?: "—", white, faint)
                    Spacer(GlanceModifier.height(6.dp))
                    Stat("Top song", r.topSong ?: "—", white, faint)
                    Spacer(GlanceModifier.height(6.dp))
                    Stat("Plays", NumberFormat.getIntegerInstance().format(r.plays.toLong()), white, faint)
                } else {
                    Spacer(GlanceModifier.height(4.dp))
                    val line = listOfNotNull(r.topArtist, "${r.plays} ${if (r.plays == 1) "play" else "plays"}").joinToString(" · ")
                    Text(line, style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun Stat(label: String, value: String, valueColor: ColorProvider, labelColor: ColorProvider) {
        Column(GlanceModifier.fillMaxWidth()) {
            Text(label.uppercase(), style = TextStyle(color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(value, style = TextStyle(color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }

    companion object {
        /** Latest recap for running compositions (Glance doesn't re-run provideGlance for a live session). */
        private val latest = MutableStateFlow<WeekRecap?>(null)

        /** Recomputes and re-renders, but only when a recap widget is actually placed. */
        suspend fun refresh(context: Context) {
            val placed = runCatching { GlanceAppWidgetManager(context).getGlanceIds(RecapWidget::class.java).isNotEmpty() }.getOrDefault(false)
            if (!placed) return
            latest.value = compute()
            runCatching { RecapWidget().updateAll(context) }
        }

        internal suspend fun compute(): WeekRecap = withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val fmt = DateTimeFormatter.ofPattern("MMM d")
            val label = "${monday.format(fmt)} – ${monday.plusDays(6).format(fmt)}"
            val library = runCatching { GlobalContext.get().get<LibraryRepository>() }.getOrNull()
                ?: return@withContext WeekRecap(0, 0, null, null, label)
            val events = runCatching { library.eventsBetween(from, until - 1) }.getOrDefault(emptyList())
            if (events.isEmpty()) return@withContext WeekRecap(0, 0, null, null, label)

            val minutes = events.sumOf { it.listenedMs } / 60_000L
            val topArtistKey = events.groupBy { it.artistKey }.maxByOrNull { (_, e) -> e.sumOf { it.listenedMs } }?.key
            val topTrackId: TrackId? = events.groupBy { it.trackId }
                .maxWithOrNull(compareBy<Map.Entry<TrackId, List<PlayEvent>>>({ it.value.size }, { e -> e.value.sumOf { it.listenedMs } }))
                ?.key
            val topSong = topTrackId?.let { id -> runCatching { library.tracks(listOf(id)).firstOrNull()?.title }.getOrNull() }
            val topArtist = topArtistKey?.let { key ->
                runCatching {
                    val ids = events.asSequence().filter { it.artistKey == key }.map { it.trackId }.distinct().take(5).toList()
                    library.tracks(ids).firstOrNull()?.artist ?: library.tracksByArtist(key).firstOrNull()?.artist
                }.getOrNull()
            }
            WeekRecap(minutes, events.size, topArtist, topSong, label)
        }
    }
}

class RecapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecapWidget()
}
