package com.arnav.music.core.metadata

import android.content.Context
import android.net.Uri
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.youtube.YouTubeApi
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.lyrics.EmbeddedLyrics
import com.arnav.music.domain.metadata.AutoTagQuery
import com.arnav.music.domain.metadata.CreditEntry
import com.arnav.music.domain.metadata.CreditGroup
import com.arnav.music.domain.metadata.DescriptionCredits
import com.arnav.music.domain.metadata.EmbeddedCredits
import com.arnav.music.domain.metadata.TrackCredits
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.quota.YouTubeCosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** What the credits page shows. */
sealed interface CreditsState {
    data object Loading : CreditsState

    /** [fromDescription] is true for YouTube songs (credits read from the video description). */
    data class Ready(val credits: TrackCredits, val fromDescription: Boolean) : CreditsState

    /** Couldn't be read right now (offline, YouTube's daily allowance used up). */
    data object Unavailable : CreditsState
}

/**
 * Song credits.
 * - On-device files: read from the file's own tags ([EmbeddedCredits]), the same random-access way
 *   [com.arnav.music.core.lyrics.LyricsRepository] reads embedded lyrics.
 * - YouTube: parsed from the video description ([DescriptionCredits]). One `videos.list` call
 *   (1 quota unit, never a search) per video, made only while the quota is in its normal state;
 *   the parsed result (including "nothing") is cached on device by video id for [CACHE_TTL_MS].
 */
class CreditsRepository(
    private val context: Context,
    private val api: YouTubeApi,
    private val usage: UsageMeter,
    private val youtube: YouTubeRepository,
) {
    private val memory = object : LinkedHashMap<String, TrackCredits>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackCredits>?): Boolean = size > MEMORY_ENTRIES
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val youtubeLock = Mutex()

    /** Loads credits once (memory cache first). Never throws. */
    suspend fun load(track: Track): CreditsState {
        val key = track.id.value + "|" + track.playbackRef
        synchronized(memory) { memory[key] }?.let { return CreditsState.Ready(withArtist(it, track), track.source == SourceType.YOUTUBE) }
        val found: TrackCredits? = try {
            when (track.source) {
                SourceType.LOCAL -> withContext(Dispatchers.IO) { readLocal(track.playbackRef) }
                SourceType.YOUTUBE -> youtube(track)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (found == null) return CreditsState.Unavailable
        synchronized(memory) { memory[key] = found }
        return CreditsState.Ready(withArtist(found, track), track.source == SourceType.YOUTUBE)
    }

    /** The song's own artist heads "Performed by" when the tags/description name no performer. */
    private fun withArtist(c: TrackCredits, track: Track): TrackCredits {
        if (c.entries.any { it.group == CreditGroup.PERFORMED }) return c
        if (c.isEmpty || AutoTagQuery.isPlaceholderArtist(track.artist)) return c
        return TrackCredits(listOf(CreditEntry(CreditGroup.PERFORMED, "Artist", track.artist, person = true)) + c.entries)
    }

    // region on-device files

    private fun readLocal(ref: String): TrackCredits {
        val uri = try { Uri.parse(ref) } catch (e: Exception) { return TrackCredits.Empty }
        if (uri.scheme != "content" && uri.scheme != "file") return TrackCredits.Empty
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = channel.size()
                    if (size > 0) return EmbeddedCredits.fromSource(ChannelSource(channel, size))
                }
            }
        } catch (e: Exception) {
            // Fall through to a plain stream (non-seekable providers).
        } catch (e: OutOfMemoryError) {
            return TrackCredits.Empty
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { EmbeddedCredits.fromBytes(readHead(it, HEAD_BYTES)) } ?: TrackCredits.Empty
        } catch (e: Exception) {
            TrackCredits.Empty
        } catch (e: OutOfMemoryError) {
            TrackCredits.Empty
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

    /** null when the answer isn't known yet (offline, quota). */
    private suspend fun youtube(track: Track): TrackCredits? {
        val videoId = listOf(track.playbackRef, track.id.nativeId).firstOrNull { VIDEO_ID.matches(it) } ?: return TrackCredits.Empty
        return youtubeLock.withLock {
            readCache(videoId)?.let { return@withLock it }
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
            val credits = withContext(Dispatchers.Default) { DescriptionCredits.parse(description) }
            writeCache(videoId, credits)
            credits
        }
    }

    private suspend fun readCache(videoId: String): TrackCredits? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(videoId, null) ?: return@withContext null
        runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("t") > CACHE_TTL_MS) return@runCatching null
            val arr = o.optJSONArray("c") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                val group = CreditGroup.entries.firstOrNull { it.name == e.optString("g") } ?: return@mapNotNull null
                CreditEntry(group, e.optString("r"), e.optString("n"), e.optBoolean("p"))
            }
            TrackCredits(list)
        }.getOrNull()
    }

    private suspend fun writeCache(videoId: String, credits: TrackCredits) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            credits.entries.forEach { arr.put(JSONObject().put("g", it.group.name).put("r", it.role).put("n", it.name).put("p", it.person)) }
            val editor = prefs.edit().putString(videoId, JSONObject().put("t", System.currentTimeMillis()).put("c", arr).toString())
            val all = prefs.all
            if (all.size >= MAX_CACHED_VIDEOS) {
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
        const val CACHE_TTL_MS = 60L * 24 * 3_600_000
        private const val PREFS = "credits_youtube"
        private const val MAX_CACHED_VIDEOS = 600
        private const val MEMORY_ENTRIES = 48
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
