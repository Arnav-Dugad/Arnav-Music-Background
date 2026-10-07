package com.arnav.music.core.lyrics

import android.content.Context
import android.net.Uri
import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.ai.AiTranscription
import com.arnav.music.core.ai.AiUnavailableReason
import com.arnav.music.core.analysis.AudioDecoder
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.lyrics.TranscriptionAudio
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * "Arnav AI lyrics": transcribes lyrics with Gemini (through [AiGateway.transcribeLyrics]) for songs
 * that have none, and saves them as [LyricsRepository.SOURCE_AI].
 *
 * - On-device songs: the file itself is sent when it's a format Gemini reads and small enough
 *   ([TranscriptionAudio.MAX_INLINE_BYTES]); otherwise it's decoded here and sent as 16 kHz mono WAV,
 *   at most [TranscriptionAudio.MAX_SECONDS].
 * - YouTube songs: only the public watch URL is sent; Gemini fetches the video. Nothing is
 *   extracted or downloaded on the device.
 *
 * Automatic mode ([maybeAuto], setting "AI lyrics when none exist"): once per song, never while AI is
 * unavailable, at most [MAX_AUTO_PER_DAY] a day, not for songs whose lyrics the user removed, and a
 * song that failed is left alone for [FAILURE_TTL_MS]. Work runs in this object's own scope, so it
 * finishes (and saves) even if the lyrics view closes.
 */
