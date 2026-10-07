package com.arnav.music.domain.library

import com.arnav.music.domain.importer.MatchScorer
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Track
import kotlin.math.abs

/**
 * Two or more versions of the same song in the library — e.g. a YouTube upload, a file on this
 * device and a track matched from a Spotify import. [key] is stable for the same set of versions and
 * changes when a new version appears, so an ignored group comes back only when something new joins it.
 */
data class DuplicateGroup(val key: String, val artistKey: String, val titleKey: String, val tracks: List<Track>)

/**
 * Finds the same song stored more than once. Versions match on [ArtistKey] plus a normalised title
 * (credits, "(Official Video)", "(Lyrics)", "(Remastered 2011)" and punctuation removed; live, remix
 * and other version markers kept, so a live take never counts as a copy of the studio one). When
 * both lengths are known they must be within [TOLERANCE_MS].
 */
object Duplicates {
    const val TOLERANCE_MS = 4_000L

    private val separator = Regex("""\s+[-–—|:]\s+""")
    private val pipeTail = Regex("""\s*[|｜].*$""")
    private val officialSuffix = Regex("""\s+official(\s+(channel|artist))?\s*$""", RegexOption.IGNORE_CASE)

    /** [ArtistKey] after dropping "Official"/"Official Channel" from channel names ("Queen Official" → "queen"). */
    fun artistKey(artist: String): String = ArtistKey.of(artist.trim().replace(officialSuffix, ""))

    /** Comparison key for a title, with a leading "Artist - " (common in YouTube uploads) removed. */
    fun titleKey(title: String, artist: String): String {
        var t = title.trim()
        val own = artistKey(artist)
        val parts = t.split(separator, limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank() && own.isNotEmpty() && artistKey(parts[0]) == own) t = parts[1]
        // "Song | Movie | Label" → "Song"
        t = t.replace(pipeTail, "").ifBlank { t }
        return MatchScorer.titleKey(t)
    }

    fun find(tracks: List<Track>, toleranceMs: Long = TOLERANCE_MS): List<DuplicateGroup> {
        val unique = tracks.distinctBy { it.id }
        val buckets = LinkedHashMap<Pair<String, String>, MutableList<Track>>()
        for (t in unique) {
            val artist = artistKey(t.artist)
            val title = titleKey(t.title, t.artist)
            if (artist.isEmpty() || title.isEmpty()) continue
            buckets.getOrPut(artist to title) { ArrayList() } += t
        }
        val out = ArrayList<DuplicateGroup>()
        for ((k, list) in buckets) {
            if (list.size < 2) continue
            for (cluster in byDuration(list, toleranceMs)) {
                if (cluster.size < 2) continue
                val ordered = cluster.sortedWith(compareBy<Track>({ it.source.ordinal }, { it.id.value }))
                out += DuplicateGroup(groupKey(k.first, k.second, ordered), k.first, k.second, ordered)
            }
        }
        return out.sortedWith(compareBy({ it.artistKey }, { it.titleKey }, { it.key }))
    }

    /**
     * Splits same-titled tracks by length: each cluster spans at most [toleranceMs] from its shortest
     * version. Versions with an unknown length join the only cluster there is; when there are several
     * (say a radio edit and an extended mix) they are left out rather than guessed, unless they can
     * pair with each other.
     */
    private fun byDuration(list: List<Track>, toleranceMs: Long): List<List<Track>> {
        val known = list.filter { (it.durationMs ?: 0L) > 0L }.sortedBy { it.durationMs }
        val unknown = list.filter { (it.durationMs ?: 0L) <= 0L }
        val clusters = ArrayList<MutableList<Track>>()
        for (t in known) {
            val last = clusters.lastOrNull()
            if (last != null && abs(t.durationMs!! - last.first().durationMs!!) <= toleranceMs) last += t
            else clusters += mutableListOf(t)
        }
        when {
            clusters.isEmpty() -> clusters += unknown.toMutableList()
            clusters.size == 1 -> clusters[0] += unknown
            unknown.size >= 2 -> clusters += unknown.toMutableList()
        }
        return clusters
    }

    private fun groupKey(artist: String, title: String, tracks: List<Track>): String {
        val ids = tracks.map { it.id.value }.sorted().joinToString(",")
        return "$artist|$title#" + Integer.toHexString(ids.hashCode())
    }
}

/** Where one version is used: Arnav playlists holding it, counted plays (30 s or more) and whether it's liked. */
data class TrackUsage(val playlists: Int = 0, val plays: Int = 0, val liked: Boolean = false) {
    companion object {
        val NONE = TrackUsage()
    }
}
