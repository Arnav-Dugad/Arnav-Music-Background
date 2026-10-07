package com.arnav.music.core.lyrics

import android.content.Context
import android.net.Uri
import com.arnav.music.core.analysis.VocalActivityStore
import com.arnav.music.core.db.AudioFeaturesDao
import com.arnav.music.core.db.LyricsDao
import com.arnav.music.core.db.LyricsEntity
import com.arnav.music.domain.lyrics.EmbeddedLyrics
import com.arnav.music.domain.lyrics.LrcParser
import com.arnav.music.domain.lyrics.LyricAligner
import com.arnav.music.domain.lyrics.LyricLine
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.lyrics.RatePacer
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/** Outcome of [LyricsRepository.fetchPack]. */
data class LyricsPackResult(
    val total: Int,
    /** Songs that have lyrics saved on the device now (including ones that already had them). */
    val found: Int,
    /** Songs whose lyrics were newly downloaded from LRCLIB. */
    val fetched: Int,
    /** Stopped asking LRCLIB after repeated network failures. */
    val offline: Boolean,
    /** Whether online lookups were allowed at all ("Online lyrics" on). */
    val onlineUsed: Boolean,
)

sealed interface LyricsState {
    data object Loading : LyricsState

    /**
     * [source] is one of the `SOURCE_*` constants; [raw] is the stored LRC/plain text (for editing).
     * [autoTimed]: the saved lyrics are plain and [lyrics] holds estimated timing ("Auto-timed").
     */
    data class Ready(val lyrics: Lyrics, val source: String, val raw: String = "", val autoTimed: Boolean = false) : LyricsState

    data object None : LyricsState
}

/**
 * Lyrics for a song, saved on this device. Sources, in order:
 * 1. lyrics embedded in the user's own local audio file (ID3 USLT/SYLT, FLAC Vorbis comment, MP4 ©lyr),
 * 2. an .lrc/.txt file the user picks with the system file picker, or text the user pastes,
 * 3. LRCLIB, the open community lyrics database (when "Online lyrics" is on): fetched once and saved,
 * 4. Arnav AI (Gemini) transcription, see [AiLyrics] (source [SOURCE_AI]).
 *
 * Plain (unsynced) lyrics are shown "Auto-timed" unless the user turned that off for the song:
 * [LyricAligner] estimates line timing from the song length and, for analysed on-device songs, the
 * vocal-activity curve ([VocalActivityStore]) and the intro/outro from [features].
 */
