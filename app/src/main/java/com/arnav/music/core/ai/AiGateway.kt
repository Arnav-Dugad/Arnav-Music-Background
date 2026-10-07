package com.arnav.music.core.ai

import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.db.AiCacheDao
import com.arnav.music.core.db.AiCacheEntity
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.firebase.FirebaseGate
import com.arnav.music.core.firebase.RemoteConfigRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.lyrics.TranscriptionCheck
import com.arnav.music.domain.lyrics.TranscriptionVerdict
import com.arnav.music.domain.model.Track
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class AiUnavailableReason { DISABLED_BY_USER, NOT_CONFIGURED, DAILY_LIMIT, THROTTLED, QUOTA, APP_CHECK, OFFLINE, TIMEOUT, MALFORMED, ERROR }

sealed interface AiOutcome {
    data class Ok(val text: String, val cached: Boolean) : AiOutcome
    data class Unavailable(val reason: AiUnavailableReason) : AiOutcome
}

/** Result of [AiGateway.transcribeLyrics]. */
sealed interface AiTranscription {
    /** Validated lyrics: LRC when [synced], otherwise plain lines (auto-timing fills in the timing). */
    data class Ok(val text: String, val synced: Boolean, val model: String) : AiTranscription

    /** Gemini heard no sung vocals. */
    data object NoVocals : AiTranscription

    /**
     * Gemini answered, but not with usable lyrics. [reason]: "recitation" (blocked by Gemini's
     * copyright/recitation check), "blocked" (safety), "input" (the audio/video wasn't accepted),
     * "too long", "refusal", "description", "repetitive", "too short"…
     */
    data class Rejected(val reason: String) : AiTranscription

    data class Unavailable(val reason: AiUnavailableReason) : AiTranscription
}

/**
 * The only door to Gemini (Firebase AI Logic, Gemini Developer API free tier).
 * Guarantees: user consent, daily cap, min interval throttle, response cache keyed by prompt
 * version, timeout, no retries on quota errors. Callers always have a local fallback.
 */
