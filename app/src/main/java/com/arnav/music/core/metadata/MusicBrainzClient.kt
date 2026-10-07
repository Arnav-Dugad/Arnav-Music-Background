package com.arnav.music.core.metadata

import android.os.SystemClock
import com.arnav.music.domain.metadata.MbRecording
import com.arnav.music.domain.metadata.MusicBrainzJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Free, keyless lookups in MusicBrainz (recording search) and the Cover Art Archive.
 *
 * MusicBrainz asks clients to identify themselves with a descriptive User-Agent and to stay at or
 * below one request per second; every call here waits for its turn ([MIN_INTERVAL_MS] apart,
 * across the whole app) and sends [userAgent].
 */
class MusicBrainzClient(
    private val http: OkHttpClient,
    private val userAgent: String,
) {
    private val gate = Mutex()
    private var lastRequestAt = 0L

    /** Thrown when MusicBrainz asks us to slow down (HTTP 503) — stop and retry later. */
    class RateLimited : Exception("MusicBrainz rate limit")

    /**
     * Recording search with a Lucene [query]. Returns null when MusicBrainz couldn't be reached (try
     * again later); an empty list when nothing matched. Throws [RateLimited] on HTTP 503.
     */
    suspend fun searchRecordings(query: String, limit: Int = 5): List<MbRecording>? {
        val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", limit.coerceIn(1, 25).toString())
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        return polite {
            withContext(Dispatchers.IO) {
                try {
                    http.newCall(request).execute().use { r ->
                        if (r.code == 503 || r.code == 429) throw RateLimited()
                        if (!r.isSuccessful) return@use null
                        val body = r.body?.string() ?: return@use null
                        MusicBrainzJson.recordings(body)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RateLimited) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * The release's front cover (250 px) from the Cover Art Archive, only when it actually exists
     * (the archive answers with a redirect to the image, or 404). Never throws.
     */
    suspend fun frontCover(releaseId: String): String? {
        if (!RELEASE_ID.matches(releaseId)) return null
        val url = "https://coverartarchive.org/release/$releaseId/front-250"
        val request = Request.Builder().url(url).head().header("User-Agent", userAgent).build()
        return polite {
            withContext(Dispatchers.IO) {
                try {
                    http.newCall(request).execute().use { r -> if (r.isSuccessful) url else null }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private suspend fun <T> polite(block: suspend () -> T): T = gate.withLock {
        val wait = lastRequestAt + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastRequestAt = SystemClock.elapsedRealtime()
        }
    }

    companion object {
        /** A little over one second, so clock jitter never pushes us past 1 request/s. */
        const val MIN_INTERVAL_MS = 1_100L
        private val RELEASE_ID = Regex("^[0-9a-fA-F-]{36}$")

        fun userAgent(versionName: String) = "ArnavMusic/$versionName (https://github.com/Arnav-Dugad/Arnav-Music)"
    }
}
