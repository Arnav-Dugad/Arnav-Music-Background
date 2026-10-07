package com.arnav.music.domain

import com.arnav.music.domain.library.Albums
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumsTest {
    private fun t(
        id: Long, title: String, artist: String = "Band", album: String? = "Record", albumId: String? = "7",
        track: Int? = null, disc: Int? = null, albumArtist: String? = null, year: Int? = 2001, ms: Long = 180_000,
    ) = Track(
        id = TrackId.local(id), title = title, artist = artist, album = album, durationMs = ms,
        artworkUrl = "content://media/external/audio/albumart/$albumId", playbackRef = "content://x/$id", year = year,
        trackNumber = track, discNumber = disc, albumId = albumId, albumArtist = albumArtist,
    )

    @Test fun `media store track numbers encode the disc`() {
        assertEquals(1 to 3, Albums.decodeTrackNumber(1003))
        assertEquals(2 to 11, Albums.decodeTrackNumber(2011))
        assertEquals(null to 7, Albums.decodeTrackNumber(7))
        assertEquals(null to null, Albums.decodeTrackNumber(0))
        assertEquals(null to null, Albums.decodeTrackNumber(null))
        assertEquals(2 to 4, Albums.decodeTrackNumber(4, discColumn = 2))
        assertEquals(3 to 4, Albums.decodeTrackNumber(1004, discColumn = 3))
        assertEquals(1 to null, Albums.decodeTrackNumber(1000))
        assertEquals(5, Albums.parseNumber("5/12"))
        assertEquals(null, Albums.parseNumber("x"))
    }

    @Test fun `groups by album id and orders by disc and track`() {
        val tracks = listOf(
            t(1, "Second", track = 2, disc = 1),
            t(2, "Disc two opener", track = 1, disc = 2),
            t(3, "First", track = 1, disc = 1),
            t(4, "Unnumbered", track = null, disc = null),
            t(5, "Elsewhere", album = "Other", albumId = "9", artist = "Solo"),
            t(6, "No album id", albumId = null),
            t(7, "No album", album = null, albumId = "11"),
        )
        val albums = Albums.group(tracks)
        assertEquals(listOf("Other", "Record"), albums.map { it.title })
        val record = albums[1]
        assertEquals(listOf("First", "Second", "Unnumbered", "Disc two opener"), record.tracks.map { it.title })
        assertEquals(4, record.trackCount)
        assertEquals(4 * 180_000L, record.totalMs)
        assertEquals("Band", record.artist)
        assertEquals(2001, record.year)
        val discs = Albums.discs(record.tracks)
        assertEquals(listOf(1, 2), discs.map { it.number })
        assertEquals(listOf("First", "Second", "Unnumbered"), discs[0].tracks.map { it.title })
    }

    @Test fun `album artist from tag, majority or various`() {
        assertEquals("Tagged", Albums.albumArtist(listOf(t(1, "a", artist = "X", albumArtist = "Tagged"), t(2, "b", artist = "Y"))))
        assertEquals("Main", Albums.albumArtist(listOf(t(1, "a", artist = "Main"), t(2, "b", artist = "Main"), t(3, "c", artist = "Guest"))))
        assertEquals(Albums.VARIOUS, Albums.albumArtist(listOf(t(1, "a", artist = "A"), t(2, "b", artist = "B"), t(3, "c", artist = "C"))))
    }
}