class AiGateway(
    private val gate: FirebaseGate,
    private val remote: RemoteConfigRepository,
    private val settings: SettingsRepository,
    private val cache: AiCacheDao,
    private val usage: UsageMeter,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    @Volatile private var lastCallAt = 0L
    @Volatile private var quotaBlockedUntil = 0L
    @Volatile private var appCheckBlockedUntil = 0L
    private val transcribing = AtomicBoolean(false)
    /** Last Gemini failure, shown in Settings → Arnav AI for diagnosis (no prompts or user data). */
    @Volatile var lastError: String? = null
        private set

    /** Forgets App Check / quota back-off, e.g. after the App Check setup changed. */
    fun clearBackoff() {
        appCheckBlockedUntil = 0L
        quotaBlockedUntil = 0L
        lastError = null
    }

    fun availability(): AiUnavailableReason? {
        val s = settings.settings.value
        return when {
            !s.aiEnabled -> AiUnavailableReason.DISABLED_BY_USER
            !gate.isAvailable || !remote.tunables.value.aiEnabled -> AiUnavailableReason.NOT_CONFIGURED
            usage.state.value.aiRequests >= s.dailyAiLimit -> AiUnavailableReason.DAILY_LIMIT
            clock.now() < quotaBlockedUntil -> AiUnavailableReason.QUOTA
            clock.now() < appCheckBlockedUntil -> AiUnavailableReason.APP_CHECK
            else -> null
        }
    }

    suspend fun generate(prompt: String, promptVersion: String, json: Boolean, cacheTtlMs: Long = 7L * 86_400_000): AiOutcome {
        val key = sha256(promptVersion + "\n" + prompt)
        cache.get(key, promptVersion)?.let { hit ->
            if (clock.now() - hit.createdAt < cacheTtlMs) {
                usage.aiCacheHit()
                return AiOutcome.Ok(hit.response, cached = true)
            }
        }
        availability()?.let { return AiOutcome.Unavailable(it) }
        val t = remote.tunables.value

        return mutex.withLock {
            if (clock.now() - lastCallAt < t.aiMinIntervalMs) return@withLock AiOutcome.Unavailable(AiUnavailableReason.THROTTLED)
            lastCallAt = clock.now()
            usage.aiRequest()
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = t.aiModel,
                    generationConfig = generationConfig {
                        temperature = t.aiTemperature
                        maxOutputTokens = t.aiMaxOutputTokens
                        if (json) responseMimeType = "application/json"
                    },
                )
                val text = withTimeout(t.aiTimeoutMs) { model.generateContent(prompt).text }
                if (text.isNullOrBlank()) AiOutcome.Unavailable(AiUnavailableReason.MALFORMED)
                else {
                    cache.put(AiCacheEntity(key, promptVersion, text, clock.now()))
                    AiOutcome.Ok(text, cached = false)
                }
            } catch (e: TimeoutCancellationException) {
                AiOutcome.Unavailable(AiUnavailableReason.TIMEOUT)
            } catch (e: Exception) {
                AiOutcome.Unavailable(classifyFailure(e))
            }
        }
    }

    /** Records [e] as [lastError] and maps it to a reason, backing off on App Check and quota errors. */
    private fun classifyFailure(e: Exception): AiUnavailableReason {
        val msg = (e.message ?: "").lowercase()
        lastError = "${e.javaClass.simpleName}: ${e.message?.take(160) ?: "no message"}"
        Log.w("AI call failed", e)
        return if ("app check" in msg || "appcheck" in msg || "attestation" in msg || "app-check" in msg) {
            // Enforced App Check rejected this install (e.g. sideloaded APK + Play Integrity).
            // Stop trying for 6 hours; the on-device engine answers meanwhile.
            appCheckBlockedUntil = clock.now() + 6 * 60 * 60_000L
            AiUnavailableReason.APP_CHECK
        } else if ("quota" in msg || "429" in msg || "resource_exhausted" in msg || "rate" in msg) {
            // Back off for an hour; never hammer the free tier.
            quotaBlockedUntil = clock.now() + 60 * 60_000L
            AiUnavailableReason.QUOTA
        } else if ("unable to resolve" in msg || "network" in msg || "timeout" in msg) {
            AiUnavailableReason.OFFLINE
        } else AiUnavailableReason.ERROR
    }

    /**
     * Arnav AI lyrics: asks Gemini to transcribe the sung lyrics of [track] as LRC, from the song's
     * own [audio] bytes (inline, [mimeType] e.g. "audio/mpeg") or, for YouTube songs, the public
     * [youtubeUrl] (`https://www.youtube.com/watch?v=…`, passed as file data; Gemini fetches the
     * video itself — nothing is downloaded or extracted on the device).
     *
     * Same rules as [generate]: user consent, daily cap (each model attempt counts as a request),
     * minimum interval, App Check/quota back-off, [lastError]. One transcription at a time; the
     * shared lock is held only for the pacing check, not during the long call. The answer is
     * validated with [TranscriptionCheck] (refusals and descriptions are rejected). When the
     * configured model rejects the input, `gemini-2.5-flash` is tried once.
     */
    suspend fun transcribeLyrics(track: Track, audio: ByteArray?, mimeType: String?, youtubeUrl: String?): AiTranscription {
        val inlineAudio = audio?.takeIf { it.isNotEmpty() && !mimeType.isNullOrBlank() }
        val videoUrl = youtubeUrl?.takeIf { YOUTUBE_WATCH_URL.matches(it) }
        if (inlineAudio == null && videoUrl == null) return AiTranscription.Rejected("input")
        availability()?.let { return AiTranscription.Unavailable(it) }
        if (!transcribing.compareAndSet(false, true)) return AiTranscription.Unavailable(AiUnavailableReason.THROTTLED)
        try {
            val t = remote.tunables.value
            val throttled = mutex.withLock {
                if (clock.now() - lastCallAt < t.aiMinIntervalMs) true else { lastCallAt = clock.now(); false }
            }
            if (throttled) return AiTranscription.Unavailable(AiUnavailableReason.THROTTLED)

            val prompt = content {
                // Media first, then the instruction (Gemini's recommended order).
                if (inlineAudio != null) inlineData(inlineAudio, mimeType!!) else fileData(videoUrl!!, YOUTUBE_MIME)
                text(transcriptionPrompt(track))
            }
            val models = listOf(t.aiModel, TRANSCRIBE_FALLBACK_MODEL).distinct()
            for ((attempt, modelName) in models.withIndex()) {
                if (attempt > 0) {
                    availability()?.let { return AiTranscription.Unavailable(it) }
                    lastCallAt = clock.now()
                }
                usage.aiRequest()
                val raw = try {
                    callTranscription(modelName, prompt)
                } catch (e: TimeoutCancellationException) {
                    return AiTranscription.Unavailable(AiUnavailableReason.TIMEOUT)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = (e.message ?: "").lowercase()
                    when {
                        "recitation" in msg -> { lastError = "Transcription blocked (recitation)"; return AiTranscription.Rejected("recitation") }
                        "max_tokens" in msg -> return AiTranscription.Rejected("too long")
                        "safety" in msg || "prohibited" in msg || "prompt was blocked" in msg -> { lastError = "Transcription blocked (safety)"; return AiTranscription.Rejected("blocked") }
                        "api key" in msg || "app check" in msg || "appcheck" in msg || "quota" in msg || "429" in msg ->
                            return AiTranscription.Unavailable(classifyFailure(e))
                        rejectsInput(msg) -> {
                            lastError = "${e.javaClass.simpleName}: ${e.message?.take(160) ?: "no message"}"
                            Log.w("AI transcription input rejected by $modelName", e)
                            if (attempt < models.lastIndex) continue
                            return AiTranscription.Rejected("input")
                        }
                        else -> return AiTranscription.Unavailable(classifyFailure(e))
                    }
                }
                if (raw.isNullOrBlank()) return AiTranscription.Unavailable(AiUnavailableReason.MALFORMED)
                return when (val v = TranscriptionCheck.check(raw, track.durationMs)) {
                    is TranscriptionVerdict.Accepted -> AiTranscription.Ok(v.text, v.synced, modelName)
                    TranscriptionVerdict.NoVocals -> AiTranscription.NoVocals
                    is TranscriptionVerdict.Rejected -> AiTranscription.Rejected(v.reason)
                }
            }
            return AiTranscription.Rejected("input")
        } finally {
            transcribing.set(false)
        }
    }

    private suspend fun callTranscription(modelName: String, prompt: Content): String? {
        // Transcription needs no reasoning; 2.5 Flash models think by default, which costs time and tokens.
        val noThinking = if (modelName.startsWith("gemini-2.5-flash")) thinkingConfig { thinkingBudget = 0 } else null
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = TRANSCRIBE_MAX_OUTPUT_TOKENS
                if (noThinking != null) thinkingConfig = noThinking
            },
        )
        return withTimeout(TRANSCRIBE_TIMEOUT_MS) { model.generateContent(prompt).text }
    }

    private fun rejectsInput(msg: String): Boolean =
        "mime" in msg || "unsupported" in msg || "not supported" in msg || "invalid_argument" in msg ||
            "invalid argument" in msg || "file_uri" in msg || "fileuri" in msg || "cannot fetch" in msg ||
            "payload size" in msg || "too large" in msg

    private fun transcriptionPrompt(track: Track): String = buildString {
        append("Transcribe the sung lyrics of this recording exactly as they are sung.\n")
        append("It is probably \"").append(track.title.take(120)).append("\" by ").append(track.artist.take(80))
        append(". Use that only to spell names; write what is actually sung.\n\n")
        append("Rules:\n")
        append("- Output only LRC lines: one sung line per line, each starting with the time it starts being sung, as [mm:ss.xx].\n")
        append("- Keep the original language and script of every line. Do not translate or romanise.\n")
        append("- Put an empty line between sections (verse, chorus, bridge). Repeat repeated lines every time they are sung.\n")
        append("- For a long passage without singing you may write a line like [01:23.00] [instrumental].\n")
        append("- No title, no section names, no notes, no explanations, no markdown.\n")
        append("- If nobody sings in this recording, output exactly: ").append(TranscriptionCheck.NO_VOCALS).append('\n')
    }

    suspend fun clearCache() = cache.clear()

    /** Counted when a request had to be answered by the on-device engine. */
    fun noteFallback() = usage.aiFallback()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Used when the configured model (Flash-Lite by default) rejects audio/video input. */
        const val TRANSCRIBE_FALLBACK_MODEL = "gemini-2.5-flash"
        const val TRANSCRIBE_MAX_OUTPUT_TOKENS = 8_192
        const val TRANSCRIBE_TIMEOUT_MS = 120_000L
        /** MIME type Firebase AI Logic documents for YouTube URLs given as file data. */
        const val YOUTUBE_MIME = "video/mp4"
        val YOUTUBE_WATCH_URL = Regex("""^https://www\.youtube\.com/watch\?v=[A-Za-z0-9_-]{11}$""")
    }
}
