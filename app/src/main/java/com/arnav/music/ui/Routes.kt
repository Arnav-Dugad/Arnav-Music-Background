package com.arnav.music.ui

import android.net.Uri

/** Navigation map. String routes keep deep links and process-death restoration simple. */
object Routes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val LIBRARY = "library"
    const val AI = "ai?q={q}"
    const val SEARCH = "search?q={q}"
    const val COLLECTION = "collection/{kind}/{id}"
    const val ARTIST = "artist/{name}"
    const val MOMENT = "moment/{id}"
    const val INSIGHTS = "insights"
    const val CONSTELLATION = "constellation"
    const val TIMELINE = "timeline"
    const val LISTENING_STATS = "listening_stats"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_PAGE = "settings/{page}"
    const val AUTH = "auth"
    const val DUPLICATES = "duplicates"
    const val IMPORTS = "imports"
    const val ALBUM = "album/{albumId}"
    const val CREDITS = "credits/{trackId}"

    fun ai(q: String? = null) = "ai?q=" + Uri.encode(q.orEmpty())
    fun search(q: String? = null) = "search?q=" + Uri.encode(q.orEmpty())
    fun collection(kind: CollectionKind, id: String = "_") = "collection/${kind.name}/${Uri.encode(id)}"
    fun artist(name: String) = "artist/" + Uri.encode(name)
    fun moment(id: String) = "moment/$id"
    /** An on-device album page (MediaStore album id). */
    fun album(albumId: String) = "album/" + Uri.encode(albumId)
    /** Credits for one song ([trackId] is a TrackId value, e.g. "yt:…" or "local:…"). */
    fun credits(trackId: String) = "credits/" + Uri.encode(trackId)
    /** Root and sub-pages are distinct destinations so each sub-page stacks on top of Settings. */
    fun settings(page: String? = null) = if (page.isNullOrBlank()) SETTINGS else "settings/" + Uri.encode(page)

    val topLevel = listOf(HOME, EXPLORE, LIBRARY, AI)
}

enum class CollectionKind { PLAYLIST, YOUTUBE_PLAYLIST, SMART, LIKED, HISTORY, LOCAL }

/**
 * Deep links: arnavmusic://track/<videoId>, playlist/<id>, artist/<name>, ai?q=…, settings/<page>,
 * moment/<id>, search?q=…. Invalid links resolve to null and land safely on Home.
 */
sealed interface DeepLink {
    data class PlayYouTube(val videoId: String) : DeepLink
    data class Navigate(val route: String) : DeepLink

    companion object {
        private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
        fun parse(uri: Uri?): DeepLink? {
            if (uri == null) return null
            if (uri.scheme == "https" && (uri.host?.endsWith("youtube.com") == true || uri.host == "youtu.be" || uri.host == "music.youtube.com")) {
                val id = if (uri.host == "youtu.be") uri.lastPathSegment else uri.getQueryParameter("v")
                return id?.takeIf { videoId.matches(it) }?.let { PlayYouTube(it) }
            }
            if (uri.scheme != "arnavmusic") return null
            val seg = uri.pathSegments.firstOrNull()
            return when (uri.host) {
                "track" -> seg?.takeIf { videoId.matches(it) }?.let { PlayYouTube(it) }
                "playlist" -> seg?.takeIf { it.length in 3..80 }?.let { Navigate(Routes.collection(CollectionKind.PLAYLIST, it)) }
                "artist" -> seg?.takeIf { it.isNotBlank() }?.let { Navigate(Routes.artist(it.take(80))) }
                "ai", "lumen" -> Navigate(Routes.ai(uri.getQueryParameter("q")?.take(300)))
                "search" -> Navigate(Routes.search(uri.getQueryParameter("q")?.take(100)))
                "settings" -> Navigate(Routes.settings(seg))
                "moment" -> seg?.takeIf { com.arnav.music.domain.model.Moments.byId(it) != null }?.let { Navigate(Routes.moment(it)) }
                "insights" -> Navigate(Routes.INSIGHTS)
                "smart" -> seg?.takeIf { s -> com.arnav.music.domain.intelligence.SmartPlaylist.entries.any { it.name == s } }?.let { Navigate(Routes.collection(CollectionKind.SMART, it)) }
                else -> null
            }
        }
    }
}
