package com.arnav.music.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.arnav.music.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Large "Up next" widget: now-playing header plus the next few queue items. */
class QueueWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 180.dp), DpSize(250.dp, 220.dp), DpSize(250.dp, 260.dp), DpSize(250.dp, 300.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetBus.current(context)
        withContext(Dispatchers.IO) { WidgetBus.ensureArt(context, initial.artworkUrl) }
        Widgets.load()
        provideContent {
            val snap by WidgetBus.snapshot.collectAsState()
            val art by WidgetBus.art.collectAsState()
            val materialYou by Widgets.materialYou.collectAsState()
            val s = snap ?: initial
            WidgetTheme(materialYou) {
                Content(context, s, art.bitmap.takeIf { art.url == s.artworkUrl })
            }
        }
    }

    @Composable
    private fun Content(context: Context, s: WidgetSnapshot, art: Bitmap?) {
        val size = LocalSize.current
        val p = widgetPalette()
        val pad = p.padding(12.dp)
        val open = openPlayerAction(context)
        val title = s.title
        Column(GlanceModifier.widgetRoot(p, pad)) {
            if (title == null) {
                Box(GlanceModifier.fillMaxSize().clickable(open), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(null, 44)
                        Spacer(GlanceModifier.height(10.dp))
                        Text(EMPTY, style = TextStyle(color = p.body, fontSize = 13.sp), maxLines = 2)
                    }
                }
            } else {
                // Header: cover, title/artist, transport.
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(GlanceModifier.defaultWeight().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(art, 48)
                        Spacer(GlanceModifier.width(10.dp))
                        Column {
                            Text(title, style = TextStyle(color = p.title, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Text(s.artist.orEmpty(), style = TextStyle(color = p.body, fontSize = 12.sp), maxLines = 1)
                        }
                    }
                    TransportButtons(context, s)
                }
                Spacer(GlanceModifier.height(8.dp))
                Text("UP NEXT", style = TextStyle(color = p.faint, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Spacer(GlanceModifier.height(4.dp))
                // Fixed part ≈ 2 × padding + 48 header + 8 + 15 label + 4; each row is 34 + 2 dp.
                val rows = ((size.height.value - 2 * pad.value - 75f) / 36f).toInt().coerceIn(1, WidgetQueueItem.MAX)
                val items = s.upNext.take(rows)
                if (items.isEmpty()) {
                    Box(GlanceModifier.fillMaxWidth().padding(vertical = 8.dp).clickable(open)) {
                        Text(EMPTY, style = TextStyle(color = p.body, fontSize = 13.sp), maxLines = 2)
                    }
                } else {
                    items.forEach { item -> QueueRow(context, item, p) }
                }
            }
        }
    }

    @Composable
    private fun QueueRow(context: Context, item: WidgetQueueItem, p: WidgetPalette) {
        // Local tracks jump straight there; a YouTube item only opens the app (it can't play hidden).
        val action = if (item.youtube) openPlayerAction(context)
        else actionRunCallback<SkipToQueueItemAction>(actionParametersOf(SkipToQueueItemAction.UidKey to item.uid, SkipToQueueItemAction.IndexKey to item.index))
        Row(
            GlanceModifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp).cornerRadius(12.dp).background(p.row).clickable(action),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title,
                style = TextStyle(color = p.rowTitle, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (item.artist.isNotBlank()) {
                Spacer(GlanceModifier.width(6.dp))
                // Keep the artist short so the title (which has the weight) always stays readable.
                val artist = if (item.artist.length > 22) item.artist.take(21).trimEnd() + "…" else item.artist
                Text("· $artist", style = TextStyle(color = p.rowBody, fontSize = 12.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(2.dp))
    }

    private companion object {
        const val EMPTY = "Nothing queued — tap to open Arnav Music"
    }
}

class QueueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QueueWidget()
}

/** Tap on an "Up next" row: jumps to that queue entry — only ever for on-device audio. */
class SkipToQueueItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val uid = parameters[UidKey] ?: return
        val hint = parameters[IndexKey] ?: -1
        withPlayer { p ->
            val items = p.state.value.queue.items
            // Resolve by uid in case the queue changed since the widget was drawn.
            val index = if (items.getOrNull(hint)?.uid == uid) hint else items.indexOfFirst { it.uid == uid }
            if (index < 0) return@withPlayer
            if (items[index].track.source != SourceType.LOCAL) return@withPlayer
            p.skipTo(index)
        }
    }

    companion object {
        val UidKey = ActionParameters.Key<Long>("uid")
        val IndexKey = ActionParameters.Key<Int>("index")
    }
}
