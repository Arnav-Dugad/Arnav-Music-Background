package com.arnav.music.core.repo

import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.db.RecentSearchEntity
import com.arnav.music.core.db.SearchDao
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.catalog.isSingle
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.provider.SearchResults
import com.arnav.music.domain.search.QueryNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

sealed interface SearchState {
    data object Idle : SearchState
    data class Instant(val query: String, val localTracks: List<Track>) : SearchState
    data class Loading(val query: String, val localTracks: List<Track>) : SearchState
    data class Results(val results: SearchResults, val localTracks: List<Track>, val refreshing: Boolean = false) : SearchState
    data class Failed(val query: String, val error: MusicError, val localTracks: List<Track>) : SearchState
}

/**
 * Search intelligence: local library + known artists + cache first, remote last.
 * Emits instant local matches, then cached pages, then (only if needed) a remote page.
 */
class SearchRepository(
    private val youtube: YouTubeRepository,
    private val library: LibraryRepository,
    private val searchDao: SearchDao,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val preferVideos: () -> Boolean = { false },
) {
    /** Songs filter = singles only, audio uploads first; Videos = music videos first; All = mixes last. */
    private fun shape(r: SearchResults, filter: SearchFilter): SearchResults {
        val prefer = if (filter == SearchFilter.VIDEOS || (filter == SearchFilter.ALL && preferVideos())) com.arnav.music.domain.model.MediaVariant.VIDEO
        else com.arnav.music.domain.model.MediaVariant.SONG
        val ranked = com.arnav.music.domain.catalog.rankForListening(r.tracks, prefer)
        val tracks = when (filter) {
            SearchFilter.TRACKS -> ranked.filter { it.isSingle() }
            SearchFilter.VIDEOS -> ranked.filter { it.variant != com.arnav.music.domain.model.MediaVariant.SONG }
            else -> ranked
        }
        return r.copy(tracks = tracks)
    }

    val recent: Flow<List<String>> = searchDao.recent().map { l -> l.map { it.display } }

    suspend fun localMatches(query: String, limit: Int? = null): List<Track> {
        if (query.isBlank()) return emptyList()
        // Snapshot, not the cached flow: on-device songs must match even when no screen observes them.
        val parsed = com.arnav.music.domain.search.LibraryQuery.parse(query)
        val known = library.allKnownTracks() + library.localTracksSnapshot()
        return known.asSequence().distinctBy { it.id }.filter(parsed::accepts)
            .map { it to if (parsed.text.isBlank()) 1f else maxOf(QueryNormalizer.matchScore(parsed.text, it.title), QueryNormalizer.matchScore(parsed.text, it.artist) * 0.95f) }
            .filter { it.second >= 0.5f }.sortedByDescending { it.second }.map { it.first }
            .take(limit ?: if (parsed.structured) 100 else 8).toList()
    }

    fun search(query: String, filter: SearchFilter, remote: Boolean): Flow<SearchState> = flow {
        val local = localMatches(query)
        emit(SearchState.Instant(query, local))
        val parsed = com.arnav.music.domain.search.LibraryQuery.parse(query)
        if (parsed.structured) {
            if (parsed.error != null) emit(SearchState.Failed(query, MusicError.Unknown(parsed.error), local))
            return@flow
        }
        val cached = youtube.cached(query, filter)
        if (cached != null) emit(SearchState.Results(shape(cached, filter), local, refreshing = remote && youtube.shouldRevalidate(cached)))
        if (!remote || !QueryNormalizer.isRemoteWorthy(query)) return@flow
        if (cached != null && !youtube.shouldRevalidate(cached)) return@flow
        if (!network.currentlyOnline()) {
            if (cached == null) emit(SearchState.Failed(query, MusicError.Offline, local))
            return@flow
        }
        if (cached == null) emit(SearchState.Loading(query, local))
        youtube.search(query, filter, null)
            .onSuccess { emit(SearchState.Results(shape(it, filter), local)) }
            .onFailure { e -> if (cached == null) emit(SearchState.Failed(query, e as? MusicError ?: MusicError.Unknown(e.javaClass.simpleName), local)) }
    }

    suspend fun nextPage(results: SearchResults, filter: SearchFilter): Result<SearchResults> {
        val token = results.nextPageToken ?: return Result.success(results)
        return youtube.search(results.query, filter, token).map { shape(it, filter) }.map { page ->
            page.copy(tracks = (results.tracks + page.tracks).distinctBy { it.id }, artists = (results.artists + page.artists).distinctBy { it.key }, playlists = (results.playlists + page.playlists).distinctBy { it.id })
        }
    }

    suspend fun remember(query: String) {
        val n = QueryNormalizer.normalize(query)
        if (n.length < 2) return
        searchDao.addRecent(RecentSearchEntity(n, query.trim().take(80), clock.now()))
    }

    suspend fun forget(query: String) = searchDao.removeRecent(QueryNormalizer.normalize(query))
    suspend fun clearHistory() = searchDao.clearRecent()
    suspend fun clearCache() = searchDao.clearCache()
}
