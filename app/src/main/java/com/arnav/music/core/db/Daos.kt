package com.arnav.music.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert suspend fun upsert(tracks: List<TrackEntity>)
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun get(id: String): TrackEntity?
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun getAll(ids: List<String>): List<TrackEntity>
    @Query("SELECT * FROM tracks") suspend fun all(): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun searchLocal(q: String, limit: Int = 30): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE artistKey = :artistKey ORDER BY updatedAt DESC")
    suspend fun byArtist(artistKey: String): List<TrackEntity>
    @Query("SELECT COUNT(*) FROM tracks") suspend fun count(): Int
}

@Dao
interface LikeDao {
    @Query("SELECT l.trackId FROM likes l WHERE l.deleted = 0 ORDER BY l.likedAt DESC")
    fun likedIds(): Flow<List<String>>

    @Query("SELECT t.* FROM tracks t INNER JOIN likes l ON l.trackId = t.id WHERE l.deleted = 0 ORDER BY l.likedAt DESC")
    fun likedTracks(): Flow<List<TrackEntity>>

    @Upsert suspend fun upsert(like: LikeEntity)
    @Upsert suspend fun upsertAll(likes: List<LikeEntity>)
    @Query("SELECT * FROM likes WHERE trackId = :id") suspend fun get(id: String): LikeEntity?
    @Query("SELECT * FROM likes WHERE dirty = 1") suspend fun dirty(): List<LikeEntity>
    @Query("SELECT * FROM likes") suspend fun all(): List<LikeEntity>
    @Query("UPDATE likes SET dirty = 0 WHERE trackId IN (:ids)") suspend fun markClean(ids: List<String>)
}

data class PlaylistWithCount(
    val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val artworkUrl: String?,
    val pinned: Boolean,
    val updatedAt: Long,
    val trackCount: Int,
)