class AiLyrics(
    private val context: Context,
    private val repo: LyricsRepository,
    private val ai: AiGateway,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
) {
    sealed interface Status {
        data object Working : Status
        data class Failed(val message: String) : Status
        data object Done : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ai_lyrics", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Per track id: generating, failed (with a message to show) or done. Absent = nothing happened. */
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()

    /** Songs tried automatically in this process (transient failures are retried next launch). */
    private val triedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** False when this build has no cloud AI at all (the button is then hidden). */
    val offered: Boolean get() = ai.availability() != AiUnavailableReason.NOT_CONFIGURED

    /** Whether the song can be sent at all (an on-device file, or a YouTube video id). */
    fun supports(track: Track): Boolean = when (track.source) {
        SourceType.LOCAL -> track.playbackRef.isNotBlank()
        SourceType.YOUTUBE -> VIDEO_ID.matches(track.playbackRef)
    }

    /** "Generate with Arnav AI". Shows a clear message instead when AI can't be used right now. */
    fun generate(track: Track) {
        val id = track.id.value
        if (_status.value[id] == Status.Working) return
        ai.availability()?.let { reason ->
            setStatus(id, Status.Failed(unavailableMessage(reason)))
            return
        }
        start(track, automatic = false)
    }

    /**
     * Automatic generation when the lyrics view shows "no lyrics" for [track]. Returns true when a
     * transcription was started.
     */
    suspend fun maybeAuto(track: Track): Boolean {
        val id = track.id.value
        if (!settings.settings.value.autoAiLyrics || !supports(track)) return false
        if (track.compilation) return false
        val duration = track.durationMs ?: return false
        if (duration < MIN_AUTO_MS || duration > MAX_AUTO_MS) return false
        if (ai.availability() != null) return false
        if (_status.value.containsKey(id) || id in triedThisSession) return false
        val failedAt = prefs.getLong(failKey(id), 0L)
        if (failedAt > 0L && clock.now() - failedAt < FAILURE_TTL_MS) return false
        if (autoCountToday() >= MAX_AUTO_PER_DAY) return false
        if (repo.isRemoved(track)) return false
        if (!triedThisSession.add(id)) return false
        bumpAutoCount()
        start(track, automatic = true)
        return true
    }

    /** Forgets a shown failure (e.g. when the user dismisses it or starts another action). */
    fun clear(track: Track) {
        val id = track.id.value
        _status.update { if (it[id] is Status.Failed) it - id else it }
    }

    private fun start(track: Track, automatic: Boolean) {
        val id = track.id.value
        setStatus(id, Status.Working)
        scope.launch {
            val result = try {
                run(track, automatic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AI lyrics failed", e)
                Status.Failed(GENERIC_FAILURE)
            } catch (e: OutOfMemoryError) {
                Status.Failed("This song is too large to send to Arnav AI.")
            }
            setStatus(id, result)
        }
    }

    private suspend fun run(track: Track, automatic: Boolean): Status {
        val id = track.id.value
        var audio: ByteArray? = null
        var mime: String? = null
        var url: String? = null
        when (track.source) {
            SourceType.LOCAL -> {
                val prepared = withContext(Dispatchers.IO) { prepareLocal(track) }
                if (prepared == null) {
                    rememberFailure(id)
                    return Status.Failed("Arnav AI couldn't read this song file.")
                }
                audio = prepared.first
                mime = prepared.second
            }
            SourceType.YOUTUBE -> url = "https://www.youtube.com/watch?v=${track.playbackRef}"
        }
        return when (val r = ai.transcribeLyrics(track, audio, mime, url)) {
            is AiTranscription.Ok -> {
                prefs.edit().remove(failKey(id)).apply()
                repo.saveAi(track, r.text, replaceRemoved = !automatic)
                Status.Done
            }
            AiTranscription.NoVocals -> {
                rememberFailure(id)
                Status.Failed("Arnav AI didn't hear any singing in this song.")
            }
            is AiTranscription.Rejected -> {
                rememberFailure(id)
                Status.Failed(rejectedMessage(r.reason, track.source))
            }
            is AiTranscription.Unavailable -> {
                if (r.reason == AiUnavailableReason.ERROR || r.reason == AiUnavailableReason.MALFORMED) rememberFailure(id)
                Status.Failed(unavailableMessage(r.reason))
            }
        }
    }

    /** Blocking. The song as Gemini-readable audio: the file itself when possible, else a WAV. */
    private fun prepareLocal(track: Track): Pair<ByteArray, String>? {
        val uri = try { Uri.parse(track.playbackRef) } catch (e: Exception) { return null }
        val resolver = context.contentResolver
        val maxMs = TranscriptionAudio.MAX_SECONDS * 1000L
        val shortEnough = (track.durationMs ?: 0L) <= maxMs + 15_000L
        if (shortEnough) {
            val size = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
            if (size <= TranscriptionAudio.MAX_INLINE_BYTES) {
                val bytes = try {
                    resolver.openInputStream(uri)?.use { readUpTo(it, TranscriptionAudio.MAX_INLINE_BYTES + 1) }
                } catch (e: Exception) {
                    null
                }
                if (bytes != null && bytes.size in 1..TranscriptionAudio.MAX_INLINE_BYTES) {
                    val mime = TranscriptionAudio.sniffMime(bytes)
                    if (mime != null) return bytes to mime
                }
            }
        }
        // Too large, too long or a container Gemini doesn't read: decode on the device.
        val decoded = AudioDecoder.decode(
            context, uri,
            maxSeconds = TranscriptionAudio.MAX_SECONDS,
            targetRate = TranscriptionAudio.WAV_RATE,
        ) ?: return null
        if (decoded.length < decoded.sampleRate * 5) return null
        return TranscriptionAudio.wav16(decoded.samples, decoded.length, decoded.sampleRate) to "audio/wav"
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val r = input.read(buf, 0, minOf(buf.size, limit - total))
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    private fun setStatus(id: String, s: Status) = _status.update { it + (id to s) }

    private fun rememberFailure(id: String) {
        prefs.edit().putLong(failKey(id), clock.now()).apply()
    }

    private fun failKey(id: String) = "fail:$id"

    private fun autoCountToday(): Int =
        if (prefs.getString(KEY_DAY, null) == clock.today()) prefs.getInt(KEY_COUNT, 0) else 0

    private fun bumpAutoCount() {
        val today = clock.today()
        val count = autoCountToday()
        prefs.edit().putString(KEY_DAY, today).putInt(KEY_COUNT, count + 1).apply()
    }

    companion object {
        const val PROGRESS_MESSAGE = "Listening to the song… this takes about 20 seconds"
        const val MAX_AUTO_PER_DAY = 15
        const val FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MIN_AUTO_MS = 30_000L
        /** Longer "songs" are usually mixes, sets or podcasts. */
        private const val MAX_AUTO_MS = 12L * 60 * 1000
        private const val KEY_DAY = "auto_day"
        private const val KEY_COUNT = "auto_count"
        private const val GENERIC_FAILURE = "Arnav AI couldn't write lyrics for this song."
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun unavailableMessage(reason: AiUnavailableReason): String = when (reason) {
            AiUnavailableReason.DISABLED_BY_USER -> "Turn on Cloud AI in Settings → Arnav AI to generate lyrics."
            AiUnavailableReason.NOT_CONFIGURED -> "Arnav AI isn't available in this version of the app."
            AiUnavailableReason.DAILY_LIMIT -> "Today's Arnav AI limit is used up. Try again tomorrow, or raise it in Settings → Arnav AI."
            AiUnavailableReason.THROTTLED -> "Arnav AI is busy with another request. Try again in a moment."
            AiUnavailableReason.QUOTA -> "Arnav AI is resting after a lot of requests. Try again in an hour."
            AiUnavailableReason.APP_CHECK -> "App Check blocked cloud AI on this install. Settings → Arnav AI → Cloud AI on this phone fixes it."
            AiUnavailableReason.OFFLINE -> "You're offline. Arnav AI needs a connection."
            AiUnavailableReason.TIMEOUT -> "Arnav AI took too long. Try again."
            AiUnavailableReason.MALFORMED, AiUnavailableReason.ERROR -> GENERIC_FAILURE
        }

        fun rejectedMessage(reason: String, source: SourceType): String = when (reason) {
            "recitation" -> "Gemini won't write out the lyrics of this song (its copyright filter stopped it)."
            "blocked" -> "Gemini declined to transcribe this song."
            "input" -> if (source == SourceType.YOUTUBE) "Gemini couldn't open this YouTube video." else "Gemini couldn't read this song's audio."
            "too long" -> "This song is too long for Arnav AI to transcribe."
            else -> "Arnav AI couldn't make out the lyrics of this song."
        }
    }
}
