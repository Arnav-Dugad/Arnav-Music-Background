package com.arnav.music.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId

@Entity(tableName = "tracks", indices = [Index("artistKey")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistKey: String,
    val album: String?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val playbackRef: String,
    val channelId: String?,
    val genres: String,
    val energy: Float?,
    val year: Int?,
    val updatedAt: Long,
    val variant: String? = null,
    val credits: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val compilation: Boolean = false,
) {
    fun toDomain() = Track(
        id = TrackId(id), title = title, artist = artist, album = album, durationMs = durationMs,
        artworkUrl = artworkUrl, playbackRef = playbackRef, channelId = channelId,
        genres = if (genres.isBlank()) emptyList() else genres.split('|'), energy = energy, year = year,
        variant = variant?.let { v -> com.arnav.music.domain.model.MediaVariant.entries.firstOrNull { it.name == v } },
        credits = credits,
        compilation = compilation,
    )

    companion object {
        fun from(t: Track, now: Long) = TrackEntity(
            t.id.value, t.title, t.artist, t.artistKey, t.album, t.durationMs, t.artworkUrl, t.playbackRef,
            t.channelId, t.genres.joinToString("|"), t.energy, t.year, now, t.variant?.name, t.credits, t.compilation,
        )
    }
}

@Entity(tableName = "likes")
data class LikeEntity(
    @PrimaryKey val trackId: String,
    val likedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val artworkUrl: String?,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    /** Source-side id (e.g. a YouTube playlist id) for imported/linked collections. */
    val remoteRef: String? = null,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"], indices = [Index("trackId")])
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAt: Long,
)

@Entity(tableName = "play_events", indices = [Index("startedAt"), Index("trackId"), Index("artistKey")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val artistKey: String,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
    val source: String,
)

@Entity(tableName = "search_cache")
data class SearchCacheEntity(
    @PrimaryKey val key: String,
    val query: String,
    val payload: String,
    val fetchedAt: Long,
    val nextPageToken: String?,
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val normalized: String,
    val display: String,
    val searchedAt: Long,
)

@Entity(tableName = "ai_cache")
data class AiCacheEntity(
    @PrimaryKey val key: String,
    val promptVersion: String,
    val response: String,
    val createdAt: Long,
)

/** Small JSON documents synced as a unit (settings, taste preferences). */
@Entity(tableName = "kv_sync")
data class KvSyncEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
    val dirty: Boolean,
)

/** Lyrics saved on this device: embedded in a local file, or imported/pasted by the user. Never synced. */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    /** Raw LRC (synced) or plain text. */
    val text: String,
    val synced: Boolean,
    /** "embedded", "file", "pasted". */
    val source: String,
    val updatedAt: Long,
)

/** On-device audio analysis of a local file (tempo, loudness, energy curve). */
@Entity(tableName = "audio_features")
data class AudioFeaturesEntity(
    @PrimaryKey val trackId: String,
    /** Beats per minute; 0 when no steady beat was found. */
    val bpm: Float,
    /** Time of the first beat, for phase-locking visuals to the music. */
    val beatOffsetMs: Long,
    /** Integrated loudness, approximate LUFS. */
    val loudnessDb: Float,
    /** 0..1 energy from loudness, onset density and tempo. */
    val energy: Float,
    /** Energy envelope, one unsigned byte per [com.arnav.music.core.analysis.AudioFeatures.ENVELOPE_STEP_MS]. */
    val envelope: ByteArray,
    val analyzedAt: Long,
    /** Analyzer version; rows from older versions are re-analyzed. */
    val version: Int,
    /** False when the file couldn't be decoded (not retried until the version changes). */
    val ok: Boolean,
    /** Musical key 0..23 (0–11 = C..B major, 12–23 = C..B minor); −1 when unknown. */
    @ColumnInfo(defaultValue = "-1") val musicalKey: Int = -1,
    /** Where the music really starts (after leading silence/quiet intro), ms. */
    @ColumnInfo(defaultValue = "0") val introMs: Long = 0,
    /** Where the outro/fade-out tail begins, ms; 0 when unknown. */
    @ColumnInfo(defaultValue = "0") val outroMs: Long = 0,
)

/** A song from a Spotify/CSV import still waiting to be matched to a YouTube upload. */
@Entity(tableName = "pending_matches", indices = [Index("playlistId")])
data class PendingMatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val position: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val attempts: Int = 0,
    /** True once a search found nothing suitable; shown as "No match" and skipped. */
    val failed: Boolean = false,
    val createdAt: Long,
)

/** One file/account import, so it can be undone (playlists hidden) or redone (restored). */
@Entity(tableName = "import_history")
data class ImportHistoryEntity(
    @PrimaryKey val id: String,
    /** "spotify", "csv", "youtube". */
    val source: String,
    /** File name or account label shown in the list. */
    val label: String,
    /** Comma-separated playlist ids created or refreshed by this import. */
    val playlistIds: String,
    val songCount: Int,
    val matchedCount: Int,
    val createdAt: Long,
    val undone: Boolean = false,
)

/**
 * Corrected metadata for an on-device song (auto-tagged from MusicBrainz or edited by the user).
 * Applied on top of MediaStore when listing local music; the audio file itself is never modified.
 */
@Entity(tableName = "tag_overrides")
data class TagOverrideEntity(
    @PrimaryKey val trackId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    /** MusicBrainz recording id, when matched. */
    val mbid: String?,
    /** Cover Art Archive image for the release, when found. */
    val artworkUrl: String?,
    /** "musicbrainz" or "user". */
    val source: String,
    /** Match confidence 0..1 (1 for user edits). */
    val confidence: Float,
    val updatedAt: Long,
)

/** Explicit feedback for the recommender ("Not interested", "Never play this artist", …). */
@Entity(tableName = "rec_feedback", indices = [Index("kind")])
data class RecFeedbackEntity(
    /** Track id or artist key, depending on [kind]. */
    @PrimaryKey val subject: String,
    /** "track_not_interested", "artist_blocked", "track_more_like_this". */
    val kind: String,
    val createdAt: Long,
)

/**
 * Where a song was skipped by hand (next / skip / picking another song) before its end, for
 * "Songs you skip at the same second". [playStartedAt] matches the listen's `play_events.startedAt`.
 * Local only, never synced.
 */
@Entity(tableName = "skip_marks", indices = [Index("trackId"), Index("playStartedAt")])
data class SkipMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playStartedAt: Long,
    val positionMs: Long,
    val durationMs: Long?,
    val skippedAt: Long,
    /** Hidden from the skip-spot list (dismissed or trimmed); still exported. */
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
)
