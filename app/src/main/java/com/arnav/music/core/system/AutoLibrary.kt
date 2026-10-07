package com.arnav.music.core.system

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

/**
 * Browse tree for Android Auto / Automotive / other media browsers. **On-device music only**:
 * every playable item is a MediaStore track (`local:<id>`, content:// uri). YouTube content is
 * never listed, resolved or played here.
 *
 * ```
 * root
 * ├─ Recently played          (local tracks from listening history)
 * ├─ Liked (on this phone)    (liked songs that are files on this device)
 * ├─ Playlists                (Arnav playlists that contain local songs → only those songs)
 * │   └─ playlist:<id>
 * ├─ All songs                (Shuffle all, then A–Z; paged by the browser)
 * └─ Shuffle all              (playable)
 * ```
 *
 * Playable items carry `mediaId` = track id and `requestMetadata.mediaUri` = the content uri.
 * Because Auto's legacy `playFromMediaId` only sends the id back, the list an item was last shown
 * in is remembered so playing it queues the rest of that list.
 */
internal class AutoLibrary(
    private val library: LibraryRepository,
    private val local: LocalMediaSource,
) {
    /** What to play: a list of on-device tracks and where to start. */
    data class Resolved(val tracks: List<Track>, val startIndex: Int)

    private class Cached<T>(val at: Long, val value: T)

    private val lock = Mutex()
    private var snapshot: Cached<List<Track>>? = null
    private var playlistCache: Cached<List<LocalPlaylist>>? = null
    private val contextOf = ConcurrentHashMap<String, String>()

    private data class LocalPlaylist(val id: String, val name: String, val tracks: List<Track>)

    // region tree

    fun rootItem(recent: Boolean): MediaItem =
        folder(if (recent) RECENT_ROOT else ROOT, "Arnav Music", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun item(id: String): MediaItem? = when {
        id == ROOT -> rootItem(false)
        id == RECENT_ROOT -> rootItem(true)
        id == RECENT -> folder(RECENT, "Recently played", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        id == LIKED -> folder(LIKED, "Liked (on this phone)", null, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        id == PLAYLISTS -> folder(PLAYLISTS, "Playlists", null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        id == ALL -> folder(ALL, "All songs", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        id == SHUFFLE -> shuffleItem()
        id.startsWith(PLAYLIST_PREFIX) -> localPlaylists().firstOrNull { PLAYLIST_PREFIX + it.id == id }?.let(::playlistFolder)
        else -> track(id)?.let(::trackItem)
    }

    /** Children of [parentId]; empty for an unknown parent (browsers expect an empty list, not an error). */
    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            folder(RECENT, "Recently played", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(LIKED, "Liked (on this phone)", null, MediaMetadata.MEDIA_TYPE_PLAYLIST),
            folder(PLAYLISTS, "Playlists", null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(ALL, "All songs", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            shuffleItem(),
        )
        RECENT_ROOT -> tracksFor(RECENT).take(1).map(::trackItem)
        PLAYLISTS -> localPlaylists().map(::playlistFolder)
        ALL -> listOf(shuffleItem()) + listed(ALL, tracksFor(ALL))
        else -> listed(parentId, tracksFor(parentId))
    }

    suspend fun search(query: String): List<MediaItem> = listed(SEARCH_PREFIX + query, tracksFor(SEARCH_PREFIX + query))

    // endregion

    // region playback resolution

    /**
     * Turns what a browser asked to play into on-device tracks: one item → its list context (or
     * Shuffle all / a whole folder / a voice search); several items → those that resolve.
     * Null when nothing playable remains.
     */
    suspend fun resolve(items: List<MediaItem>, startIndex: Int): Resolved? {
        if (items.isEmpty()) return null
        if (items.size == 1) return resolveOne(items[0])
        val tracks = ArrayList<Track>(items.size)
        var start = 0
        items.forEachIndexed { i, item ->
            val t = trackFor(item) ?: return@forEachIndexed
            if (i == startIndex) start = tracks.size
            tracks += t
        }
        return if (tracks.isEmpty()) null else Resolved(tracks, start.coerceIn(0, tracks.lastIndex))
    }

    private suspend fun resolveOne(item: MediaItem): Resolved? {
        val id = item.mediaId
        val query = item.requestMetadata.searchQuery
        return when {
            id == SHUFFLE -> tracksFor(ALL).shuffled().takeIf { it.isNotEmpty() }?.let { Resolved(it.take(MAX_QUEUE), 0) }
            id.isEmpty() && query != null -> {
                // Voice: "play <query> on Arnav Music"; an empty query means "play something".
                val list = if (query.isBlank()) tracksFor(ALL).shuffled() else tracksFor(SEARCH_PREFIX + query)
                list.takeIf { it.isNotEmpty() }?.let { Resolved(it.take(MAX_QUEUE), 0) }
            }
            id == RECENT || id == LIKED || id == ALL || id.startsWith(PLAYLIST_PREFIX) ->
                tracksFor(id).takeIf { it.isNotEmpty() }?.let { window(it, 0) }
            else -> {
                val track = trackFor(item) ?: return null
                val list = contextOf[track.id.value]?.let { tracksFor(it) }?.takeIf { l -> l.any { it.id == track.id } }
                if (list == null) Resolved(listOf(track), 0) else window(list, list.indexOfFirst { it.id == track.id })
            }
        }
    }

    private suspend fun trackFor(item: MediaItem): Track? {
        if (item.mediaId.isNotEmpty()) return track(item.mediaId)
        val uri = item.requestMetadata.mediaUri?.toString() ?: return null
        return localSnapshot().firstOrNull { it.playbackRef == uri }
    }

    /** Keeps very long lists to [MAX_QUEUE] items around the start. */
    private fun window(list: List<Track>, index: Int): Resolved {
        val i = index.coerceIn(0, list.lastIndex)
        if (list.size <= MAX_QUEUE) return Resolved(list, i)
        val from = (i - 100).coerceAtLeast(0).coerceAtMost(list.size - MAX_QUEUE)
        return Resolved(list.subList(from, from + MAX_QUEUE).toList(), i - from)
    }

    // endregion

    // region data

    /** A local track by id (only `local:` ids ever resolve). */
    private suspend fun track(id: String): Track? {
        if (id.isEmpty() || TrackId(id).source != SourceType.LOCAL) return null
        localSnapshot().firstOrNull { it.id.value == id }?.let { return it }
        return runCatching { local.track(TrackId(id)) }.getOrNull()
    }

    private suspend fun tracksFor(parentId: String): List<Track> = when {
        parentId == RECENT -> {
            val byId = localById()
            runCatching { library.recentlyPlayed(MAX_RECENT * 2).first() }.getOrDefault(emptyList())
                .mapNotNull { (id, _) -> byId[id.value] }
                .distinctBy { it.id }
                .take(MAX_RECENT)
        }
        parentId == LIKED -> {
            val byId = localById()
            runCatching { library.likedTracks.first() }.getOrDefault(emptyList())
                .filter { it.source == SourceType.LOCAL }
                .mapNotNull { byId[it.id.value] }
        }
        parentId == ALL -> {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            localSnapshot().sortedWith { a, b ->
                val c = collator.compare(a.title, b.title)
                if (c != 0) c else collator.compare(a.artist, b.artist)
            }
        }
        parentId.startsWith(PLAYLIST_PREFIX) -> {
            val id = parentId.removePrefix(PLAYLIST_PREFIX)
            localPlaylists().firstOrNull { it.id == id }?.tracks.orEmpty()
        }
        parentId.startsWith(SEARCH_PREFIX) -> searchLocal(parentId.removePrefix(SEARCH_PREFIX))
        else -> emptyList()
    }

    private suspend fun searchLocal(query: String): List<Track> {
        val tokens = query.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            localSnapshot()
                .map { t -> t to "${t.title} ${t.artist} ${t.album.orEmpty()}".lowercase() }
                .filter { (_, hay) -> tokens.all { it in hay } }
                // Title hits first, then artist hits.
                .sortedByDescending { (t, _) -> tokens.count { t.title.lowercase().contains(it) } }
                .map { it.first }
                .take(MAX_SEARCH)
        }
    }

    private suspend fun localPlaylists(): List<LocalPlaylist> = lock.withLock {
        playlistCache?.takeIf { fresh(it.at) }?.let { return@withLock it.value }
        val byId = localById()
        val playlists = runCatching { library.playlists.first() }.getOrDefault(emptyList())
            .filter { it.kind != PlaylistKind.YOUTUBE }
            .sortedByDescending { it.pinned }
        val out = playlists.mapNotNull { p ->
            val tracks = runCatching { library.playlistTracks(p.id).first() }.getOrDefault(emptyList())
                .filter { it.source == SourceType.LOCAL }
                .mapNotNull { byId[it.id.value] }
                .distinctBy { it.id }
            if (tracks.isEmpty()) null else LocalPlaylist(p.id, p.name, tracks)
        }
        playlistCache = Cached(SystemClock.elapsedRealtime(), out)
        out
    }

    private suspend fun localById(): Map<String, Track> = localSnapshot().associateBy { it.id.value }

    private suspend fun localSnapshot(): List<Track> {
        snapshot?.takeIf { fresh(it.at) }?.let { return it.value }
        val tracks = withContext(Dispatchers.IO) { runCatching { library.localTracksSnapshot() }.getOrDefault(emptyList()) }
            .filter { it.source == SourceType.LOCAL }
        snapshot = Cached(SystemClock.elapsedRealtime(), tracks)
        return tracks
    }

    private fun fresh(at: Long) = SystemClock.elapsedRealtime() - at < CACHE_MS

    // endregion

    // region items

    /** Track items for a list, remembering the list as each track's play context. */
    private fun listed(parentId: String, tracks: List<Track>): List<MediaItem> {
        if (contextOf.size > 20_000) contextOf.clear()
        return tracks.map { t ->
            contextOf[t.id.value] = parentId
            trackItem(t)
        }
    }

    private fun trackItem(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id.value)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(t.playbackRef)).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(t.artworkUrl?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun playlistFolder(p: LocalPlaylist): MediaItem {
        val n = p.tracks.size
        return folder(
            PLAYLIST_PREFIX + p.id, p.name,
            "$n ${if (n == 1) "song" else "songs"} on this phone",
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
            artwork = p.tracks.firstNotNullOfOrNull { it.artworkUrl },
        )
    }

    private fun shuffleItem(): MediaItem = MediaItem.Builder()
        .setMediaId(SHUFFLE)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Shuffle all")
                .setSubtitle("Every song on this phone")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    private fun folder(id: String, title: String, subtitle: String?, type: Int, artwork: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()

    // endregion

    companion object {
        const val ROOT = "auto_root"
        const val RECENT_ROOT = "auto_recent_root"
        const val RECENT = "auto_recent"
        const val LIKED = "auto_liked"
        const val PLAYLISTS = "auto_playlists"
        const val ALL = "auto_all"
        const val SHUFFLE = "auto_shuffle_all"
        const val PLAYLIST_PREFIX = "auto_playlist:"
        private const val SEARCH_PREFIX = "auto_search:"

        private const val CACHE_MS = 20_000L
        private const val MAX_RECENT = 40
        private const val MAX_SEARCH = 50
        /** Longest queue handed to the player from one browse action. */
        const val MAX_QUEUE = 500

        /** Applies the browser's page / pageSize to a full list. */
        fun <T> page(list: List<T>, page: Int, pageSize: Int): List<T> {
            if (page < 0 || pageSize <= 0) return list
            val from = page.toLong() * pageSize
            if (from >= list.size) return emptyList()
            val to = minOf(list.size.toLong(), from + pageSize).toInt()
            return list.subList(from.toInt(), to)
        }
    }
}
