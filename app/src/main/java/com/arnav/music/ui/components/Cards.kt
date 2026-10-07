package com.arnav.music.ui.components

import com.arnav.music.ui.sharedArt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arnav.music.domain.model.Moment
import com.arnav.music.domain.model.Track
import com.arnav.music.feature.moments.MomentCanvas
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Size
import com.arnav.music.ui.theme.Space

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null, onLongClick: (() -> Unit)? = null, width: Dp = Size.artworkL) {
    val interaction = rememberInteraction()
    val haptics = ArnavTheme.haptics
    val nav = com.arnav.music.ui.LocalNavigator.current
    var bounds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Column(
        modifier.width(width)
            .pressScale(interaction, 0.96f)
            .combinedClickable(interaction, indication = null, role = Role.Button, onClick = {
                onClick()
                // The cover flies from this card into Now Playing.
                bounds?.let(nav.flyFrom)
            }, onLongClick = { haptics.longPress(); nav.preview(track, onLongClick) }, onLongClickLabel = "Preview")
            .semantics(mergeDescendants = true) { contentDescription = "${track.title} by ${track.artist}" },
    ) {
        Artwork(track.artworkUrl, track.id.value, Modifier.pressTilt().size(width).onGloballyPositioned { bounds = it.boundsInRoot() }, decodeSize = 360)
        Spacer(Modifier.height(10.dp))
        Text(track.title, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(caption ?: track.artist, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MixCard(title: String, subtitle: String, artwork: List<String>, seed: String, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = Size.artworkL, sharedKey: String? = null) {
    val interaction = rememberInteraction()
    Column(modifier.width(width).pressScale(interaction, 0.96f).combinedClickable(interaction, indication = null, onClick = onClick)) {
        Box {
            Mosaic(artwork, seed, Modifier.sharedArt(sharedKey).pressTilt().size(width))
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(Radius.artwork)).padding(Space.m), contentAlignment = Alignment.BottomStart) {
                Text(title, style = ArnavTheme.type.title, color = Color.White, maxLines = 2,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.xs)))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(subtitle, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MomentCard(moment: Moment, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val interaction = rememberInteraction()
    Box(
        modifier
            .then(if (large) Modifier.fillMaxWidth().aspectRatio(1.6f) else Modifier.size(width = 148.dp, height = 188.dp))
            .pressScale(interaction, 0.97f)
            .pressTilt(if (large) 4f else 7f)
            .clip(RoundedCornerShape(if (large) Radius.xl else Radius.l))
            .combinedClickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${moment.title} moment. ${moment.subtitle}" },
    ) {
        MomentCanvas(moment, Modifier.fillMaxSize(), animated = large, density = if (large) 1f else 0.4f)
        Column(Modifier.align(Alignment.BottomStart).padding(if (large) Space.xl else Space.m)) {
            if (large) Text("MOMENT", style = ArnavTheme.type.overline, color = Color.White.copy(alpha = 0.7f))
            Text(moment.title, style = if (large) ArnavTheme.type.display else ArnavTheme.type.title, color = Color.White)
            Text(moment.subtitle, style = ArnavTheme.type.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 2)
        }
    }
}
