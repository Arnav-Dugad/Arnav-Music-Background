package com.arnav.music.core.youtube

import com.arnav.music.core.common.Clock
import com.arnav.music.core.db.SearchCacheEntity
import com.arnav.music.core.db.SearchDao
import com.arnav.music.core.db.TrackDao
import com.arnav.music.core.db.TrackEntity
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.Artist
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.provider.MusicCatalogProvider
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.provider.SearchProvider
import com.arnav.music.domain.provider.SearchResults
import com.arnav.music.domain.quota.CachePolicy
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.quota.YouTubeCosts
import com.arnav.music.domain.search.QueryNormalizer
import com.arnav.music.domain.sync.Backoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CachedPage(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val nextPageToken: String? = null,
)

/**
 * YouTube as a catalog + search provider. Quota is treated as scarce:
 * normalised cache keys, in-flight de-duplication, day-level reuse, conserve mode, backoff.
 */
class YouTubeRepository(
    private val api: YouTubeApi,
    private val searchDao: SearchDao,
    private val trackDao: TrackDao,
    private val usage: UsageMeter,
    private val json: Json,
    private val clock: Clock,
    private val dailyBudget: () -> Int,
    private val region: () -> String?,
) : SearchProvider, MusicCatalogProvider {
    override val source = SourceType.YOUTUBE

    private val inflightLock = Mutex()
    private val inflight = HashMap<String, CompletableDeferred<Result<SearchResults>>>()

    fun quotaState(): QuotaState = usage.youtubeState(dailyBudget())

    /** Cache only, never touches the network. Used for instant results while typing. */
    suspend fun cached(query: String, filter: SearchFilter, pageToken: String? = null): SearchResults? {
        val key = (CACHE_VERSION + QueryNormalizer.cacheKey(query, filter.name)) + (pageToken?.let { "#$it" } ?: "")
        val hit = searchDao.get(key) ?: return null
        val page = runCatching { json.decodeFromString(CachedPage.serializer(), hit.payload) }.getOrNull() ?: return null
        return SearchResults(query, page.tracks, page.artists, page.playlists, page.nextPageToken, fromCache = true, fetchedAt = hit.fetchedAt)
    }

    fun shouldRevalidate(results: SearchResults): Boolean = CachePolicy.shouldRevalidate(results.fetchedAt, clock.now(), quotaState())

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): Result<SearchResults> {
        val key = (CACHE_VERSION + QueryNormalizer.cacheKey(query, filter.name)) + (pageToken?.let { "#$it" } ?: "")
        val state = quotaState()
        cached(query, filter, pageToken)?.let { c ->
            if (CachePolicy.isFresh(c.fetchedAt, clock.now(), state)) {
                usage.youtubeCacheHit()
                return Result.success(c)
            }
        }
        if (state == QuotaState.EXHAUSTED) {
            return cached(query, filter, pageToken)?.let { Result.success(it) } ?: Result.failure(MusicError.QuotaExhausted)
        }
        if (!QueryNormalizer.isRemoteWorthy(query)) return Result.success(SearchResults(query))

        // De-duplicate identical in-flight requests (e.g. two screens asking at once).
        val (deferred, owner) = inflightLock.withLock {
            inflight[key]?.let { it to false } ?: CompletableDeferred<Result<SearchResults>>().also { inflight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        val result = runCatching { remoteSearch(query, filter, pageToken, key) }
            .recoverCatching { e ->
                // Serve stale cache rather than an error whenever we can.
                cached(query, filter, pageToken) ?: throw e
            }
        deferred.complete(result)
        inflightLock.withLock { inflight.remove(key) }
        return result
    }

    private suspend fun remoteSearch(query: String, filter: SearchFilter, pageToken: String?, key: String): SearchResults {
        val type = when (filter) {
            SearchFilter.ALL -> "video,channel,playlist"
            SearchFilter.TRACKS, SearchFilter.VIDEOS -> "video"
            SearchFilter.ARTISTS -> "channel"
            SearchFilter.PLAYLISTS -> "playlist"
        }
        val response = withRetry { api.search(query.trim(), type, pageToken, 25, region()) }
        usage.youtubeCall(YouTubeCosts.SEARCH, isSearch = true)

        val videoIds = response.items.mapNotNull { it.id.videoId }
        val tracks = if (videoIds.isEmpty()) emptyList() else {
            val details = withRetry { api.videos(videoIds) }
            usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, isSearch = false)
            details.items.filter { it.status.embeddable && it.snippet.liveBroadcastContent != "live" }.map { it.toTrack() }
                .sortedBy { t -> videoIds.indexOf(t.id.nativeId) }
        }
        val artists = response.items.filter { it.id.channelId != null && it.id.kind.endsWith("channel") }.map {
            val name = it.snippet.channelTitle.ifBlank { it.snippet.title }.replace(Regex("""\s*-\s*Topic$"""), "")
            Artist(ArtistKey.of(name), name, it.snippet.thumbnails.best(), it.id.channelId)
        }.distinctBy { it.key }
        val playlists = response.items.filter { it.id.playlistId != null }.map {
            Playlist("ytpl:${it.id.playlistId}", it.snippet.title, it.snippet.channelTitle, PlaylistKind.YOUTUBE, it.snippet.thumbnails.best())
        }
        val now = clock.now()
        trackDao.upsert(tracks.map { TrackEntity.from(it, now) })
        val page = CachedPage(tracks, artists, playlists, response.nextPageToken)
        searchDao.put(SearchCacheEntity(key, query, json.encodeToString(CachedPage.serializer(), page), now, response.nextPageToken))
        return SearchResults(query, tracks, artists, playlists, response.nextPageToken, fromCache = false, fetchedAt = now)
    }

    /** Trending music chart (1 unit), cached for the day. */
    suspend fun trending(): Result<List<Track>> {
        val region = region()?.takeIf { it.length == 2 } ?: "US"
        val key = "chart|$region"
        val state = quotaState()
        searchDao.get(key)?.let { hit ->
            if (CachePolicy.isFresh(hit.fetchedAt, clock.now(), state)) {
                usage.youtubeCacheHit()
                return runCatching { json.decodeFromString(CachedPage.serializer(), hit.payload).tracks }
            }
        }
        if (state == QuotaState.EXHAUSTED) return Result.failure(MusicError.QuotaExhausted)
        return runCatching {
            val r = withRetry { api.chart(region) }
            usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, false)
            val tracks = r.items.filter { it.status.embeddable }.map { it.toTrack() }
            val now = clock.now()
            trackDao.upsert(tracks.map { TrackEntity.from(it, now) })
            searchDao.put(SearchCacheEntity(key, "chart", json.encodeToString(CachedPage.serializer(), CachedPage(tracks)), now, null))
            tracks
        }.recoverCatching { e ->
            searchDao.get(key)?.let { json.decodeFromString(CachedPage.serializer(), it.payload).tracks } ?: throw e
        }
    }

    suspend fun playlistTracks(playlistId: String): Result<List<Track>> {
        val key = "pl|$playlistId"
        searchDao.get(key)?.let { hit ->
            if (CachePolicy.isFresh(hit.fetchedAt, clock.now(), quotaState())) {
                usage.youtubeCacheHit()
                return runCatching { json.decodeFromString(CachedPage.serializer(), hit.payload).tracks }
            }
        }
        if (quotaState() == QuotaState.EXHAUSTED) return Result.failure(MusicError.QuotaExhausted)
        return runCatching {
            val items = withRetry { api.playlistItems(playlistId) }
            usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
            val ids = items.items.mapNotNull { it.contentDetails.videoId }
            val videos = if (ids.isEmpty()) emptyList() else withRetry { api.videos(ids) }.items
            usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, false)
            val tracks = videos.filter { it.status.embeddable }.map { it.toTrack() }.sortedBy { ids.indexOf(it.id.nativeId) }
            val now = clock.now()
            trackDao.upsert(tracks.map { TrackEntity.from(it, now) })
            searchDao.put(SearchCacheEntity(key, playlistId, json.encodeToString(CachedPage.serializer(), CachedPage(tracks)), now, null))
            tracks
        }
    }

    override suspend fun track(id: TrackId): Track? = trackDao.get(id.value)?.toDomain()

    /** Resolves a single video id (deep links, shared links): Room first, else videos.list (1 unit). */
    suspend fun video(videoId: String): Result<Track> {
        trackDao.get(TrackId.youtube(videoId).value)?.let { return Result.success(it.toDomain()) }
        if (quotaState() == QuotaState.EXHAUSTED) return Result.failure(MusicError.QuotaExhausted)
        return runCatching {
            val v = withRetry { api.videos(listOf(videoId)) }.items.firstOrNull { it.status.embeddable } ?: throw MusicError.Unavailable
            usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, false)
            v.toTrack().also { trackDao.upsert(listOf(TrackEntity.from(it, clock.now()))) }
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: MusicError.QuotaExhausted) {
                usage.youtubeExhausted(); throw e
            } catch (e: MusicError.Http) {
                if (e.code < 500 || attempt >= 2) throw e
            }
            delay(Backoff.delayMs(attempt++, baseMs = 600))
        }
    }

    companion object {
        /** Bumped when track mapping changes so stale cached pages are refetched once. */
        private const val CACHE_VERSION = "v2|"
    }
}

/** Maps a videos.list item to an Arnav track (label-title parsing, Song/Video variant, compilation flag). */
internal fun YtVideo.toTrack(): Track {
    val parsed = Formatters.parseYouTubeTitle(snippet.title, snippet.channelTitle)
    val genres = MetadataEnricher.genres(snippet.title, snippet.tags, snippet.description)
    return Track(
        id = TrackId.youtube(id),
        title = parsed.title,
        artist = parsed.artist,
        album = parsed.album,
        credits = parsed.credits,
        variant = com.arnav.music.domain.catalog.TrackClassifier.variant(snippet.channelTitle, snippet.title),
        compilation = com.arnav.music.domain.catalog.TrackClassifier.isCompilation(snippet.title, Formatters.parseIsoDuration(contentDetails.duration)),
        durationMs = Formatters.parseIsoDuration(contentDetails.duration),
        artworkUrl = snippet.thumbnails.best() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        playbackRef = id,
        channelId = snippet.channelId,
        genres = genres,
        energy = MetadataEnricher.energy(snippet.title, snippet.tags, genres),
        year = MetadataEnricher.year(snippet.publishedAt),
    )
}
