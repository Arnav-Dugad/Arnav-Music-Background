package com.arnav.music.core.metadata

import android.content.Context
import com.arnav.music.core.db.TagOverrideDao
import com.arnav.music.core.db.TagOverrideEntity
import com.arnav.music.core.local.LocalFileEntry
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.domain.metadata.AutoTagMatch
import com.arnav.music.domain.metadata.AutoTagQuery
import com.arnav.music.domain.metadata.AutoTagScorer
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Fills in missing song info for on-device music from MusicBrainz, and stores the user's own edits.
 *
 * Results live in the `tag_overrides` table and are applied on top of MediaStore by
 * [LocalMediaSource]; audio files are never written. A song is looked up only when its artist is
 * missing/placeholder or it has no album, at most once per [RETRY_AFTER_MS], and a match is kept
 * only at [AutoTagScorer.ACCEPT] confidence or more. Cover art comes from the Cover Art Archive,
 * only for albums that have no cover on the device.
 */
class AutoTagger(
    context: Context,
    private val dao: TagOverrideDao,
    private val local: LocalMediaSource,
    private val musicBrainz: MusicBrainzClient,
) {
    private val attempts = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class RunResult(val looked: Int, val tagged: Int, val remaining: Int, val stoppedEarly: Boolean)

    /** Every override (auto and user), for screens that want to badge edited songs. */
    fun observeOverrides(): Flow<Map<String, TagOverrideEntity>> = dao.observeAll().map { rows -> rows.associateBy { it.trackId } }

    suspend fun override(trackId: TrackId): TagOverrideEntity? = runCatching { dao.get(trackId.value) }.getOrNull()

    /** On-device songs that still need a lookup now. */
    suspend fun pending(now: Long = System.currentTimeMillis()): List<LocalFileEntry> {
        if (!local.hasPermission()) return emptyList()
        val done = dao.all().map { it.trackId }.toHashSet()
        return local.rawEntries().filter { e ->
            val t = e.track
            t.id.value !in done && AutoTagQuery.needsTagging(t.title, t.artist, t.album) && !triedRecently(t.id.value, now)
        }
    }

    /**
     * Looks up to [limit] songs, one MusicBrainz request per second. [shouldStop] is checked between
     * songs (setting turned off, work stopped). Network trouble ends the run early; songs not
     * reached are tried on the next run.
     */
    suspend fun runBatch(limit: Int = BATCH_SIZE, shouldStop: () -> Boolean = { false }): RunResult {
        val now = System.currentTimeMillis()
        val pending = pending(now)
        var looked = 0
        var tagged = 0
        var stopped = false
        for (entry in pending.take(limit)) {
            if (shouldStop()) { stopped = true; break }
            val outcome = try {
                tagOne(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MusicBrainzClient.RateLimited) {
                stopped = true
                break
            } catch (e: Exception) {
                null
            }
            if (outcome == null) { stopped = true; break } // offline / server trouble: try again next run
            looked++
            markTried(entry.track.id.value, System.currentTimeMillis())
            if (outcome) tagged++
        }
        return RunResult(looked, tagged, (pending.size - looked).coerceAtLeast(0), stopped)
    }

    /** true = tagged, false = no confident match, null = MusicBrainz unreachable. */
    private suspend fun tagOne(entry: LocalFileEntry): Boolean? {
        val t = entry.track
        val hints = AutoTagQuery.hints(t.title, t.artist, entry.fileName)
        val results = musicBrainz.searchRecordings(AutoTagQuery.query(hints, t.durationMs)) ?: return null
        val match = withContext(Dispatchers.Default) {
            AutoTagScorer.best(hints, t.durationMs, results, albumHint = t.album)
        } ?: return false
        val cover = if (match.releaseId != null && !withContext(Dispatchers.IO) { local.hasAlbumArt(t.albumId) }) {
            musicBrainz.frontCover(match.releaseId!!)
        } else null
        dao.upsert(entity(t, hints.titleFromFileName, match, cover))
        return true
    }

    private fun entity(t: Track, titleFromFileName: Boolean, m: AutoTagMatch, cover: String?) = TagOverrideEntity(
        trackId = t.id.value,
        title = m.title.takeIf { titleFromFileName && !it.equals(t.title, ignoreCase = true) },
        artist = m.artist.takeIf { AutoTagQuery.isPlaceholderArtist(t.artist) && it.isNotBlank() },
        album = m.album?.takeIf { AutoTagQuery.isPlaceholderAlbum(t.album) && it.isNotBlank() },
        year = m.year?.takeIf { t.year == null },
        mbid = m.recordingId,
        artworkUrl = cover,
        source = SOURCE_MUSICBRAINZ,
        confidence = m.confidence,
        updatedAt = System.currentTimeMillis(),
    )

    /**
     * Saves the user's own title/artist/album for an on-device song (confidence 1, never replaced
     * by auto-tagging). A blank field falls back to the file's own value; when nothing is left to
     * override the row is removed.
     */
    suspend fun editTags(track: Track, title: String, artist: String, album: String) {
        if (track.source != SourceType.LOCAL) return
        val id = track.id.value
        val existing = runCatching { dao.get(id) }.getOrNull()
        val raw = runCatching { local.rawEntries().firstOrNull { it.track.id == track.id }?.track }.getOrNull()
        fun changed(value: String, original: String?): String? =
            value.trim().take(200).takeIf { it.isNotEmpty() && (original == null || it != original) }
        val newTitle = changed(title, raw?.title)
        val newArtist = changed(artist, raw?.artist)
        val newAlbum = changed(album, raw?.album)
        if (newTitle == null && newArtist == null && newAlbum == null && existing?.artworkUrl == null && existing?.year == null) {
            runCatching { dao.delete(id) }
            return
        }
        dao.upsert(
            TagOverrideEntity(
                trackId = id,
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                year = existing?.year,
                mbid = existing?.mbid,
                artworkUrl = existing?.artworkUrl,
                source = SOURCE_USER,
                confidence = 1f,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Drops any correction for [trackId] and lets auto-tagging try again. */
    suspend fun reset(trackId: TrackId) {
        runCatching { dao.delete(trackId.value) }
        attempts.edit().remove(trackId.value).apply()
    }

    private fun triedRecently(id: String, now: Long): Boolean = now - attempts.getLong(id, 0L) < RETRY_AFTER_MS

    private fun markTried(id: String, now: Long) {
        attempts.edit().putLong(id, now).apply()
    }

    companion object {
        const val SOURCE_MUSICBRAINZ = "musicbrainz"
        const val SOURCE_USER = "user"
        const val BATCH_SIZE = 40
        /** Songs without a confident match are looked up again after this long. */
        const val RETRY_AFTER_MS = 30L * 24 * 3_600_000
        private const val PREFS = "auto_tag_attempts"
    }
}
