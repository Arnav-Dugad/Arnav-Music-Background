package com.arnav.music.domain.provider

import com.arnav.music.domain.model.Artist
import com.arnav.music.domain.model.PlaybackCapabilities
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.flow.Flow

/**
 * Contracts every music source implements. YouTube and on-device files are providers today;
 * Jellyfin / Navidrome / Plex / radio could be added later without touching the UI.
 */
interface MusicCatalogProvider {
    val source: SourceType
    suspend fun track(id: TrackId): Track?
    suspend fun tracks(ids: List<TrackId>): List<Track> = ids.mapNotNull { track(it) }
}

/** TRACKS = songs (singles, audio/art-track uploads preferred); VIDEOS = music videos. */
enum class SearchFilter { ALL, TRACKS, VIDEOS, ARTISTS, PLAYLISTS }

data class SearchResults(
    val query: String,
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val nextPageToken: String? = null,
    val fromCache: Boolean = false,
    val fetchedAt: Long = 0L,
) {
    val isEmpty get() = tracks.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

interface SearchProvider {
    val source: SourceType
    /** Must be cheap / cache-first. Remote calls are the provider's responsibility to budget. */
    suspend fun search(query: String, filter: SearchFilter, pageToken: String? = null): Result<SearchResults>
}

interface PlaybackProvider {
    val source: SourceType
    fun capabilities(track: Track): PlaybackCapabilities
}

interface LibraryProvider {
    val source: SourceType
    fun tracks(): Flow<List<Track>>
}

/** Typed failures shown with intentional, recoverable error states. */
sealed class MusicError(message: String) : Exception(message) {
    data object Offline : MusicError("offline")
    data object QuotaExhausted : MusicError("quota_exhausted")
    data object MissingApiKey : MusicError("missing_api_key")
    data object NotConfigured : MusicError("not_configured")
    data object PermissionDenied : MusicError("permission_denied")
    data object Unavailable : MusicError("unavailable")
    data class Http(val code: Int) : MusicError("http_$code")
    data class Unknown(val detail: String) : MusicError(detail)
}
