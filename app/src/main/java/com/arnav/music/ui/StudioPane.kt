package com.arnav.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arnav.music.core.playback.PlayerState
import com.arnav.music.core.playback.Progress
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.HeartButton
import com.arnav.music.ui.components.PlayPauseButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space

/** Width of the docked Now Playing + queue pane in the tablet "Studio" layout. */
val StudioPaneWidth: Dp = 372.dp

/**
 * Tablet "Studio" pane: Now Playing docked beside your library with Up Next underneath, so the
 * library, the queue and the player sit side by side. The artwork (or YouTube player) itself is the
 * same travelling surface as on phones; this pane reserves its slot via [onArtBounds].
 */
@Composable
fun StudioPane(
    track: Track,
    state: PlayerState,
    progress: Progress,
    liked: Boolean,
    onArtBounds: (Rect) -> Unit,
    onExpand: () -> Unit,
    togglePlay: () -> Unit,
    next: () -> Unit,
    previous: () -> Unit,
    toggleLike: (Track) -> Unit,
    openArtist: (String) -> Unit,
    queue: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val video = track.source == SourceType.YOUTUBE
    Column(
        modifier.width(StudioPaneWidth).fillMaxHeight().background(c.surface).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.s, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text("Now playing", style = ArnavTheme.type.overline, color = c.contentMuted, modifier = Modifier.weight(1f))
            ArnavIconButton(Icons.Rounded.OpenInFull, "Open full player", onExpand, tint = c.contentMuted, size = 18.dp)
        }
        // Slot for the travelling artwork / YouTube player.
        Box(
            Modifier.padding(horizontal = Space.l).fillMaxWidth().aspectRatio(if (video) 16f / 9f else 1f)
                .clip(RoundedCornerShape(18.dp))
                .background(c.surfaceRaised)
                .onGloballyPositioned { onArtBounds(it.boundsInRoot()) }
                .semantics { contentDescription = "Now playing artwork" },
        )
        Spacer(Modifier.height(Space.m))
        Row(Modifier.padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(track, contentKey = { it.id }, transitionSpec = { fadeIn(motion.fast()) togetherWith fadeOut(motion.fast()) }, modifier = Modifier.weight(1f), label = "studioMeta") { t ->
                Column {
                    Text(t.title, style = ArnavTheme.type.title, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        t.artist, style = ArnavTheme.type.body, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openArtist(t.artist) },
                    )
                }
            }
            HeartButton(liked, { toggleLike(track) }, size = 22.dp)
        }
        Spacer(Modifier.height(Space.s))
        // Slim progress with times.
        Column(Modifier.padding(horizontal = Space.l)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.content.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(c.accent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(Formatters.duration(progress.positionMs), style = ArnavTheme.type.numeric, color = c.contentSubtle)
                Spacer(Modifier.weight(1f))
                Text(if (progress.durationMs > 0) "-" + Formatters.duration(progress.durationMs - progress.positionMs) else "", style = ArnavTheme.type.numeric, color = c.contentSubtle)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ArnavIconButton(Icons.Rounded.SkipPrevious, "Previous", previous, tint = c.content, size = 30.dp, enabled = state.capabilities.canSkip)
            Spacer(Modifier.width(Space.l))
            PlayPauseButton(state.isPlaying, togglePlay, size = 56.dp, buffering = state.isBuffering)
            Spacer(Modifier.width(Space.l))
            ArnavIconButton(Icons.Rounded.SkipNext, "Next", next, tint = c.content, size = 30.dp, enabled = state.capabilities.canSkip && state.queue.hasNext)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline.copy(alpha = 0.5f)))
        Box(Modifier.weight(1f).fillMaxWidth()) { queue() }
    }
}

@Suppress("unused") private val unusedFill = Modifier.fillMaxSize()
