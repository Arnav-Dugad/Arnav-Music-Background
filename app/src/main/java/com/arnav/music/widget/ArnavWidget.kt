package com.arnav.music.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.arnav.music.MainActivity
import com.arnav.music.R
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.playback.RepeatMode
import com.arnav.music.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

class ArnavWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 64.dp), DpSize(260.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetBus.current(context)
        withContext(Dispatchers.IO) { WidgetBus.ensureArt(context, initial.artworkUrl) }
        Widgets.load()
        provideContent {
            // Observed inside the composition: Glance doesn't re-run provideGlance for a live session.
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
        val tall = LocalSize.current.height >= 100.dp
        val p = widgetPalette()
        val open = openPlayerAction(context)
        // 12 dp in both styles: this widget's sizes are too short for the 16 dp Material 3 padding.
        Column(GlanceModifier.widgetRoot(p, 12.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                Artwork(art, if (tall) 64 else 44)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(s.title ?: "Arnav Music", style = TextStyle(color = p.title, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(s.artist ?: "Tap to start listening", style = TextStyle(color = p.body, fontSize = 12.sp), maxLines = 1)
                }
            }
            if (tall) {
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TransportButtons(context, s)
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetButton(R.drawable.ic_w_ai, "Ask Arnav AI", actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("arnavmusic://ai"), context, MainActivity::class.java)))
                    WidgetButton(R.drawable.ic_w_moment, "Moments", actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("arnavmusic://moment/night_drive"), context, MainActivity::class.java)))
                }
            }
        }
    }

    companion object {
        /**
         * Called by the app when playback changes. Cheap: writes a snapshot, publishes it to running
         * widget sessions and asks Glance to (re)render. Never touches the player.
         */
        suspend fun refresh(
            context: Context,
            title: String?,
            artist: String?,
            artworkUrl: String?,
            playing: Boolean,
            youtube: Boolean,
            hasNext: Boolean,
            upNext: List<WidgetQueueItem> = emptyList(),
        ) {
            // Art first so running sessions never flash the placeholder between two covers.
            WidgetBus.ensureArt(context, artworkUrl)
            WidgetBus.publish(context, WidgetSnapshot(title, artist, artworkUrl, playing, youtube, hasNext, upNext))
            runCatching { ArnavWidget().updateAll(context) }
            runCatching { QueueWidget().updateAll(context) }
        }
    }
}

class ArnavWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArnavWidget()
}

// region Shared widget pieces

internal fun openPlayerAction(context: Context): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true))

/** Cover art (keeps its own colours) or a themed placeholder. */
@Composable
internal fun Artwork(art: Bitmap?, sizeDp: Int) {
    if (art != null) {
        Image(ImageProvider(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(14.dp))
    } else {
        val p = widgetPalette()
        Box(GlanceModifier.size(sizeDp.dp).cornerRadius(14.dp).background(p.artPlaceholder), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_stat_arnav), contentDescription = null, modifier = GlanceModifier.size(22.dp), colorFilter = p.artPlaceholderIcon)
        }
    }
}

/**
 * Prev / play-pause / next. YouTube can't play while the app is hidden, so for a YouTube track
 * anything that would start playback opens the app instead.
 */
@Composable
internal fun TransportButtons(context: Context, s: WidgetSnapshot) {
    val open = openPlayerAction(context)
    WidgetButton(R.drawable.ic_w_prev, "Previous", if (s.youtube) open else actionRunCallback<PrevAction>())
    WidgetButton(
        if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play, if (s.playing) "Pause" else "Play",
        if (s.youtube && !s.playing) open else actionRunCallback<PlayPauseAction>(),
        primary = true,
    )
    WidgetButton(R.drawable.ic_w_next, "Next", if (s.youtube) open else actionRunCallback<NextAction>())
}

/** Round icon button; [primary] gets a filled container in the Material You style. */
@Composable
internal fun WidgetButton(icon: Int, label: String, action: Action, primary: Boolean = false) {
    val p = widgetPalette()
    val container = if (primary) p.primaryButton else null
    val base = GlanceModifier.size(40.dp).cornerRadius(20.dp)
    Box((if (container != null) base.background(container) else base).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(22.dp),
            colorFilter = if (container != null) p.primaryIcon else p.icon,
        )
    }
}

// endregion

// region Actions (run on Main, never start YouTube playback)

internal suspend fun withPlayer(block: (PlaybackController) -> Unit) = withContext(Dispatchers.Main) {
    runCatching { block(GlobalContext.get().get()) }
}

private fun isLocalAt(p: PlaybackController, index: Int): Boolean =
    p.state.value.queue.items.getOrNull(index)?.track?.source == SourceType.LOCAL

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer { p ->
            val s = p.state.value
            // Pausing is always fine; starting is only allowed for local audio.
            if (s.isPlaying || s.current?.source == SourceType.LOCAL) p.togglePlay()
        }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer { p ->
            val s = p.state.value
            if (s.current?.source != SourceType.LOCAL) return@withPlayer
            val q = s.queue
            val target = when {
                q.hasNext -> q.currentIndex + 1
                s.repeat == RepeatMode.ALL -> 0
                else -> -1 // next() just pauses at the end of the queue
            }
            if (target == -1 || isLocalAt(p, target)) p.next()
        }
    }
}

class PrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer { p ->
            val s = p.state.value
            if (s.current?.source != SourceType.LOCAL) return@withPlayer
            // previous() restarts the current track after 4 s or at the start of the queue.
            val restarts = p.progress.value.positionMs > 4_000 || !s.queue.hasPrevious
            if (restarts || isLocalAt(p, s.queue.currentIndex - 1)) p.previous()
        }
    }
}

// endregion
