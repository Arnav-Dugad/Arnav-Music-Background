package com.arnav.music.core.youtube

import com.arnav.music.core.db.PlaylistEntity
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.quota.YouTubeCosts

/** A playlist on the signed-in user's YouTube account. */
data class RemotePlaylist(
    val id: String,
    val title: String,
    val itemCount: Int,
    val artworkUrl: String?,
    /** "Liked videos" — readable by its owner through the API, unlike YouTube Music's "Liked music". */
    val liked: Boolean = false,
)

data class ImportProgress(
    val playlist: String,
    val playlistIndex: Int,
    val playlistCount: Int,
    val tracksRead: Int,
    val tracksExpected: Int,
)

data class ImportSummary(val playlists: Int, val tracks: Int, val skipped: Int)

/** [missing]: gone from YouTube, no longer readable, or empty, so left as they were. */
data class RefreshSummary(val playlists: Int, val tracks: Int, val skipped: Int, val missing: Int)

/**
 * Copies the user's own YouTube playlists into Arnav playlists using the official Data API with a
 * youtube.readonly OAuth token. Only metadata is read (titles, ids, artwork) — playback still goes
 * through the visible YouTube player. The token is passed per call and never stored or logged.
 *
 * Quota: playlists.list and playlistItems.list cost 1 unit per page of 50; videos.list 1 unit per 50.
 * A 200-song playlist costs about 8 units out of the 10,000 daily free quota.
 */
class YouTubeImporter(
    private val api: YouTubeApi,
    private val library: LibraryRepository,
    private val usage: UsageMeter,
) {
    suspend fun playlists(token: String): List<RemotePlaylist> {
        val out = ArrayList<RemotePlaylist>()
        var page: String? = null
        var pages = 0
        do {
            val r = api.myPlaylists(token, page)
            usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
            r.items.forEach { p ->
                out += RemotePlaylist(p.id, p.snippet.title.ifBlank { "Untitled playlist" }, p.contentDetails.itemCount ?: 0, p.snippet.thumbnails.small())
            }
            page = r.nextPageToken
        } while (page != null && ++pages < MAX_PAGES)
        // "LL" is the owner's Liked videos list; listed first, and quietly dropped if the account hides it.
        val liked = runCatching { api.playlistItems("LL", null, token) }.getOrNull()
        if (liked != null && liked.items.isNotEmpty()) {
            usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
            val art = liked.items.firstOrNull()?.snippet?.thumbnails?.small()
            out.add(0, RemotePlaylist("LL", "Liked videos", -1, art, liked = true))
        }
        return out
    }

    /**
     * Imports each selected playlist, reporting progress as pages arrive. Unembeddable, private and
     * deleted videos are skipped (they could never play inside the app anyway).
     */
    suspend fun import(token: String, selected: List<RemotePlaylist>, onProgress: (ImportProgress) -> Unit): ImportSummary {
        var tracksTotal = 0
        var skipped = 0
        val ids = ArrayList<String>()
        try {
            selected.forEachIndexed { index, pl ->
                val read = readTracks(token, pl, index, selected.size, onProgress)
                ids += library.importPlaylist(
                    remoteId = pl.id,
                    name = pl.title,
                    description = "Imported from YouTube",
                    tracks = read.tracks,
                )
                tracksTotal += read.tracks.size
                skipped += read.skipped
            }
        } finally {
            // Recorded even when a later playlist fails, so the ones already copied can be undone.
            if (ids.isNotEmpty()) {
                val label = selected.singleOrNull()?.title ?: "${ids.size} YouTube playlists"
                runCatching { library.recordImport(HISTORY_SOURCE, label, ids, tracksTotal + skipped, tracksTotal) }
            }
        }
        return ImportSummary(selected.size, tracksTotal, skipped)
    }

    /** Arnav playlists previously copied from YouTube (still in the library). */
    suspend fun importedPlaylists(): List<PlaylistEntity> = library.importedYouTubePlaylists()

    /**
     * Re-reads playlists imported earlier (by their YouTube ids) and replaces each Arnav copy's songs
     * with the current YouTube contents. Local names and descriptions are kept. A playlist that no
     * longer exists on YouTube, or comes back with no playable videos, is left untouched.
     * Costs about 1 unit per 50 songs; no searches.
     */
    suspend fun refresh(
        token: String,
        remoteIds: Collection<String>? = null,
        /** False for the quiet daily refresh, so it doesn't add an entry to the import history every day. */
        record: Boolean = true,
        onProgress: (ImportProgress) -> Unit = {},
    ): RefreshSummary {
        val targets = library.importedYouTubePlaylists().filter { p ->
            val ref = p.remoteRef
            ref != null && (remoteIds == null || ref in remoteIds)
        }
        var refreshed = 0
        var tracksTotal = 0
        var skipped = 0
        var missing = 0
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        try {
            targets.forEachIndexed { index, p ->
                val remoteId = p.remoteRef ?: return@forEachIndexed
                val read = try {
                    readTracks(token, RemotePlaylist(remoteId, p.name, 0, null), index, targets.size, onProgress)
                } catch (e: MusicError.Http) {
                    if (e.code == 404 || e.code == 403) { missing++; return@forEachIndexed } else throw e
                }
                if (read.tracks.isEmpty()) { missing++; return@forEachIndexed }
                ids += library.importPlaylist(remoteId = remoteId, name = p.name, description = p.description, tracks = read.tracks)
                names += p.name
                refreshed++
                tracksTotal += read.tracks.size
                skipped += read.skipped
            }
        } finally {
            if (record && ids.isNotEmpty()) {
                val label = "Refresh: " + (names.singleOrNull() ?: "${ids.size} YouTube playlists")
                runCatching { library.recordImport(HISTORY_SOURCE, label, ids, tracksTotal + skipped, tracksTotal) }
            }
        }
        return RefreshSummary(refreshed, tracksTotal, skipped, missing)
    }

    private class ReadResult(val tracks: List<Track>, val skipped: Int)

    private suspend fun readTracks(token: String, pl: RemotePlaylist, index: Int, count: Int, onProgress: (ImportProgress) -> Unit): ReadResult {
        var skipped = 0
        val ids = ArrayList<String>()
        var page: String? = null
        var pages = 0
        do {
            val r = api.playlistItems(pl.id, page, token)
            usage.youtubeCall(YouTubeCosts.PLAYLIST_ITEMS, false)
            r.items.mapNotNullTo(ids) { it.contentDetails.videoId }
            onProgress(ImportProgress(pl.title, index, count, ids.size, pl.itemCount.coerceAtLeast(ids.size)))
            page = r.nextPageToken
        } while (page != null && ++pages < MAX_PAGES && ids.size < MAX_TRACKS)
        val wanted = ids.distinct().take(MAX_TRACKS)
        val tracks = ArrayList<Track>(wanted.size)
        wanted.chunked(50).forEach { chunk ->
            val r = api.videos(chunk, token)
            usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, false)
            val byId = r.items.filter { it.status.embeddable && it.snippet.liveBroadcastContent != "live" }.associateBy { it.id }
            chunk.forEach { id -> byId[id]?.let { tracks += it.toTrack() } ?: skipped++ }
        }
        return ReadResult(tracks, skipped)
    }

    private companion object {
        /** Import-history source tag (see ImportHistoryEntity.source). */
        const val HISTORY_SOURCE = "youtube"
        const val MAX_PAGES = 20
        /** Matches the playlist sync limit. */
        const val MAX_TRACKS = 500
    }
}
