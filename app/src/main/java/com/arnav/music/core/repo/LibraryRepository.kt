package com.arnav.music.core.repo

import com.arnav.music.core.analysis.withFeatures
import com.arnav.music.core.common.Clock
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.ImportHistoryEntity
import com.arnav.music.core.db.LikeEntity
import com.arnav.music.core.db.PlayEventEntity
import com.arnav.music.core.db.PlaylistEntity
import com.arnav.music.core.db.PlaylistTrackEntity
import com.arnav.music.core.db.TrackEntity
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.domain.intelligence.PlayStats
import com.arnav.music.domain.library.TrackUsage
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * Unified library across sources: likes, Arnav playlists, history and on-device music.
 * All writes are local-first and optimistic; cloud sync follows asynchronously.
 */
class LibraryRepository(
    private val db: ArnavDatabase,
    private val local: LocalMediaSource,
    private val sync: CloudSync,
    private val clock: Clock,
    scope: CoroutineScope,
) {
    val likedIds: StateFlow<Set<TrackId>> = db.likes().likedIds()
        .map { ids -> ids.map(::TrackId).toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val likedTracks: Flow<List<Track>> = db.likes().likedTracks().map { list -> list.map { it.toDomain() } }

    val playlists: Flow<List<Playlist>> = db.playlists().observe().map { list ->
        list.map {
            Playlist(it.id, it.name, it.description, runCatching { PlaylistKind.valueOf(it.kind) }.getOrDefault(PlaylistKind.ARNAV), it.artworkUrl, it.trackCount, it.updatedAt, it.pinned)
        }
    }

    /** On-device songs, with measured energy from on-device audio analysis where available. */
    val localTracks: StateFlow<List<Track>> = kotlinx.coroutines.flow.combine(local.tracks(), db.audioFeatures().summaries()) { tracks, features ->
        if (features.isEmpty()) tracks else tracks.withFeatures(features.associateBy { it.trackId })
    }.stateIn(scope, SharingStarted.WhileSubscribed(10_000), emptyList())

    val eventCount: Flow<Int> = db.events().count()

    fun recentlyPlayed(limit: Int = 30): Flow<List<Pair<TrackId, Long>>> =
        db.events().recentTracks(limit).map { rows -> rows.map { TrackId(it.trackId) to it.lastPlayed } }

    suspend fun remember(tracks: List<Track>) {
        val now = clock.now()
        db.tracks().upsert(tracks.map { TrackEntity.from(it, now) })
    }

    suspend fun tracks(ids: List<TrackId>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val found = ids.chunked(500).flatMap { chunk -> db.tracks().getAll(chunk.map { it.value }) }.associateBy { it.id }
        val missingLocal = ids.filter { it.value !in found && it.source == com.arnav.music.domain.model.SourceType.LOCAL }
        val localResolved = missingLocal.mapNotNull { local.track(it) }.associateBy { it.id.value }
        return ids.mapNotNull { id -> found[id.value]?.toDomain() ?: localResolved[id.value] }
    }

    suspend fun allKnownTracks(): List<Track> = db.tracks().all().map { it.toDomain() } + localTracks.value

    suspend fun toggleLike(track: Track): Boolean {
        val now = clock.now()
        remember(listOf(track))
        val existing = db.likes().get(track.id.value)
        val nowLiked = existing == null || existing.deleted
        db.likes().upsert(LikeEntity(track.id.value, if (nowLiked) now else existing!!.likedAt, now, deleted = !nowLiked, dirty = true))
        sync.requestSync()
        return nowLiked
    }

    suspend fun createPlaylist(name: String, description: String = "", tracks: List<Track> = emptyList()): String {
        val id = "arn_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = clock.now()
        db.playlists().upsert(PlaylistEntity(id, name.trim().take(100).ifBlank { "New playlist" }, description.take(500), PlaylistKind.ARNAV.name, tracks.firstOrNull()?.artworkUrl, false, now, now))
        if (tracks.isNotEmpty()) {
            remember(tracks)
            db.playlists().replaceTracks(id, tracks.map { it.id.value }, now)
        }
        sync.requestSync()
        return id
    }

    suspend fun addToPlaylist(playlistId: String, tracks: List<Track>) {
        val p = db.playlists().get(playlistId) ?: return
        remember(tracks)
        val now = clock.now()
        var pos = db.playlists().maxPosition(playlistId)
        tracks.forEach { db.playlists().addTrack(PlaylistTrackEntity(playlistId, it.id.value, ++pos, now)) }
        db.playlists().upsert(p.copy(updatedAt = now, dirty = true, artworkUrl = p.artworkUrl ?: tracks.firstOrNull()?.artworkUrl))
        sync.requestSync()
    }

    suspend fun removeFromPlaylist(playlistId: String, trackId: TrackId) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().removeTrack(playlistId, trackId.value)
        db.playlists().upsert(p.copy(updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    suspend fun reorderPlaylist(playlistId: String, ids: List<TrackId>) {
        val p = db.playlists().get(playlistId) ?: return
        val now = clock.now()
        db.playlists().replaceTracks(playlistId, ids.map { it.value }, now)
        db.playlists().upsert(p.copy(updatedAt = now, dirty = true))
        sync.requestSync()
    }

    suspend fun renamePlaylist(playlistId: String, name: String, description: String) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().upsert(p.copy(name = name.take(100), description = description.take(500), updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    suspend fun togglePin(playlistId: String) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().upsert(p.copy(pinned = !p.pinned, updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    suspend fun deletePlaylist(playlistId: String) {
        val p = db.playlists().get(playlistId) ?: return
        db.playlists().clear(playlistId)
        db.playlists().upsert(p.copy(deleted = true, updatedAt = clock.now(), dirty = true))
        sync.requestSync()
    }

    /** Saves a YouTube playlist reference into the library (read-only, clearly labelled). */
    suspend fun saveYouTubePlaylist(playlist: Playlist) {
        val now = clock.now()
        db.playlists().upsert(PlaylistEntity(playlist.id, playlist.name, playlist.description, PlaylistKind.YOUTUBE.name, playlist.artworkUrl, false, now, now, dirty = false, remoteRef = playlist.id.removePrefix("ytpl:")))
    }

    /**
     * Creates or refreshes an Arnav playlist copied from the user's YouTube account. The id is stable
     * per source playlist, so importing again updates it instead of duplicating it. Synced like any
     * Arnav playlist (capped at 500 tracks, the sync schema limit).
     */
    suspend fun importPlaylist(remoteId: String, name: String, description: String, tracks: List<Track>): String {
        val id = "ytimp_" + remoteId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(56)
        val now = clock.now()
        val existing = db.playlists().get(id)
        val capped = tracks.distinctBy { it.id }.take(500)
        remember(capped)
        db.playlists().upsert(
            PlaylistEntity(
                id, name.trim().take(100).ifBlank { "YouTube playlist" }, description.take(500), PlaylistKind.ARNAV.name,
                capped.firstOrNull()?.artworkUrl ?: existing?.artworkUrl, existing?.pinned ?: false,
                existing?.createdAt ?: now, now, deleted = false, dirty = true, remoteRef = remoteId,
            ),
        )
        db.playlists().replaceTracks(id, capped.map { it.id.value }, now)
        sync.requestSync()
        return id
    }

    /** On-device songs right now; queries MediaStore once when nothing is observing [localTracks] yet. */
    suspend fun localTracksSnapshot(): List<Track> =
        localTracks.value.ifEmpty { runCatching { local.tracks().first() }.getOrDefault(emptyList()) }

    /**
     * Creates an Arnav playlist from a Spotify/CSV import. Each track is stored at its position in the
     * source file, so songs matched later can be slotted into their original place.
     */
    suspend fun createImportedPlaylist(name: String, description: String, placed: List<Pair<Int, Track>>): String {
        val id = "arn_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = clock.now()
        val unique = placed.distinctBy { it.second.id }.sortedBy { it.first }
        db.playlists().upsert(
            PlaylistEntity(id, name.trim().take(100).ifBlank { "Imported playlist" }, description.take(500), PlaylistKind.ARNAV.name, unique.firstOrNull()?.second?.artworkUrl, false, now, now),
        )
        if (unique.isNotEmpty()) {
            remember(unique.map { it.second })
            db.playlists().insertTracks(unique.map { (pos, t) -> PlaylistTrackEntity(id, t.id.value, pos, now) })
        }
        sync.requestSync()
        return id
    }

    /**
     * Adds a song matched after import at its source [position]. Returns false when the playlist is
     * gone (deleted) or already holds that song.
     */
    suspend fun insertImportedTrack(playlistId: String, track: Track, position: Int): Boolean {
        val p = db.playlists().get(playlistId) ?: return false
        if (p.deleted) return false
        remember(listOf(track))
        val now = clock.now()
        val inserted = db.playlists().addTrack(PlaylistTrackEntity(playlistId, track.id.value, position, now)) != -1L
        if (inserted) {
            db.playlists().upsert(p.copy(updatedAt = now, dirty = true, artworkUrl = p.artworkUrl ?: track.artworkUrl))
            sync.requestSync()
        }
        return inserted
    }

    /** True while the playlist exists and hasn't been deleted. */
    suspend fun playlistExists(playlistId: String): Boolean = db.playlists().get(playlistId)?.let { !it.deleted } ?: false

    /** Playlists copied from the user's YouTube account (see [importPlaylist]) that are still in the library. */
    suspend fun importedYouTubePlaylists(): List<PlaylistEntity> =
        db.playlists().all().filter { !it.deleted && it.remoteRef != null && it.id.startsWith("ytimp_") }

    fun playlist(id: String): Flow<PlaylistEntity?> = db.playlists().observeOne(id)
    fun playlistTracks(id: String): Flow<List<Track>> = db.playlists().tracks(id).map { l -> l.map { it.toDomain() } }

    suspend fun recordPlay(event: PlayEvent, source: String) {
        db.events().insert(
            PlayEventEntity(
                trackId = event.trackId.value, artistKey = event.artistKey, startedAt = event.startedAt,
                listenedMs = event.listenedMs, durationMs = event.trackDurationMs, completed = event.completed,
                skipped = event.skipped, source = source,
            ),
        )
    }

    suspend fun events(sinceMs: Long = 0L): List<PlayEvent> = db.events().since(sinceMs).map { it.toDomain() }
    suspend fun eventsBetween(from: Long, to: Long): List<PlayEvent> = db.events().between(from, to).map { it.toDomain() }
    fun observeEvents(sinceMs: Long): Flow<List<PlayEvent>> = db.events().observeSince(sinceMs).map { l -> l.map { it.toDomain() } }
    suspend fun firstEventAt(): Long? = db.events().firstEventAt()
    suspend fun clearHistory() { db.events().clear(); db.skipMarks().clear() }
    suspend fun tracksByArtist(artistKey: String): List<Track> = db.tracks().byArtist(artistKey).map { it.toDomain() }
    suspend fun searchKnown(q: String): List<Track> = db.tracks().searchLocal(q).map { it.toDomain() }

    private fun PlayEventEntity.toDomain() = PlayEvent(TrackId(trackId), artistKey, startedAt, listenedMs, durationMs, completed, skipped)

    // ------------------------------------------------------------------ play counts

    /** Plays of 30 s or more for one song; null when it hasn't been played that long yet. */
    suspend fun playStats(trackId: TrackId): PlayStats? {
        val row = db.events().playStats(trackId.value)
        return if (row.plays > 0) PlayStats(row.plays, row.firstPlayed, row.lastPlayed) else null
    }

    // ------------------------------------------------------------------ duplicates

    /**
     * Songs the user keeps — in an Arnav playlist, liked, or on this device — with where each one is
     * used. Search results that were only seen, never saved, are left out.
     */
    suspend fun libraryUsage(): Pair<List<Track>, Map<TrackId, TrackUsage>> {
        val entries = db.playlists().activeEntries()
        val liked = db.likes().all().filter { !it.deleted }.map { it.trackId }.toSet()
        val stored = tracks((entries.map { it.trackId } + liked).distinct().map(::TrackId))
        val all = (stored + localTracksSnapshot()).distinctBy { it.id }
        val inPlaylists = entries.groupingBy { it.trackId }.eachCount()
        val plays = db.events().since(0L).filter { it.listenedMs >= PlayStats.MIN_LISTEN_MS }.groupingBy { it.trackId }.eachCount()
        val usage = all.associate { t ->
            t.id to TrackUsage(playlists = inPlaylists[t.id.value] ?: 0, plays = plays[t.id.value] ?: 0, liked = t.id.value in liked)
        }
        return all to usage
    }

    /**
     * Swaps [from] for [to] in every Arnav playlist (same position) and in likes. Playlists that
     * already hold [to] simply lose [from]. Play history is left as it was. Returns the number of
     * playlists changed.
     */
    suspend fun replaceEverywhere(from: TrackId, to: Track): Int {
        if (from == to.id) return 0
        remember(listOf(to))
        val now = clock.now()
        var changed = 0
        val byPlaylist = db.playlists().activeEntries().groupBy { it.playlistId }
        for ((playlistId, rows) in byPlaylist) {
            val old = rows.firstOrNull { it.trackId == from.value } ?: continue
            val p = db.playlists().get(playlistId) ?: continue
            if (p.deleted || p.kind != PlaylistKind.ARNAV.name) continue
            db.playlists().removeTrack(playlistId, from.value)
            if (rows.none { it.trackId == to.id.value }) {
                db.playlists().addTrack(PlaylistTrackEntity(playlistId, to.id.value, old.position, old.addedAt))
            }
            db.playlists().upsert(p.copy(updatedAt = now, dirty = true, artworkUrl = p.artworkUrl ?: to.artworkUrl))
            changed++
        }
        val like = db.likes().get(from.value)
        if (like != null && !like.deleted) {
            db.likes().upsert(like.copy(deleted = true, updatedAt = now, dirty = true))
            val target = db.likes().get(to.id.value)
            if (target == null || target.deleted) db.likes().upsert(LikeEntity(to.id.value, like.likedAt, now, deleted = false, dirty = true))
        }
        sync.requestSync()
        return changed
    }

    // ------------------------------------------------------------------ import history

    fun importHistory(): Flow<List<ImportHistoryEntity>> = db.importHistory().observe()

    suspend fun importHistoryEntry(id: String): ImportHistoryEntity? = db.importHistory().get(id)

    suspend fun saveImportHistory(entry: ImportHistoryEntity) = db.importHistory().upsert(entry)

    /** Records one import (Spotify/CSV file, YouTube import or refresh) so it can be undone later. */
    suspend fun recordImport(source: String, label: String, playlistIds: List<String>, songCount: Int, matchedCount: Int): String? {
        val ids = playlistIds.filter(::isImportedPlaylistId).distinct()
        if (ids.isEmpty()) return null
        val id = "imp_" + UUID.randomUUID().toString().replace("-", "").take(16)
        db.importHistory().upsert(
            ImportHistoryEntity(
                id = id, source = source, label = label.trim().take(120).ifBlank { "Import" }, playlistIds = ids.joinToString(","),
                songCount = songCount, matchedCount = matchedCount, createdAt = clock.now(),
            ),
        )
        return id
    }

    /** Each playlist with its current number of songs, in the given order; missing ids are skipped. */
    suspend fun playlistsWithCounts(ids: List<String>): List<Pair<PlaylistEntity, Int>> =
        ids.mapNotNull { id -> db.playlists().get(id)?.let { it to db.playlists().trackIds(id).size } }

    /**
     * Hides playlists created by an import (soft delete, songs kept so it can be redone). Only ids made
     * by the import flows are touched; returns the ids actually hidden.
     */
    suspend fun hideImportedPlaylists(ids: List<String>): List<String> {
        val now = clock.now()
        val hidden = ArrayList<String>()
        for (id in ids.distinct()) {
            if (!isImportedPlaylistId(id)) continue
            val p = db.playlists().get(id) ?: continue
            if (p.deleted) continue
            db.playlists().upsert(p.copy(deleted = true, updatedAt = now, dirty = true))
            hidden += id
        }
        if (hidden.isNotEmpty()) sync.requestSync()
        return hidden
    }

    /** Brings back playlists hidden by [hideImportedPlaylists]; returns how many came back. */
    suspend fun restoreImportedPlaylists(ids: List<String>): Int {
        val now = clock.now()
        var restored = 0
        for (id in ids.distinct()) {
            if (!isImportedPlaylistId(id)) continue
            val p = db.playlists().get(id) ?: continue
            if (!p.deleted) continue
            db.playlists().upsert(p.copy(deleted = false, updatedAt = now, dirty = true))
            restored++
        }
        if (restored > 0) sync.requestSync()
        return restored
    }

    /** Ids made by [createImportedPlaylist] ("arn_…") or [importPlaylist] ("ytimp_…"); history only ever lists these. */
    private fun isImportedPlaylistId(id: String): Boolean = id.startsWith("arn_") || id.startsWith("ytimp_")
}