class LyricsRepository(
    private val context: Context,
    private val dao: LyricsDao,
    private val online: LrclibClient? = null,
    private val onlineEnabled: () -> Boolean = { false },
    private val features: AudioFeaturesDao? = null,
) {
    private val misses = context.getSharedPreferences("lyrics_online_misses", Context.MODE_PRIVATE)
    private val timingPrefs = context.getSharedPreferences("lyrics_auto_timing", Context.MODE_PRIVATE)
    private val vocals = VocalActivityStore(context)
    private val _autoTimingOff = MutableStateFlow(timingPrefs.getStringSet(KEY_AUTO_TIMING_OFF, emptySet())?.toSet().orEmpty())

    /** Songs (track ids) whose plain lyrics the user wants shown without estimated timing. */
    val autoTimingOff: StateFlow<Set<String>> = _autoTimingOff.asStateFlow()

    /** Last auto-timing result per song, so repeated emissions don't re-run the aligner. */
    private val alignCache = object : LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>?): Boolean = size > 8
    }
    /** Songs being looked up right now, so the player and the mini player don't both ask. */
    private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Tracks whose file was already scanned this process, so a file without lyrics is read once. */
    private val scanned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun observe(track: Track): Flow<LyricsState> = flow {
        emit(LyricsState.Loading)
        val id = track.id.value
        val saved = try { dao.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (saved == null && track.source == SourceType.LOCAL && scanned.add(id)) {
            scanEmbedded(track)
        }
        if (saved == null) fetchOnlineIfNeeded(track)
        emitAll(
            combine(dao.observe(id), autoTimingOff) { row, off -> row to (id !in off) }
                .distinctUntilChanged()
                .map { (row, autoTime) -> toState(row, track, autoTime) },
        )
    }
        .catch { emit(LyricsState.None) }
        .distinctUntilChanged()

    /**
     * Looks the song up on LRCLIB unless lyrics are already saved, the user removed them, the setting
     * is off, or LRCLIB recently had nothing (remembered for a week; instrumentals for 90 days).
     */
    private suspend fun fetchOnlineIfNeeded(track: Track, force: Boolean = false): Boolean =
        lookupOnline(track, force) == Lookup.SAVED

    private enum class Lookup { SAVED, MISSING, SKIPPED, FAILED }

    private suspend fun lookupOnline(track: Track, force: Boolean = false, pace: suspend () -> Unit = {}): Lookup {
        val client = online ?: return Lookup.SKIPPED
        if (!onlineEnabled()) return Lookup.SKIPPED
        val id = track.id.value
        val missedAt = misses.getLong(id, 0L)
        val missTtl = if (misses.getBoolean("$id#instrumental", false)) INSTRUMENTAL_TTL_MS else MISS_TTL_MS
        if (!force && missedAt > 0 && System.currentTimeMillis() - missedAt < missTtl) return Lookup.MISSING
        if (!inflight.add(id)) return Lookup.SKIPPED
        return try {
            if (!force && dao.get(id) != null) return Lookup.SKIPPED
            when (val found = client.find(track, pace)) {
                is OnlineLyrics.Found -> if (save(track, found.text, SOURCE_LRCLIB)) Lookup.SAVED else Lookup.MISSING
                OnlineLyrics.Instrumental -> { misses.edit().putLong(id, System.currentTimeMillis()).putBoolean("$id#instrumental", true).apply(); Lookup.MISSING }
                OnlineLyrics.NotFound -> { misses.edit().putLong(id, System.currentTimeMillis()).apply(); Lookup.MISSING }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or LRCLIB unreachable: try again next time, nothing remembered.
            Lookup.FAILED
        } finally {
            inflight.remove(id)
        }
    }

    /**
     * "Download lyrics" for a whole playlist/album: saves lyrics for every song in [tracks] that has
     * none yet — from the song file first (on-device songs), then LRCLIB — so they work offline.
     *
     * Songs with saved lyrics count as found; songs whose lyrics the user removed, and songs LRCLIB
     * recently had nothing for, are skipped. LRCLIB is asked at most ~4 times a second, nothing is
     * looked up online when "Online lyrics" is off, and the pack stops after a few network failures
     * in a row (offline). Cancelling the calling coroutine stops it after the current song.
     *
     * [onProgress] is called with (songs done, total, songs that now have lyrics), first with
     * (0, total, 0) and after every song.
     */
    suspend fun fetchPack(
        tracks: List<Track>,
        onProgress: (done: Int, total: Int, found: Int) -> Unit,
    ): LyricsPackResult {
        val unique = tracks.distinctBy { it.id }
        val total = unique.size
        var done = 0
        var found = 0
        var fetched = 0
        var failuresInRow = 0
        var offline = false
        val pacer = RatePacer(PACK_REQUEST_INTERVAL_MS)
        val pace: suspend () -> Unit = {
            val wait = synchronized(pacer) { pacer.acquire(System.currentTimeMillis()) }
            if (wait > 0) delay(wait)
        }
        onProgress(0, total, 0)
        for (track in unique) {
            currentCoroutineContext().ensureActive()
            val id = track.id.value
            val saved = try { dao.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val has = when {
                saved != null -> saved.source != SOURCE_REMOVED && saved.text.isNotBlank()
                track.source == SourceType.LOCAL && scanned.add(id) && scanEmbedded(track) -> true
                offline -> false
                else -> when (lookupOnline(track, pace = pace)) {
                    Lookup.SAVED -> { fetched++; failuresInRow = 0; true }
                    Lookup.FAILED -> {
                        failuresInRow++
                        if (failuresInRow >= PACK_MAX_FAILURES_IN_ROW) offline = true
                        false
                    }
                    Lookup.MISSING -> { failuresInRow = 0; false }
                    Lookup.SKIPPED -> false
                }
            }
            if (has) found++
            done++
            onProgress(done, total, found)
        }
        return LyricsPackResult(total = total, found = found, fetched = fetched, offline = offline, onlineUsed = onlineAvailable)
    }

    /** "Search online again": clears a removal or a remembered miss and asks LRCLIB now. */
    suspend fun searchOnline(track: Track): Boolean {
        val id = track.id.value
        misses.edit().remove(id).remove("$id#instrumental").apply()
        val existing = runCatching { dao.get(id) }.getOrNull()
        if (existing != null && existing.source == SOURCE_REMOVED) runCatching { dao.delete(id) }
        return fetchOnlineIfNeeded(track, force = true)
    }

    /** Whether online lookups are available and switched on. */
    val onlineAvailable: Boolean get() = online != null && onlineEnabled()

    /** Reads lyrics from the song file again (e.g. after the user removed them by mistake). */
    suspend fun rescanEmbedded(track: Track): Boolean {
        if (track.source != SourceType.LOCAL) return false
        scanned.add(track.id.value)
        return scanEmbedded(track, force = true)
    }

    /** Imports an .lrc/.txt document the user picked. False when unreadable or not lyrics. */
    suspend fun importFile(track: Track, uri: Uri): Boolean {
        val text = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { readText(it, MAX_IMPORT_BYTES) }
            } catch (e: Exception) {
                null
            }
        } ?: return false
        return save(track, text, SOURCE_FILE)
    }

    /** Saves pasted LRC or plain text. False when there is nothing usable. */
    suspend fun savePasted(track: Track, text: String): Boolean = save(track, text, SOURCE_PASTED)

    /**
     * Removes saved lyrics. For local files a blank marker row is kept so lyrics embedded in the
     * file don't silently come back; [rescanEmbedded] restores them.
     */
    suspend fun remove(track: Track) {
        val id = track.id.value
        try {
            // A marker row (not a delete) so neither the file nor LRCLIB brings them back by itself.
            dao.upsert(LyricsEntity(id, "", synced = false, source = SOURCE_REMOVED, updatedAt = System.currentTimeMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing sensible to surface; the row simply stays.
        }
    }

    private suspend fun save(track: Track, raw: String, source: String): Boolean {
        val text = normalize(raw).take(MAX_TEXT_CHARS)
        val parsed = LrcParser.parse(text, track.durationMs) ?: return false
        return try {
            dao.upsert(LyricsEntity(track.id.value, text, synced = parsed is Lyrics.Synced, source = source, updatedAt = System.currentTimeMillis()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun scanEmbedded(track: Track, force: Boolean = false): Boolean {
        val text = withContext(Dispatchers.IO) { readEmbedded(track.playbackRef) } ?: return false
        val parsed = LrcParser.parse(text, track.durationMs) ?: return false
        return try {
            val id = track.id.value
            // A manual import/paste that landed while we were reading wins.
            val existing = dao.get(id)
            if (!force && existing != null) return false
            if (force && existing != null && existing.source != SOURCE_REMOVED && existing.text.isNotBlank()) return false
            dao.upsert(LyricsEntity(id, text, synced = parsed is Lyrics.Synced, source = SOURCE_EMBEDDED, updatedAt = System.currentTimeMillis()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun toState(entity: LyricsEntity?, track: Track, autoTime: Boolean): LyricsState {
        if (entity == null || entity.text.isBlank()) return LyricsState.None
        val parsed = LrcParser.parse(entity.text, track.durationMs) ?: return LyricsState.None
        if (parsed is Lyrics.Plain && autoTime) {
            val timed = autoTimed(track, entity.text, parsed)
            if (timed != null) return LyricsState.Ready(Lyrics.Synced(timed), entity.source, entity.text, autoTimed = true)
        }
        return LyricsState.Ready(parsed, entity.source, entity.text)
    }

    /** Estimated timing for plain lyrics; null when the song length is unknown or too short. */
    private suspend fun autoTimed(track: Track, text: String, plain: Lyrics.Plain): List<LyricLine>? {
        val duration = track.durationMs?.takeIf { it >= MIN_AUTO_TIMING_MS } ?: return null
        val key = "${track.id.value}|$duration|${text.hashCode()}"
        synchronized(alignCache) { alignCache[key] }?.let { return it }
        val lines = withContext(Dispatchers.IO) {
            val id = track.id.value
            val activity = if (track.source == SourceType.LOCAL) vocals.read(id) else null
            val row = if (track.source == SourceType.LOCAL) {
                try { features?.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            } else null
            val ok = row?.takeIf { it.ok }
            withContext(Dispatchers.Default) {
                LyricAligner.align(
                    lines = plain.lines,
                    durationMs = duration,
                    activity = activity?.values,
                    stepMs = activity?.stepMs ?: LyricAligner.DEFAULT_STEP_MS,
                    introMs = ok?.introMs ?: 0L,
                    outroMs = ok?.outroMs ?: 0L,
                )
            }
        }.takeIf { it.isNotEmpty() } ?: return null
        synchronized(alignCache) { alignCache[key] = lines }
        return lines
    }

    /** Turns estimated timing of plain lyrics on or off for [track] ("Turn off auto-timing"). */
    fun setAutoTiming(track: Track, enabled: Boolean) {
        val id = track.id.value
        val next = if (enabled) _autoTimingOff.value - id else _autoTimingOff.value + id
        _autoTimingOff.value = next
        timingPrefs.edit().putStringSet(KEY_AUTO_TIMING_OFF, next).apply()
    }

    /**
     * "Save timing" after Adjust timing: stores [lrc] as synced lyrics. The source stays what it was
     * (lyrics from LRCLIB stay "From LRCLIB", Arnav AI lyrics stay labelled as AI).
     */
    suspend fun saveTiming(track: Track, lrc: String): Boolean {
        val existing = try { dao.get(track.id.value) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val source = existing?.source?.takeIf { it != SOURCE_REMOVED } ?: SOURCE_PASTED
        return save(track, lrc, source)
    }

    /** True when the user removed this song's lyrics (nothing should bring them back by itself). */
    suspend fun isRemoved(track: Track): Boolean =
        try { dao.get(track.id.value)?.source == SOURCE_REMOVED } catch (e: CancellationException) { throw e } catch (e: Exception) { false }

    /**
     * Saves lyrics transcribed by Arnav AI unless other lyrics arrived meanwhile (a paste, LRCLIB…).
     * A "removed" marker is replaced only when [replaceRemoved] (the user asked for AI lyrics).
     */
    suspend fun saveAi(track: Track, text: String, replaceRemoved: Boolean): Boolean {
        val existing = try { dao.get(track.id.value) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (existing != null) {
            val removed = existing.source == SOURCE_REMOVED || existing.text.isBlank()
            if (!removed || !replaceRemoved) return false
        }
        return save(track, text, SOURCE_AI)
    }

    /** Blocking; call on IO. Returns lyrics text embedded in the local file, or null. */
    private fun readEmbedded(ref: String): String? {
        val uri = try { Uri.parse(ref) } catch (e: Exception) { return null }
        if (uri.scheme != "content" && uri.scheme != "file") return null
        // Random access when possible: lets us skip artwork and reach an MP4 `moov` at the end.
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = channel.size()
                    if (size > 0) return EmbeddedLyrics.fromSource(ChannelSource(channel, size))
                }
            }
        } catch (e: Exception) {
            // Fall through to a plain stream (non-seekable providers).
        } catch (e: OutOfMemoryError) {
            return null
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                EmbeddedLyrics.fromBytes(readBytes(input, HEAD_BYTES))
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Positional reads over a file channel. Reads are capped by [EmbeddedLyrics] itself. */
    private class ChannelSource(private val channel: FileChannel, override val length: Long) : EmbeddedLyrics.Source {
        override fun read(position: Long, size: Int): ByteArray {
            if (position < 0 || size <= 0 || position >= length) return ByteArray(0)
            val n = minOf(size.toLong(), length - position, MAX_READ.toLong()).toInt()
            val buffer = ByteBuffer.allocate(n)
            var at = position
            while (buffer.hasRemaining()) {
                val r = channel.read(buffer, at)
                if (r <= 0) break
                at += r
            }
            val got = buffer.position()
            return if (got == n) buffer.array() else buffer.array().copyOf(got)
        }
    }

    companion object {
        const val SOURCE_EMBEDDED = "embedded"
        const val SOURCE_FILE = "file"
        const val SOURCE_PASTED = "pasted"
        /** Fetched from LRCLIB (community lyrics) and saved on the device. */
        const val SOURCE_LRCLIB = "lrclib"
        /** Transcribed from the song by Arnav AI (Gemini). May contain mistakes; editable like any lyrics. */
        const val SOURCE_AI = "ai"
        private const val KEY_AUTO_TIMING_OFF = "off"
        private const val MIN_AUTO_TIMING_MS = 20_000L
        /** ~4 LRCLIB requests a second at most while downloading a lyrics pack. */
        private const val PACK_REQUEST_INTERVAL_MS = 250L
        private const val PACK_MAX_FAILURES_IN_ROW = 3
        private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val INSTRUMENTAL_TTL_MS = 90L * 24 * 60 * 60 * 1000
        /** Marker row: the user removed lyrics for a local file. Rendered as no lyrics. */
        const val SOURCE_REMOVED = "removed"

        private const val HEAD_BYTES = 2 * 1024 * 1024
        private const val MAX_READ = 16 * 1024 * 1024
        private const val MAX_IMPORT_BYTES = 512 * 1024
        private const val MAX_TEXT_CHARS = 200_000

        private fun readBytes(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < limit) {
                val r = input.read(buf, 0, minOf(buf.size, limit - total))
                if (r < 0) break
                out.write(buf, 0, r)
                total += r
            }
            return out.toByteArray()
        }

        /** Text up to [limit] bytes: UTF-8 (BOM stripped), or UTF-16 when it carries a BOM. */
        private fun readText(input: InputStream, limit: Int): String {
            val b = readBytes(input, limit)
            if (b.size >= 2) {
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                if (b0 == 0xFF && b1 == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
                if (b0 == 0xFE && b1 == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            }
            if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF) {
                return String(b, 3, b.size - 3, Charsets.UTF_8)
            }
            return String(b, Charsets.UTF_8)
        }

        private fun normalize(raw: String): String =
            raw.removePrefix("﻿").replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim()
    }
}
