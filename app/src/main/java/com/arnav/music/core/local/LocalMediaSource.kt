package com.arnav.music.core.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.TagOverrideDao
import com.arnav.music.core.db.TagOverrideEntity
import com.arnav.music.domain.library.Albums
import com.arnav.music.domain.model.PlaybackCapabilities
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.provider.LibraryProvider
import com.arnav.music.domain.provider.MusicCatalogProvider
import com.arnav.music.domain.provider.PlaybackProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/** One on-device song as MediaStore has it (before tag overrides), with its file name. */
data class LocalFileEntry(val track: Track, val fileName: String?)

/**
 * User-owned audio from MediaStore — the fully controllable, background-capable source.
 *
 * Corrected metadata (MusicBrainz auto-tags and the user's own edits, see
 * [com.arnav.music.core.metadata.AutoTagger]) is applied on top of MediaStore's values; the audio
 * files themselves are never modified. [overrideDao] defaults to the app database from Koin.
 */
class LocalMediaSource(
    private val context: Context,
    private val overrideDao: TagOverrideDao? = null,
) : LibraryProvider, MusicCatalogProvider, PlaybackProvider {
    override val source = SourceType.LOCAL

    private val overrides: TagOverrideDao? by lazy {
        overrideDao ?: runCatching { GlobalContext.get().get<ArnavDatabase>().tagOverrides() }.getOrNull()
    }

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun capabilities(track: Track) = PlaybackCapabilities.LocalMedia

    /** Re-queries whenever MediaStore or a tag override changes; emits empty when permission is missing. */
    override fun tracks(): Flow<List<Track>> {
        val raw = rawTracks()
        val dao = overrides ?: return raw
        val overrideFlow = dao.observeAll().catch { emit(emptyList()) }
        return combine(raw, overrideFlow) { tracks, rows -> applyOverrides(tracks, rows) }.flowOn(Dispatchers.Default)
    }

    /** MediaStore's own values, without tag overrides. */
    fun rawTracks(): Flow<List<Track>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        trySend(Unit)
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().map { query().map { it.track } }.flowOn(Dispatchers.IO)

    override suspend fun track(id: TrackId): Track? = withContext(Dispatchers.IO) {
        val raw = id.nativeId.toLongOrNull()?.let { query(selectionId = it).firstOrNull()?.track } ?: return@withContext null
        val row = runCatching { overrides?.get(id.value) }.getOrNull()
        raw.withOverride(row)
    }

    /** Every on-device song as MediaStore has it, with its file name (for the auto-tagger). */
    suspend fun rawEntries(): List<LocalFileEntry> = withContext(Dispatchers.IO) { query() }

    /**
     * True when MediaStore has a cover for [albumId] (embedded in a file or a folder image). Blocking
     * I/O; call off the main thread.
     */
    fun hasAlbumArt(albumId: String?): Boolean {
        val id = albumId?.toLongOrNull() ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id)
                context.contentResolver.loadThumbnail(uri, Size(96, 96), null).recycle()
                true
            } else {
                context.contentResolver.openFileDescriptor(ContentUris.withAppendedId(ALBUM_ART, id), "r")?.use { true } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun query(selectionId: Long? = null): List<LocalFileEntry> {
        if (!hasPermission()) return emptyList()
        val modern = Build.VERSION.SDK_INT >= 30
        val projection = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM); add(MediaStore.Audio.Media.ALBUM_ID); add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.YEAR); add(MediaStore.Audio.Media.TRACK); add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (modern) { add(MediaStore.Audio.Media.DISC_NUMBER); add(MediaStore.Audio.Media.ALBUM_ARTIST) }
        }.toTypedArray()
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000")
            if (selectionId != null) append(" AND ${MediaStore.Audio.Media._ID} = $selectionId")
        }
        val out = ArrayList<LocalFileEntry>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val discCol = if (modern) c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else -1
                val albumArtistCol = if (modern) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val albumId = c.getLong(albumIdCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val artist = c.getString(artistCol)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
                    val rawTrack = if (trackCol >= 0 && !c.isNull(trackCol)) c.getInt(trackCol) else null
                    val discTag = if (discCol >= 0 && !c.isNull(discCol)) Albums.parseNumber(c.getString(discCol)) else null
                    val (disc, number) = Albums.decodeTrackNumber(rawTrack, discTag)
                    val albumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol)?.takeUnless { it.isBlank() || it == "<unknown>" } else null
                    val track = Track(
                        id = TrackId.local(id),
                        title = c.getString(titleCol)?.ifBlank { null } ?: "Untitled",
                        artist = artist,
                        album = c.getString(albumCol)?.takeUnless { it.isBlank() || it == "<unknown>" },
                        durationMs = c.getLong(durCol).takeIf { it > 0 },
                        artworkUrl = ContentUris.withAppendedId(ALBUM_ART, albumId).toString(),
                        playbackRef = uri.toString(),
                        year = c.getInt(yearCol).takeIf { it in 1900..2100 },
                        trackNumber = number,
                        discNumber = disc,
                        albumId = albumId.takeIf { it > 0 }?.toString(),
                        albumArtist = albumArtist,
                    )
                    out += LocalFileEntry(track, if (nameCol >= 0) c.getString(nameCol) else null)
                }
            }
        }
        return out
    }

    companion object {
        private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

        /** Applies tag overrides (by track id) to on-device songs. */
        fun applyOverrides(tracks: List<Track>, rows: List<TagOverrideEntity>): List<Track> {
            if (rows.isEmpty()) return tracks
            val byId = rows.associateBy { it.trackId }
            return tracks.map { t -> t.withOverride(byId[t.id.value]) }
        }

        private fun Track.withOverride(o: TagOverrideEntity?): Track = if (o == null) this else copy(
            title = o.title?.takeIf { it.isNotBlank() } ?: title,
            artist = o.artist?.takeIf { it.isNotBlank() } ?: artist,
            album = o.album?.takeIf { it.isNotBlank() } ?: album,
            year = o.year ?: year,
            artworkUrl = o.artworkUrl?.takeIf { it.isNotBlank() } ?: artworkUrl,
        )
    }
}
