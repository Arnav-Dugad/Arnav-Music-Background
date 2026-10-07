package com.arnav.music.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.arnav.music.core.lyrics.LyricsRepository
import com.arnav.music.core.lyrics.LyricsState
import com.arnav.music.core.playback.Progress
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.lyrics.LyricsTiming
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.koinInject

/**
 * The synced lyric line being sung right now, or null when there are no synced lyrics, during an
 * instrumental break, or when [enabled] is false. Drives the mini player's "now singing" caption.
 */
@Composable
fun nowSinging(track: Track, progress: Progress, enabled: Boolean): String? {
    val repo = koinInject<LyricsRepository>()
    val flow = remember(track.id, enabled) { if (enabled) repo.observe(track) else flowOf(LyricsState.None) }
    val state by flow.collectAsState(LyricsState.Loading)
    val ready = state as? LyricsState.Ready ?: return null
    val synced = ready.lyrics as? Lyrics.Synced ?: return null
    val i = LyricsTiming.activeIndex(synced.lines, progress.positionMs)
    return synced.lines.getOrNull(i)?.takeUnless { it.isInstrumental }?.fullText
}
