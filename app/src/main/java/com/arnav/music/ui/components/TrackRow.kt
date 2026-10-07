package com.arnav.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Size
import com.arnav.music.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Track row. Swipe right → add to queue, swipe left → like. Swipes have resistance past the
 * threshold, a snap haptic when armed, and mirror custom accessibility actions so nothing is
 * gesture-only. Long-press opens quick actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    liked: Boolean = false,
    subtitle: String? = null,
    index: Int? = null,
    onQueue: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    showArtwork: Boolean = true,
    compact: Boolean = false,
    /** When true, the left swipe removes (e.g. from a playlist) instead of liking. */
    leftSwipeRemoves: Boolean = false,
) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    val motion = ArnavTheme.motion
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var armed by remember { mutableStateOf(0) }
    val threshold = 0.22f
    val swipeEnabled = onQueue != null || onLike != null

    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        if (swipeEnabled && offset.value != 0f) {
            val right = offset.value > 0
            val progress = (abs(offset.value) / (width * threshold)).coerceIn(0f, 1f)
            Row(
                Modifier.matchParentSize().background(if (right) c.accent.copy(alpha = 0.18f * progress) else c.danger.copy(alpha = 0.16f * progress)).padding(horizontal = Space.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) androidx.compose.foundation.layout.Arrangement.Start else androidx.compose.foundation.layout.Arrangement.End,
            ) {
                Icon(
                    if (right) Icons.AutoMirrored.Rounded.PlaylistAdd else if (leftSwipeRemoves) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.Favorite, null,
                    tint = if (right) c.accent else c.danger,
                    modifier = Modifier.size(22.dp).graphicsLayer { val s = 0.6f + 0.4f * progress; scaleX = s; scaleY = s; alpha = progress },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(c.background.copy(alpha = if (offset.value != 0f) 1f else 0f))
                .then(
                    if (swipeEnabled) Modifier.draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val limit = width * threshold
                                val cur = offset.value
                                // Rubber-band beyond the threshold.
                                val resist = if (abs(cur) > limit) 0.35f else 1f
                                var next = cur + delta * resist
                                if (onQueue == null) next = next.coerceAtMost(0f)
                                if (onLike == null) next = next.coerceAtLeast(0f)
                                offset.snapTo(next)
                                val nowArmed = when { next > limit -> 1; next < -limit -> -1; else -> 0 }
                                if (nowArmed != armed) { armed = nowArmed; if (nowArmed != 0) haptics.snap() }
                            }
                        },
                        onDragStopped = {
                            when (armed) {
                                1 -> { onQueue?.invoke(); haptics.queued() }
                                -1 -> { onLike?.invoke() }
                            }
                            armed = 0
                            offset.animateTo(0f, motion.responsive())
                        },
                    ) else Modifier,
                )
                .combinedClickable(onClick = onClick, onLongClick = onMore?.let { { haptics.longPress(); it() } })
                .semantics {
                    customActions = buildList {
                        onQueue?.let { add(CustomAccessibilityAction("Add to queue") { it(); true }) }
                        onLike?.let { add(CustomAccessibilityAction(if (leftSwipeRemoves) "Remove" else if (liked) "Unlike" else "Like") { it(); true }) }
                        onMore?.let { add(CustomAccessibilityAction("More options") { it(); true }) }
                    }
                }
                .padding(horizontal = Space.gutter, vertical = if (compact) 6.dp else Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Text("$index", style = ArnavTheme.type.numeric, color = c.contentSubtle, modifier = Modifier.width(28.dp))
            }
            if (showArtwork) {
                Box {
                    Artwork(track.artworkUrl, track.id.value, Modifier.size(if (compact) Size.artworkXs else 52.dp), RoundedCornerShape(Radius.s), decodeSize = 160)
                    if (playing) {
                        Box(Modifier.matchParentSize().clip(RoundedCornerShape(Radius.s)).background(c.scrim), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.GraphicEq, "Now playing", tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.width(Space.m))
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, style = ArnavTheme.type.titleSmall, color = if (playing) c.accent else c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (liked) { Icon(Icons.Rounded.Favorite, "Liked", tint = c.accent, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(
                        subtitle ?: buildString {
                            append(track.artist)
                            track.durationMs?.let { append(" · "); append(Formatters.duration(it)) }
                        },
                        style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (track.source == SourceType.LOCAL && !compact) {
                SourceBadge(youtube = false, modifier = Modifier.padding(start = Space.s))
            }
            if (onMore != null) ArnavIconButton(Icons.Rounded.MoreVert, "More options for ${track.title}", onMore, tint = c.contentSubtle, size = 20.dp)
        }
    }
}

@Composable
fun TrackRowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radius.s)).shimmer())
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.35f).height(11.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        }
    }
}

@Composable
fun CardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.width(Size.artworkL)) {
        Box(Modifier.size(Size.artworkL).clip(RoundedCornerShape(Radius.artwork)).shimmer())
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(13.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(11.dp).clip(RoundedCornerShape(4.dp)).shimmer())
    }
}
