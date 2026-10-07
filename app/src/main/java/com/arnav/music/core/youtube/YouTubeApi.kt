package com.arnav.music.core.youtube

import com.arnav.music.domain.provider.MusicError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable data class YtThumb(val url: String? = null, val width: Int? = null, val height: Int? = null)
@Serializable data class YtThumbs(val default: YtThumb? = null, val medium: YtThumb? = null, val high: YtThumb? = null, val standard: YtThumb? = null, val maxres: YtThumb? = null) {
    fun best(): String? = (maxres ?: standard ?: high ?: medium ?: default)?.url
    fun small(): String? = (medium ?: high ?: default)?.url
}
@Serializable data class YtSnippet(
    val title: String = "",
    val channelTitle: String = "",
    val channelId: String? = null,
    val description: String = "",
    val publishedAt: String? = null,
    val thumbnails: YtThumbs = YtThumbs(),
    val tags: List<String> = emptyList(),
    val categoryId: String? = null,
    val liveBroadcastContent: String? = null,
)
@Serializable data class YtId(val kind: String = "", val videoId: String? = null, val channelId: String? = null, val playlistId: String? = null)
@Serializable data class YtSearchItem(val id: YtId = YtId(), val snippet: YtSnippet = YtSnippet())
@Serializable data class YtSearchResponse(val items: List<YtSearchItem> = emptyList(), val nextPageToken: String? = null)
@Serializable data class YtContentDetails(val duration: String? = null, val itemCount: Int? = null, val videoId: String? = null)
@Serializable data class YtStatus(val embeddable: Boolean = true, val privacyStatus: String? = null)
@Serializable data class YtVideo(val id: String = "", val snippet: YtSnippet = YtSnippet(), val contentDetails: YtContentDetails = YtContentDetails(), val status: YtStatus = YtStatus())
@Serializable data class YtVideoResponse(val items: List<YtVideo> = emptyList(), val nextPageToken: String? = null)
@Serializable data class YtPlaylistItem(val snippet: YtSnippet = YtSnippet(), val contentDetails: YtContentDetails = YtContentDetails())
@Serializable data class YtPlaylistItemsResponse(val items: List<YtPlaylistItem> = emptyList(), val nextPageToken: String? = null)
@Serializable data class YtPlaylist(val id: String = "", val snippet: YtSnippet = YtSnippet(), val contentDetails: YtContentDetails = YtContentDetails())
@Serializable data class YtPlaylistResponse(val items: List<YtPlaylist> = emptyList(), val nextPageToken: String? = null)
@Serializable data class YtErrorReason(val reason: String? = null)
@Serializable data class YtErrorBody(val code: Int = 0, val errors: List<YtErrorReason> = emptyList())
@Serializable data class YtError(val error: YtErrorBody = YtErrorBody())

/**
 * Minimal YouTube Data API v3 client. Public data uses the API key; the playlist importer passes a
 * short-lived OAuth access token (youtube.readonly) that lives only in memory and is never logged.
 * Every call reports its quota cost to the caller; nothing here is called without passing the cache first.
 */
class YouTubeApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiKey: () -> String?,
    private val packageName: String,
    private val certSha1: () -> String?,
) {
    private val base = "https://www.googleapis.com/youtube/v3/"

    private suspend inline fun <reified T> get(path: String, params: Map<String, String?>, bearer: String? = null): T = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() }
        if (key == null && bearer == null) throw MusicError.MissingApiKey
        val url: HttpUrl = (base + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            if (bearer == null && key != null) addQueryParameter("key", key)
        }.build()
        val request = Request.Builder().url(url)
            // Lets Google enforce the Android-app restriction configured on the API key.
            .header("X-Android-Package", packageName)
            .apply { certSha1()?.let { header("X-Android-Cert", it) } }
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw MusicError.Offline
        }
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val reason = runCatching { json.decodeFromString(YtError.serializer(), body).error.errors.firstOrNull()?.reason }.getOrNull()
                throw when {
                    reason == "quotaExceeded" || reason == "dailyLimitExceeded" || reason == "rateLimitExceeded" -> MusicError.QuotaExhausted
                    r.code == 400 && reason == "keyInvalid" -> MusicError.MissingApiKey
                    r.code == 401 -> MusicError.PermissionDenied
                    reason == "accessNotConfigured" || reason == "youtubeSignupRequired" -> MusicError.NotConfigured
                    r.code == 403 -> MusicError.Http(403)
                    else -> MusicError.Http(r.code)
                }
            }
            json.decodeFromString<T>(body)
        }
    }

    /** search.list — 100 units. */
    suspend fun search(query: String, type: String, pageToken: String?, maxResults: Int = 20, regionCode: String? = null): YtSearchResponse =
        get("search", mapOf(
            "part" to "snippet", "q" to query, "type" to type, "maxResults" to maxResults.toString(),
            "pageToken" to pageToken, "safeSearch" to "moderate",
            "videoCategoryId" to if (type == "video") "10" else null,
            "videoEmbeddable" to if (type == "video") "true" else null,
            "regionCode" to regionCode?.takeIf { it.length == 2 },
        ))

    /** videos.list — 1 unit for up to 50 ids. */
    suspend fun videos(ids: List<String>, bearer: String? = null): YtVideoResponse =
        get("videos", mapOf("part" to "snippet,contentDetails,status", "id" to ids.take(50).joinToString(",")), bearer)

    /** Most popular music videos chart — 1 unit. */
    suspend fun chart(regionCode: String?, pageToken: String? = null): YtVideoResponse =
        get("videos", mapOf(
            "part" to "snippet,contentDetails,status", "chart" to "mostPopular", "videoCategoryId" to "10",
            "maxResults" to "25", "regionCode" to (regionCode?.takeIf { it.length == 2 } ?: "US"), "pageToken" to pageToken,
        ))

    /** playlistItems.list — 1 unit. */
    suspend fun playlistItems(playlistId: String, pageToken: String? = null, bearer: String? = null): YtPlaylistItemsResponse =
        get("playlistItems", mapOf("part" to "snippet,contentDetails", "playlistId" to playlistId, "maxResults" to "50", "pageToken" to pageToken), bearer)

    /** playlists.list?mine=true — 1 unit. Requires the user's OAuth token (youtube.readonly). */
    suspend fun myPlaylists(bearer: String, pageToken: String? = null): YtPlaylistResponse =
        get("playlists", mapOf("part" to "snippet,contentDetails", "mine" to "true", "maxResults" to "50", "pageToken" to pageToken), bearer)
}
