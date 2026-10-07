package com.arnav.music.ui.player

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay

/** How long a long-press preview plays before it fades away. */
private const val PREVIEW_MS = 15_000L

/**
 * Long-press "peek": the cover pops forward and a short excerpt plays from about a third of the
 * way in. On-device songs use a private player; YouTube plays in its own visible embedded player
 * (never hidden). Whatever was playing pauses and resumes afterwards.
 */
@Composable
fun CoverPreview(
    track: Track,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val appear = remember { Animatable(0f) }
    var remaining by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motion.expressive()) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            val f = 1f - (System.currentTimeMillis() - start).toFloat() / PREVIEW_MS
            remaining = f.coerceIn(0f, 1f)
            if (f <= 0f) break
            delay(100)
        }
        onDismiss()
    }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f * appear.value))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close preview", onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(Space.gutter).widthIn(max = 420.dp).fillMaxWidth()
                .graphicsLayer {
                    val a = appear.value
                    scaleX = 0.88f + 0.12f * a; scaleY = 0.88f + 0.12f * a
                    alpha = a.coerceIn(0f, 1f)
                }
                .glass(GlassMaterial.Elevated, RoundedCornerShape(Radius.xl))
                // Swallow taps on the card so they don't close the preview.
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(Space.l)
                .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Previewing ${track.title} by ${track.artist}" },
        ) {
            val video = track.source == SourceType.YOUTUBE
            val cropArt = org.koin.compose.koinInject<com.arnav.music.core.settings.SettingsRepository>().settings.collectAsState().value.cropArtTracks
            // Cover-art uploads show square, like Now Playing: the player stays visible, only the empty bars fall outside.
            val squareArt = video && cropArt && track.variant == com.arnav.music.domain.model.MediaVariant.SONG
            BoxWithConstraints(
                Modifier.fillMaxWidth().aspectRatio(if (video && !squareArt) 16f / 9f else 1f).clip(RoundedCornerShape(Radius.l)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (video) YouTubePreview(track.playbackRef, if (squareArt) Modifier.requiredSize(maxHeight * (16f / 9f), maxHeight) else Modifier.fillMaxSize()) else {
                    Artwork(track.artworkUrl, track.id.value, Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape, decodeSize = 800)
                    LocalPreview(track)
                }
            }
            // Time left in the excerpt.
            Box(Modifier.padding(top = Space.s).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.content.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxWidth(remaining).height(2.dp).background(c.accent))
            }
            Spacer(Modifier.height(Space.m))
            Text(track.title, style = ArnavTheme.type.title, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = ArnavTheme.type.body, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Play", { onDismiss(); onPlay() }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                SecondaryButton("Queue", { onDismiss(); onQueue() }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.QueueMusic)
                if (onMore != null) ArnavIconButton(Icons.Rounded.MoreHoriz, "More options", { onDismiss(); onMore() })
            }
        }
    }
}

@Composable
private fun LocalPreview(track: Track) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(track.playbackRef)))
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        val duration = track.durationMs ?: 0L
        player.seekTo(if (duration > 60_000L) duration / 3 else 0L)
        player.play()
        // Gentle fade in, hold, and fade out before the preview closes.
        val fade = Animatable(0f)
        fade.animateTo(1f, tween(700, easing = LinearEasing)) { player.volume = value }
        delay(PREVIEW_MS - 2_000L)
        fade.animateTo(0f, tween(1_000, easing = LinearEasing)) { player.volume = value }
        player.pause()
    }
}

@Composable
private fun YouTubePreview(videoId: String, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        YouTubePlayerView(context).apply {
            enableAutomaticInitialization = false
            initialize(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    youTubePlayer.loadVideo(videoId, 45f)
                }
            }, true, IFramePlayerOptions.Builder(context).controls(0).fullscreen(0).rel(0).ivLoadPolicy(3).build())
        }
    }
    DisposableEffect(lifecycle) {
        lifecycle.addObserver(view)
        onDispose {
            lifecycle.removeObserver(view)
            view.release()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
