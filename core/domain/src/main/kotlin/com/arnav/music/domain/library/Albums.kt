package com.arnav.music.domain.library

import com.arnav.music.domain.model.Track

/** An album of on-device songs (grouped by MediaStore album id). */
data class AlbumSummary(
    val id: String,
    val title: String,
    /** Album artist when tagged, else the common artist, else "Various artists". */
    val artist: String,
    val year: Int?,
    val artworkUrl: String?,
    /** Disc, then track number, then title. */
    val tracks: List<Track>,
) {
    val trackCount: Int get() = tracks.size
    val totalMs: Long get() = tracks.sumOf { it.durationMs ?: 0L }
}

data class AlbumDisc(val number: Int, val tracks: List<Track>)

object Albums {
    const val VARIOUS = "Various artists"

    /**
     * MediaStore's TRACK column holds disc × 1000 + track ("1003" = disc 1, track 3). Returns
     * (disc, track); either is null when unknown. A separate DISC_NUMBER (API 30+) wins.
     */
    fun decodeTrackNumber(raw: Int?, discColumn: Int? = null): Pair<Int?, Int?> {
        val disc = discColumn?.takeIf { it in 1..999 }
        if (raw == null || raw <= 0) return disc to null
        return if (raw >= 1000) {
            val d = (raw / 1000).takeIf { it in 1..999 }
            val t = (raw % 1000).takeIf { it > 0 }
            (disc ?: d) to t
        } else disc to raw
    }

    /** "3/12" or "03" style tags → 3. */
    fun parseNumber(raw: String?): Int? = raw?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /** Albums of [tracks]; songs without an album id or album title are left out. Sorted by title. */
    fun group(tracks: List<Track>): List<AlbumSummary> =
        tracks.filter { !it.albumId.isNullOrBlank() && !it.album.isNullOrBlank() }
            .groupBy { it.albumId!! }
            .map { (id, list) -> summary(id, list) }
            .sortedWith(compareBy<AlbumSummary> { it.title.lowercase() }.thenBy { it.artist.lowercase() })

    fun summary(id: String, tracks: List<Track>): AlbumSummary {
        val ordered = ordered(tracks)
        val title = mostCommon(tracks.mapNotNull { it.album?.takeIf { a -> a.isNotBlank() } }) ?: "Unknown album"
        return AlbumSummary(
            id = id,
            title = title,
            artist = albumArtist(tracks),
            year = mostCommon(tracks.mapNotNull { it.year }),
            artworkUrl = ordered.firstNotNullOfOrNull { it.artworkUrl },
            tracks = ordered,
        )
    }

    fun albumArtist(tracks: List<Track>): String {
        mostCommon(tracks.mapNotNull { it.albumArtist?.takeIf { a -> a.isNotBlank() } })?.let { return it }
        val byKey = tracks.groupBy { it.artistKey }
        if (byKey.size == 1) return tracks.first().artist
        // One artist on most songs (others are features) still reads as theirs.
        val (topKey, topTracks) = byKey.maxByOrNull { it.value.size } ?: return VARIOUS
        return if (topTracks.size * 2 > tracks.size && topKey.isNotEmpty()) mostCommon(topTracks.map { it.artist }) ?: VARIOUS else VARIOUS
    }

    fun ordered(tracks: List<Track>): List<Track> =
        tracks.sortedWith(compareBy<Track> { it.discNumber ?: 1 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })

    /** Songs per disc in order; a single disc comes back as one group. */
    fun discs(tracks: List<Track>): List<AlbumDisc> =
        ordered(tracks).groupBy { it.discNumber ?: 1 }.toSortedMap().map { (n, t) -> AlbumDisc(n, t) }

    private fun <T> mostCommon(values: List<T>): T? =
        values.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<T, Int>> { it.value })?.key
}
