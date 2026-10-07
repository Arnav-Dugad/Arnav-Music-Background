package com.arnav.music.core.lyrics

import com.arnav.music.domain.catalog.TrackClassifier
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

@Serializable
internal data class LrclibRecord(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** What LRCLIB had for a song. */
sealed interface OnlineLyrics {
    data class Found(val text: String, val synced: Boolean) : OnlineLyrics
    data object Instrumental : OnlineLyrics
    data object NotFound : OnlineLyrics
}

/**
 * Client for LRCLIB (lrclib.net), a free, open, community-maintained lyrics database with
 * time-synced LRC. No API key; requests identify the app with a User-Agent as LRCLIB asks.
 * Lookups: an exact match on title + artist (+ album) + duration first, then a search that
 * prefers synced lyrics and the closest duration.
 */
class LrclibClient(
    baseClient: OkHttpClient,
    private val userAgent: String,
) {
    // Lyrics are a nice-to-have: never let a slow network hold the lyrics screen for long.
    private val client = baseClient.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    /**
     * Looks [track] up. [pace] runs before every HTTP request (the lyrics pack uses it to stay at
     * about four requests a second); a lookup makes one or two requests.
     */
    suspend fun find(track: Track, pace: suspend () -> Unit = {}): OnlineLyrics = withContext(Dispatchers.IO) {
        val title = cleanTitle(track.title)
        val artist = track.artist.substringBefore(',').substringBefore(" & ").substringBefore(" x ").trim()
        if (title.isBlank() || artist.isBlank()) return@withContext OnlineLyrics.NotFound
        val durationSec = track.durationMs?.let { it / 1000.0 }

        // 1. Exact signature (LRCLIB matches duration within ±2 s).
        if (durationSec != null && durationSec > 0) {
            val url = "$BASE/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply { track.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) } }
                .addQueryParameter("duration", durationSec.toLong().toString())
                .build()
            pace()
            getOne(url.toString())?.let { rec -> toResult(rec)?.let { return@withContext it } }
        }

        // 2. Search, then pick the best candidate.
        val search = "$BASE/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        pace()
        val candidates = getList(search.toString())
        val best = candidates
            .filter { (it.syncedLyrics ?: it.plainLyrics).isNullOrBlank().not() || it.instrumental }
            .filter { c -> durationSec == null || c.duration == null || abs(c.duration - durationSec) <= 6.0 }
            .filter { c -> TrackClassifier.titleSimilarity(cleanTitle(c.trackName.orEmpty()), title) >= 0.6f }
            .sortedWith(
                compareByDescending<LrclibRecord> { !it.syncedLyrics.isNullOrBlank() }
                    .thenBy { c -> if (durationSec == null || c.duration == null) 99.0 else abs(c.duration - durationSec) },
            )
            .firstOrNull()
        best?.let { toResult(it) } ?: OnlineLyrics.NotFound
    }

    private fun toResult(rec: LrclibRecord): OnlineLyrics? = when {
        !rec.syncedLyrics.isNullOrBlank() -> OnlineLyrics.Found(rec.syncedLyrics, synced = true)
        !rec.plainLyrics.isNullOrBlank() -> OnlineLyrics.Found(rec.plainLyrics, synced = false)
        rec.instrumental -> OnlineLyrics.Instrumental
        else -> null
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", userAgent).build()

    // Network failures (IOException) propagate so an offline lookup is never remembered as "no lyrics".
    private fun getOne(url: String): LrclibRecord? =
        client.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) null
            else runCatching { json.decodeFromString(LrclibRecord.serializer(), r.body?.string().orEmpty()) }.getOrNull()
        }

    private fun getList(url: String): List<LrclibRecord> =
        client.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) emptyList()
            else runCatching { json.decodeFromString(ListSerializer(LrclibRecord.serializer()), r.body?.string().orEmpty()) }.getOrDefault(emptyList())
        }

    companion object {
        private const val BASE = "https://lrclib.net"
        private val noise = Regex("""\s*[(\[](?:[^)\]]*(?:feat|ft\.|with |official|lyric|audio|video|visuali[sz]er|remaster|live|explicit|clean|hd|4k)[^)\]]*)[)\]]""", RegexOption.IGNORE_CASE)
        private val dashFeat = Regex("""\s+-\s+(?:feat|ft)\..*$""", RegexOption.IGNORE_CASE)

        /** "Song (feat. X) [Official Video]" → "Song". Keeps meaningful brackets like "(Acoustic)". */
        fun cleanTitle(raw: String): String = raw.replace(noise, "").replace(dashFeat, "").trim()
    }
}
