package com.arnav.music.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.arnav.music.core.lyrics.LyricsRepository
import com.arnav.music.core.lyrics.LyricsState
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.domain.lyrics.LyricLine
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.lyrics.LyricsTiming
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * Lyrics widget: the line being sung right now (large), the next line (dimmed) and the track title
 * (small). Tapping opens the player. It only ever shows text — it never starts or controls playback.
 *
 * How it stays live (Glance 1.1 session rules):
 * - [provideGlance] runs once per Glance *session*. While a session is alive the composition
 *   re-renders from [WidgetBus.lyric] via `collectAsState`, and `provideGlance` is not re-run.
 * - A session closes ~45 s after its first composition unless it receives events; each
 *   `updateAll` sends one (and adds 5 s when less than 5 s are left), or starts a new session that
 *   re-runs [provideGlance] if the old one has ended. So [Feed] calls `updateAll` on every line
 *   change — not just the StateFlow — to keep the widget current for the whole song.
 * - Every rendered change is pushed through `AppWidgetManager.updateAppWidget` (a binder call), so
 *   the feed only emits when the *active line* changes (a few times per minute), never per
 *   progress tick, and only while a lyrics widget is actually placed.
 */
class LyricsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetBus.lyric.value ?: fallback(context)
        Widgets.load()
        provideContent {
            val live by WidgetBus.lyric.collectAsState()
            val materialYou by Widgets.materialYou.collectAsState()
            WidgetTheme(materialYou) {
                Content(context, live ?: initial)
            }
        }
    }

    /** Before the app has computed a line (e.g. right after process start): title only. */
    private fun fallback(context: Context): WidgetLyric {
        val s = WidgetBus.current(context)
        return if (s.title == null) WidgetLyric.Idle else WidgetLyric(WidgetLyric.State.LOADING, s.title, s.artist)
    }

    @Composable
    private fun Content(context: Context, l: WidgetLyric) {
        val tall = LocalSize.current.height >= 170.dp
        val p = widgetPalette()
        val white = p.title
        // The glass style dims the next line a little more than other secondary text.
        val muted = if (p.materialYou) p.body else ColorProvider(Color(0x99FFFFFF))
        val faint = p.faint
        val accent = p.accent
        Column(GlanceModifier.widgetRoot(p, if (tall) p.padding(14.dp) else 14.dp).clickable(openPlayerAction(context))) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LYRICS", style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(GlanceModifier.width(8.dp))
                val heading = listOfNotNull(l.title, l.artist?.takeIf { it.isNotBlank() }).joinToString(" · ")
                Text(heading, style = TextStyle(color = faint, fontSize = 11.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            }
            Spacer(GlanceModifier.height(if (tall) 12.dp else 6.dp))
            when (l.state) {
                WidgetLyric.State.SYNCED -> {
                    Text(
                        l.current ?: "♪",
                        style = TextStyle(color = white, fontSize = if (tall) 22.sp else 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = if (tall) 3 else 2,
                    )
                    val next = l.next
                    if (next != null) {
                        Spacer(GlanceModifier.height(6.dp))
                        Text(next, style = TextStyle(color = muted, fontSize = if (tall) 15.sp else 13.sp), maxLines = if (tall) 2 else 1)
                    }
                }
                WidgetLyric.State.NOTHING_PLAYING -> Message("Nothing playing", "Tap to open Arnav Music", white, muted)
                WidgetLyric.State.NO_LYRICS -> Message("No lyrics for this song", "Add them from the lyrics screen in the app", white, muted)
                WidgetLyric.State.UNSYNCED -> Message("These lyrics aren't time-synced", "Tap to read them in the app", white, muted)
                WidgetLyric.State.LOADING -> Message("…", null, white, muted)
            }
        }
    }

    @Composable
    private fun Message(title: String, body: String?, white: ColorProvider, muted: ColorProvider) {
        Text(title, style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 2)
        if (body != null) {
            Spacer(GlanceModifier.height(4.dp))
            Text(body, style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 2)
        }
    }

    /**
     * Feeds [WidgetBus.lyric] from playback + lyrics. Started once by `ArnavApp`. Emits only when the
     * visible text changes and only while a lyrics widget is placed (checked at start, from the
     * receiver's callbacks, and at most once a minute on track changes).
     */
    object Feed {
        private val placed = MutableStateFlow(false)
        @Volatile private var lastCheckAt = 0L

        fun start(context: Context, scope: CoroutineScope) {
            val app = context.applicationContext
            val player = runCatching { GlobalContext.get().get<PlaybackController>() }.getOrNull() ?: return
            val lyrics = runCatching { GlobalContext.get().get<LyricsRepository>() }.getOrNull() ?: return
            scope.launch(Dispatchers.Default) { recheck(app) }
            scope.launch {
                player.state.map { it.current?.id }.distinctUntilChanged().collect {
                    if (SystemClock.elapsedRealtime() - lastCheckAt > RECHECK_MS) launch(Dispatchers.Default) { recheck(app) }
                }
            }
            scope.launch {
                placed
                    .flatMapLatest { on -> if (on) lines(player, lyrics) else emptyFlow() }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { l ->
                        WidgetBus.publishLyric(l)
                        withContext(Dispatchers.IO) { runCatching { LyricsWidget().updateAll(app) } }
                    }
            }
        }

        /** Called by the receiver when widgets are added or removed. */
        internal fun requestRecheck(context: Context) {
            val app = context.applicationContext
            val scope = runCatching { GlobalContext.get().get<CoroutineScope>() }.getOrNull() ?: return
            scope.launch(Dispatchers.Default) {
                delay(500) // let Glance register the receiver for getGlanceIds first
                recheck(app)
            }
        }

        private suspend fun recheck(context: Context) {
            lastCheckAt = SystemClock.elapsedRealtime()
            val glance = runCatching { GlanceAppWidgetManager(context).getGlanceIds(LyricsWidget::class.java).isNotEmpty() }.getOrDefault(false)
            val direct = runCatching {
                AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LyricsWidgetReceiver::class.java)).isNotEmpty()
            }.getOrDefault(false)
            placed.value = glance || direct
        }

        private fun lines(player: PlaybackController, lyrics: LyricsRepository): Flow<WidgetLyric> =
            player.state
                .map { it.current }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.title == b?.title }
                .flatMapLatest { track ->
                    if (track == null) flowOf(WidgetLyric.Idle)
                    else lyrics.observe(track).flatMapLatest { state -> forState(player, track, state) }
                }

        private fun forState(player: PlaybackController, track: Track, state: LyricsState): Flow<WidgetLyric> = when (state) {
            LyricsState.Loading -> flowOf(WidgetLyric(WidgetLyric.State.LOADING, track.title, track.artist))
            LyricsState.None -> flowOf(WidgetLyric(WidgetLyric.State.NO_LYRICS, track.title, track.artist))
            is LyricsState.Ready -> when (val l = state.lyrics) {
                is Lyrics.Plain -> flowOf(WidgetLyric(WidgetLyric.State.UNSYNCED, track.title, track.artist))
                is Lyrics.Synced -> player.progress
                    .map { LyricsTiming.activeIndex(l.lines, it.positionMs) }
                    .distinctUntilChanged()
                    .map { index -> synced(track, l.lines, index) }
            }
        }

        private fun synced(track: Track, lines: List<LyricLine>, index: Int): WidgetLyric {
            val current = lines.getOrNull(index)?.takeUnless { it.isInstrumental }?.fullText
            var n = index + 1
            while (n < lines.size && lines[n].isInstrumental) n++
            return WidgetLyric(WidgetLyric.State.SYNCED, track.title, track.artist, current, lines.getOrNull(n)?.fullText)
        }

        private const val RECHECK_MS = 60_000L
    }
}

class LyricsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LyricsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        LyricsWidget.Feed.requestRecheck(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        LyricsWidget.Feed.requestRecheck(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        LyricsWidget.Feed.requestRecheck(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        LyricsWidget.Feed.requestRecheck(context)
    }
}