@Dao
interface PlaylistDao {
    @Query("SELECT pt.* FROM playlist_tracks pt INNER JOIN playlists p ON p.id = pt.playlistId WHERE p.deleted = 0 AND p.kind = 'ARNAV'")
    suspend fun activeEntries(): List<PlaylistTrackEntity>
    @Query(
        """SELECT p.id, p.name, p.description, p.kind, p.artworkUrl, p.pinned, p.updatedAt,
           (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount
           FROM playlists p WHERE p.deleted = 0 ORDER BY p.pinned DESC, p.updatedAt DESC"""
    )
    fun observe(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun get(id: String): PlaylistEntity?
    @Query("SELECT * FROM playlists WHERE id = :id") fun observeOne(id: String): Flow<PlaylistEntity?>
    @Upsert suspend fun upsert(p: PlaylistEntity)
    @Upsert suspend fun upsertAll(p: List<PlaylistEntity>)
    @Query("SELECT * FROM playlists WHERE dirty = 1") suspend fun dirty(): List<PlaylistEntity>
    @Query("SELECT * FROM playlists") suspend fun all(): List<PlaylistEntity>
    @Query("UPDATE playlists SET dirty = 0 WHERE id IN (:ids)") suspend fun markClean(ids: List<String>)

    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position ASC")
    fun tracks(id: String): Flow<List<TrackEntity>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :id ORDER BY position ASC")
    suspend fun trackIds(id: String): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :id")
    suspend fun maxPosition(id: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addTrack(e: PlaylistTrackEntity): Long
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: String, trackId: String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId") suspend fun clear(playlistId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTracks(e: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun replaceTracks(playlistId: String, ids: List<String>, now: Long) {
        clear(playlistId)
        insertTracks(ids.distinct().mapIndexed { i, id -> PlaylistTrackEntity(playlistId, id, i, now) })
    }
}

data class TrackPlayCount(val trackId: String, val plays: Int, val lastPlayed: Long)

@Dao
interface PlayEventDao {
    @Query("SELECT COUNT(*) AS plays, MIN(startedAt) AS firstPlayed, MAX(startedAt) AS lastPlayed FROM play_events WHERE trackId = :trackId AND listenedMs >= 30000")
    suspend fun playStats(trackId: String): PlayStatsRow
    @Insert suspend fun insert(e: PlayEventEntity)
    @Query("SELECT * FROM play_events WHERE startedAt >= :since ORDER BY startedAt ASC")
    suspend fun since(since: Long): List<PlayEventEntity>
    @Query("SELECT * FROM play_events WHERE startedAt >= :since ORDER BY startedAt ASC")
    fun observeSince(since: Long): Flow<List<PlayEventEntity>>
    @Query("SELECT * FROM play_events WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    suspend fun between(from: Long, to: Long): List<PlayEventEntity>
    @Query("SELECT trackId, COUNT(*) AS plays, MAX(startedAt) AS lastPlayed FROM play_events GROUP BY trackId ORDER BY lastPlayed DESC LIMIT :limit")
    fun recentTracks(limit: Int): Flow<List<TrackPlayCount>>
    @Query("SELECT COUNT(*) FROM play_events") fun count(): Flow<Int>
    @Query("SELECT MIN(startedAt) FROM play_events") suspend fun firstEventAt(): Long?
    @Query("DELETE FROM play_events") suspend fun clear()
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_cache WHERE `key` = :key") suspend fun get(key: String): SearchCacheEntity?
    @Upsert suspend fun put(e: SearchCacheEntity)
    @Query("DELETE FROM search_cache WHERE fetchedAt < :before") suspend fun prune(before: Long)
    @Query("SELECT COUNT(*) FROM search_cache") suspend fun count(): Int
    @Query("SELECT * FROM search_cache ORDER BY fetchedAt DESC LIMIT 40") suspend fun recentPayloads(): List<SearchCacheEntity>

    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT :limit") fun recent(limit: Int = 12): Flow<List<RecentSearchEntity>>
    @Upsert suspend fun addRecent(e: RecentSearchEntity)
    @Query("DELETE FROM recent_searches WHERE normalized = :key") suspend fun removeRecent(key: String)
    @Query("DELETE FROM recent_searches") suspend fun clearRecent()
    @Query("DELETE FROM search_cache") suspend fun clearCache()
}

@Dao
interface AiCacheDao {
    @Query("SELECT * FROM ai_cache WHERE `key` = :key AND promptVersion = :version") suspend fun get(key: String, version: String): AiCacheEntity?
    @Upsert suspend fun put(e: AiCacheEntity)
    @Query("DELETE FROM ai_cache") suspend fun clear()
    @Query("SELECT COUNT(*) FROM ai_cache") suspend fun count(): Int
}

@Dao
interface KvSyncDao {
    @Query("SELECT * FROM kv_sync WHERE `key` = :key") suspend fun get(key: String): KvSyncEntity?
    @Upsert suspend fun put(e: KvSyncEntity)
    @Query("SELECT * FROM kv_sync WHERE dirty = 1") suspend fun dirty(): List<KvSyncEntity>
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId") suspend fun get(trackId: String): LyricsEntity?
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId") fun observe(trackId: String): Flow<LyricsEntity?>
    @Upsert suspend fun upsert(lyrics: LyricsEntity)
    @Query("DELETE FROM lyrics WHERE trackId = :trackId") suspend fun delete(trackId: String)
}

/** Light projection of [AudioFeaturesEntity] without the envelope blob. */
data class FeatureSummary(val trackId: String, val bpm: Float, val loudnessDb: Float, val energy: Float)

@Dao
interface AudioFeaturesDao {
    @Query("SELECT * FROM audio_features WHERE trackId = :trackId") suspend fun get(trackId: String): AudioFeaturesEntity?
    @Query("SELECT * FROM audio_features WHERE trackId = :trackId") fun observe(trackId: String): Flow<AudioFeaturesEntity?>
    @Query("SELECT trackId, bpm, loudnessDb, energy FROM audio_features WHERE ok = 1") fun summaries(): Flow<List<FeatureSummary>>
    @Query("SELECT trackId FROM audio_features WHERE version = :version") suspend fun analyzedIds(version: Int): List<String>
    @Query("SELECT COUNT(*) FROM audio_features WHERE ok = 1") fun analyzedCount(): Flow<Int>
    /** Moves the outro mark ("Trim it here"); returns 0 when the song has no successful analysis. */
    @Query("UPDATE audio_features SET outroMs = :outroMs WHERE trackId = :trackId AND ok = 1")
    suspend fun setOutro(trackId: String, outroMs: Long): Int
    @Upsert suspend fun upsert(features: AudioFeaturesEntity)
    @Query("DELETE FROM audio_features") suspend fun clear()
}

@Dao
interface PendingMatchDao {
    @Insert suspend fun insertAll(items: List<PendingMatchEntity>)
    @Query("SELECT * FROM pending_matches WHERE failed = 0 ORDER BY createdAt, playlistId, position LIMIT :limit")
    suspend fun next(limit: Int): List<PendingMatchEntity>
    @Query("SELECT * FROM pending_matches WHERE playlistId = :playlistId ORDER BY position") fun forPlaylist(playlistId: String): Flow<List<PendingMatchEntity>>
    @Query("SELECT * FROM pending_matches WHERE id = :id") suspend fun get(id: Long): PendingMatchEntity?
    @Query("SELECT COUNT(*) FROM pending_matches WHERE failed = 0") fun openCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM pending_matches WHERE failed = 0") suspend fun openCountNow(): Int
    @Query("UPDATE pending_matches SET attempts = attempts + 1, failed = :failed WHERE id = :id") suspend fun markAttempt(id: Long, failed: Boolean)
    @Query("DELETE FROM pending_matches WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM pending_matches WHERE playlistId = :playlistId") suspend fun deleteForPlaylist(playlistId: String)
}

@Dao
interface ImportHistoryDao {
    @Upsert suspend fun upsert(item: ImportHistoryEntity)
    @Query("SELECT * FROM import_history ORDER BY createdAt DESC") fun observe(): Flow<List<ImportHistoryEntity>>
    @Query("SELECT * FROM import_history WHERE id = :id") suspend fun get(id: String): ImportHistoryEntity?
    @Query("DELETE FROM import_history WHERE id = :id") suspend fun delete(id: String)
}

data class PlayStatsRow(val plays: Int, val firstPlayed: Long?, val lastPlayed: Long?)

@Dao
interface TagOverrideDao {
    @Upsert suspend fun upsert(item: TagOverrideEntity)
    @Query("SELECT * FROM tag_overrides") fun observeAll(): Flow<List<TagOverrideEntity>>
    @Query("SELECT * FROM tag_overrides") suspend fun all(): List<TagOverrideEntity>
    @Query("SELECT * FROM tag_overrides WHERE trackId = :trackId") suspend fun get(trackId: String): TagOverrideEntity?
    @Query("DELETE FROM tag_overrides WHERE trackId = :trackId") suspend fun delete(trackId: String)
}

@Dao
interface RecFeedbackDao {
    @Upsert suspend fun upsert(item: RecFeedbackEntity)
    @Query("SELECT * FROM rec_feedback") fun observeAll(): Flow<List<RecFeedbackEntity>>
    @Query("SELECT * FROM rec_feedback") suspend fun all(): List<RecFeedbackEntity>
    @Query("DELETE FROM rec_feedback WHERE subject = :subject") suspend fun delete(subject: String)
}

@Dao
interface SkipMarkDao {
    @Insert suspend fun insert(mark: SkipMarkEntity)
    @Query("SELECT * FROM skip_marks WHERE dismissed = 0 ORDER BY skippedAt ASC") suspend fun active(): List<SkipMarkEntity>
    @Query("SELECT * FROM skip_marks ORDER BY playStartedAt ASC") suspend fun all(): List<SkipMarkEntity>
    @Query("UPDATE skip_marks SET dismissed = 1 WHERE trackId = :trackId") suspend fun dismiss(trackId: String)
    @Query("DELETE FROM skip_marks") suspend fun clear()
}
