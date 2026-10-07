package com.arnav.music.core.chapters

import android.content.Context
import android.net.Uri
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.youtube.YouTubeApi
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.chapters.Chapter
import com.arnav.music.domain.chapters.DescriptionChapters
import com.arnav.music.domain.chapters.EmbeddedChapters
import com.arnav.music.domain.lyrics.EmbeddedLyrics
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.quota.YouTubeCosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Chapters for long tracks.
 * - On-device files: read from the file itself (ID3 CHAP, MP4 chapter track / Nero `chpl`, FLAC
 *   Vorbis CHAPTERxxx), the same random-access way [com.arnav.music.core.lyrics.LyricsRepository]
 *   reads embedded lyrics.
 * - YouTube: timestamps in the video description, only for videos of at least
 *   [YOUTUBE_MIN_DURATION_MS]. One `videos.list` call (1 quota unit) per video, made only while the
 *   quota is in its normal state; the result (including "no chapters") is cached on device by
 *   video id for [CACHE_TTL_MS].
 */
class ChapterRepository(
    private val context: Context,
    private val api: YouTubeApi,
    private val usage: UsageMeter,
    private val youtube: YouTubeRepository,
) {
    private val memory = object : LinkedHashMap<String, List<Chapter>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Chapter>>?): Boolean = size > MEMORY_ENTRIES
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val youtubeLock = Mutex()

    /**
     * Chapters of [track] (empty while loading and when there are none). [durationHintMs] is the
     * player's duration, used for YouTube tracks whose length isn't known from the catalogue.
     */
    fun observe(track: Track?, durationHintMs: Long? = null): Flow<List<Chapter>> = flow {
        if (track == null) {
            emit(emptyList())
            return@flow
        }
        val key = memoryKey(track)
        val known = synchronized(memory) { memory[key] }
        if (known != null) {
            emit(known)
            return@flow
        }
        emit(emptyList())
        emit(chapters(track, durationHintMs))
    }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    /** Loads chapters once (memory cache first). Never throws. */
    suspend fun chapters(track: Track, durationHintMs: Long? = null): List<Chapter> {
        val key = memoryKey(track)
        synchronized(memory) { memory[key] }?.let { return it }
        val result = try {
            when (track.source) {
                SourceType.LOCAL -> local(track)
                SourceType.YOUTUBE -> youtube(track, track.durationMs ?: durationHintMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // null = couldn't tell (offline, quota) → don't remember, try again next time.
        if (result != null) synchronized(memory) { memory[key] = result }
        return result.orEmpty()
    }

    private fun memoryKey(track: Track) = track.id.value + "|" + track.playbackRef

    // region on-device files

    private suspend fun local(track: Track): List<Chapter> = withContext(Dispatchers.IO) {
        readLocal(track.playbackRef, track.durationMs)
    }

    /** Blocking. Random access when possible (reaches an MP4 `moov` at the end), else the file head. */
    private fun readLocal(ref: String, durationMs: Long?): List<Chapter> {
        val uri = try { Uri.parse(ref) } catch (e: Exception) { return emptyList() }
        if (uri.scheme != "content" && uri.scheme != "file") return emptyList()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = channel.size()
                    if (size > 0) return EmbeddedChapters.fromSource(ChannelSource(channel, size), durationMs)
                }
            }
        } catch (e: Exception) {
            // Fall through to a plain stream (non-seekable providers).
        } catch (e: OutOfMemoryError) {
            return emptyList()
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { EmbeddedChapters.fromBytes(readHead(it, HEAD_BYTES), durationMs) }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        } catch (e: OutOfMemoryError) {
            emptyList()
        }
    }

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

    // endregion

    // region YouTube descriptions

    /** null when the answer isn't known yet (no duration, offline, quota); too short is a known "none". */
    private suspend fun youtube(track: Track, durationMs: Long?): List<Chapter>? {
        if (durationMs == null || durationMs <= 0) return null
        if (durationMs < YOUTUBE_MIN_DURATION_MS) return emptyList()
        val videoId = track.playbackRef.takeIf { VIDEO_ID.matches(it) } ?: return emptyList()
        return youtubeLock.withLock {
            readCache(videoId, durationMs)?.let { return@withLock it }
            if (youtube.quotaState() != QuotaState.NORMAL) return@withLock null
            val description = try {
                val response = api.videos(listOf(videoId))
                usage.youtubeCall(YouTubeCosts.VIDEOS_LIST, isSearch = false)
                response.items.firstOrNull { it.id == videoId }?.snippet?.description.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MusicError.QuotaExhausted) {
                usage.youtubeExhausted()
                return@withLock null
            } catch (e: Exception) {
                return@withLock null
            }
            val chapters = withContext(Dispatchers.Default) { DescriptionChapters.parse(description, durationMs) }
            writeCache(videoId, chapters)
            chapters
        }
    }

    private suspend fun readCache(videoId: String, durationMs: Long): List<Chapter>? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(videoId, null) ?: return@withContext null
        runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("t") > CACHE_TTL_MS) return@runCatching null
            val arr = o.optJSONArray("c") ?: JSONArray()
            val list = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Chapter(c.getLong("s"), c.optString("n"))
            }
            list.filter { it.startMs < durationMs }.takeIf { it.size >= 2 }.orEmpty()
        }.getOrNull()
    }

    private suspend fun writeCache(videoId: String, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            chapters.forEach { arr.put(JSONObject().put("s", it.startMs).put("n", it.title)) }
            val editor = prefs.edit().putString(videoId, JSONObject().put("t", System.currentTimeMillis()).put("c", arr).toString())
            val all = prefs.all
            if (all.size >= MAX_CACHED_VIDEOS) {
                // Drop the oldest quarter so the file stays small.
                all.entries
                    .map { (k, v) -> k to (runCatching { JSONObject(v as String).optLong("t") }.getOrDefault(0L)) }
                    .sortedBy { it.second }
                    .take(MAX_CACHED_VIDEOS / 4)
                    .forEach { (k, _) -> if (k != videoId) editor.remove(k) }
            }
            editor.apply()
        }
    }

    // endregion

    companion object {
        /** YouTube videos shorter than this are never checked for chapters (saves quota). */
        const val YOUTUBE_MIN_DURATION_MS = 15 * 60_000L
        const val CACHE_TTL_MS = 30L * 24 * 3_600_000

        private const val PREFS = "chapters_youtube"
        private const val MAX_CACHED_VIDEOS = 400
        private const val MEMORY_ENTRIES = 64
        private const val HEAD_BYTES = 2 * 1024 * 1024
        private const val MAX_READ = 16 * 1024 * 1024
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        private fun readHead(input: InputStream, limit: Int): ByteArray {
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
    }
}
