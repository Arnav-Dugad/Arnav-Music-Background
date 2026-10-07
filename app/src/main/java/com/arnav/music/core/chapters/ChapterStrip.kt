package com.arnav.music.core.chapters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arnav.music.core.playback.Progress
import com.arnav.music.domain.chapters.Chapter
import com.arnav.music.domain.chapters.ChapterMath
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space
import org.koin.compose.koinInject

/**
 * Horizontally scrollable chapter chips for the Now Playing screen; the current chapter is
 * highlighted and kept in view. Renders nothing when [track] has fewer than two chapters.
 *
 * Works for local and YouTube tracks: [onSeek] receives the chapter start in ms and should call
 * `PlaybackController.seekTo` (YouTube seeks through the visible in-app player, which is fine).
 */
@Composable
fun ChapterStrip(track: Track?, progress: Progress, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val repo = koinInject<ChapterRepository>()
    // YouTube durations sometimes arrive only from the player; re-query once it crosses the threshold.
    val playerDuration = progress.durationMs.takeIf { it > 0 }
    val longEnough = (track?.durationMs ?: playerDuration ?: 0L) >= ChapterRepository.YOUTUBE_MIN_DURATION_MS
    val flow = remember(track?.id, track?.playbackRef, longEnough) { repo.observe(track, playerDuration) }
    val chapters by flow.collectAsState(initial = emptyList())
    if (chapters.size < 2) return

    val active = ChapterMath.activeIndex(chapters, progress.positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(active, chapters) {
        if (active >= 0) listState.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        itemsIndexed(chapters, key = { _, c -> c.startMs }) { index, chapter ->
            ChapterChip(index, chapter, selected = index == active, onClick = { onSeek(chapter.startMs) })
        }
    }
}

@Composable
private fun ChapterChip(index: Int, chapter: Chapter, selected: Boolean, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    val type = ArnavTheme.type
    val shape = RoundedCornerShape(14.dp)
    val time = Formatters.duration(chapter.startMs)
    Column(
        Modifier
            .widthIn(max = 220.dp)
            .clip(shape)
            .background(if (selected) c.content else c.surfaceRaised.copy(alpha = 0.55f))
            .then(if (selected) Modifier else Modifier.border(1.dp, c.divider, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = "Chapter ${index + 1}, ${chapter.title}, starts at $time"
            }
            .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Text(time, style = type.caption, color = if (selected) c.background.copy(alpha = 0.7f) else c.contentSubtle, maxLines = 1)
        Text(
            chapter.title,
            style = type.label,
            color = if (selected) c.background else c.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
