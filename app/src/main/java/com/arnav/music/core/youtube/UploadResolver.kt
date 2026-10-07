package com.arnav.music.core.youtube

import com.arnav.music.domain.catalog.TrackClassifier
import com.arnav.music.domain.catalog.isSingle
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.SearchFilter

/**
 * Finds another upload of the same song — the official "Topic" audio for Song mode, a music
 * video for Video mode, or any playable upload when one can't be embedded. One search, cache-first.
 */
class UploadResolver(private val youtube: YouTubeRepository) {
    suspend fun alternative(track: Track, want: MediaVariant?, exclude: Set<String> = emptySet()): Track? {
        val query = "${track.artist} ${track.title}".take(100)
        val results = youtube.cached(query, SearchFilter.TRACKS) ?: youtube.search(query, SearchFilter.TRACKS).getOrNull() ?: return null
        val candidates = results.tracks.filter { c ->
            c.id != track.id && c.playbackRef !in exclude && c.isSingle() &&
                TrackClassifier.titleSimilarity(c.title, track.title) >= 0.6f
        }
        return candidates.sortedWith(
            compareBy<Track>(
                { if (want != null && it.variant == want) 0 else if (it.variant == null) 1 else 2 },
                { if (it.artistKey == track.artistKey) 0 else 1 },
            ),
        ).firstOrNull { want == null || it.variant == want || it.variant == null }
    }
}
