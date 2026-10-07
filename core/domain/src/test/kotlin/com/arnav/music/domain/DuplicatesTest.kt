package com.arnav.music.domain

import com.arnav.music.domain.library.Duplicates
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private fun yt(id: String, title: String, artist: String, sec: Long? = null, variant: MediaVariant? = null) =
        Track(TrackId.youtube(id), title, artist, durationMs = sec?.times(1000), playbackRef = id, variant = variant)

    private fun local(id: Long, title: String, artist: String, sec: Long? = null) =
        Track(TrackId.local(id), title, artist, durationMs = sec?.times(1000), playbackRef = "content://media/$id")

    @Test fun `title key strips credits, upload noise and punctuation`() {
        assertEquals("dont stop me now", Duplicates.titleKey("Don't Stop Me Now (Remastered 2011)", "Queen"))
        assertEquals("dont stop me now", Duplicates.titleKey("Queen - Don't Stop Me Now [Official Video]", "Queen Official"))
        assertEquals("levitating", Duplicates.titleKey("Dua Lipa - Levitating (Official Video)", "DuaLipaVEVO"))
        assertEquals("starboy", Duplicates.titleKey("Starboy (feat. Daft Punk)", "The Weeknd"))
        assertEquals("starboy", Duplicates.titleKey("Starboy ft. Daft Punk (Lyrics)", "The Weeknd"))
        assertEquals("kesariya", Duplicates.titleKey("Kesariya | Brahmastra | Arijit Singh", "Arijit Singh"))
        // Version markers stay, so live and studio takes differ.
        assertEquals("yellow live", Duplicates.titleKey("Yellow (Live)", "Coldplay"))
        // A dash only drops the prefix when it is the artist's name.
        assertEquals("love me do", Duplicates.titleKey("Love - Me Do", "The Beatles"))
    }

    @Test fun `same song across youtube, device and import is one group`() {
        val tracks = listOf(
            yt("aaaaaaaaaaa", "Don't Stop Me Now (Official Video)", "Queen Official", 216),
            local(1, "Don't Stop Me Now - Remastered 2011", "Queen", 214),
            yt("bbbbbbbbbbb", "Don't Stop Me Now (Remastered 2011)", "Queen - Topic", 215, MediaVariant.SONG),
            yt("ccccccccccc", "Bohemian Rhapsody", "Queen", 355),
        )
        val groups = Duplicates.find(tracks)
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(3, g.tracks.size)
        assertEquals("queen", g.artistKey)
        // YouTube versions first, then on-device (stable order)
        assertTrue(g.tracks.last().id.value.startsWith("local:"))
    }

    @Test fun `identical ids are not duplicates`() {
        val a = yt("aaaaaaaaaaa", "Song", "Artist", 200)
        assertTrue(Duplicates.find(listOf(a, a, a.copy(title = "Song (Lyrics)"))).isEmpty())
    }

    @Test fun `different lengths split versions`() {
        val tracks = listOf(
            yt("aaaaaaaaaaa", "Song", "Artist", 200),
            local(1, "Song", "Artist", 203),
            local(2, "Song", "Artist", 360), // extended mix
        )
        val g = Duplicates.find(tracks).single()
        assertEquals(setOf("yt:aaaaaaaaaaa", "local:1"), g.tracks.map { it.id.value }.toSet())
        // 5 s apart is too far
        assertTrue(Duplicates.find(listOf(yt("aaaaaaaaaaa", "Song", "Artist", 200), local(1, "Song", "Artist", 205))).isEmpty())
    }

    @Test fun `unknown lengths join a single cluster but never guess between two`() {
        val single = Duplicates.find(listOf(yt("aaaaaaaaaaa", "Song", "Artist", 200), local(1, "Song", "Artist", null)))
        assertEquals(2, single.single().tracks.size)

        val ambiguous = Duplicates.find(
            listOf(
                yt("aaaaaaaaaaa", "Song", "Artist", 200), local(1, "Song", "Artist", 202),
                local(2, "Song", "Artist", 400), local(3, "Song", "Artist", 401),
                local(4, "Song", "Artist", null),
            ),
        )
        assertEquals(2, ambiguous.size)
        assertTrue(ambiguous.none { g -> g.tracks.any { it.id.value == "local:4" } })
    }

    @Test fun `different artists or live versions are kept apart`() {
        val tracks = listOf(
            yt("aaaaaaaaaaa", "Hallelujah", "Leonard Cohen", 280),
            yt("bbbbbbbbbbb", "Hallelujah", "Jeff Buckley", 281),
            local(1, "Yellow", "Coldplay", 266),
            local(2, "Yellow (Live)", "Coldplay", 268),
        )
        assertTrue(Duplicates.find(tracks).isEmpty())
    }

    @Test fun `group key is stable and changes when a version is added`() {
        val a = yt("aaaaaaaaaaa", "Song", "Artist", 200)
        val b = local(1, "Song", "Artist", 201)
        val c = local(2, "Song (Audio)", "Artist", 199)
        val k1 = Duplicates.find(listOf(a, b)).single().key
        assertEquals(k1, Duplicates.find(listOf(b, a)).single().key)
        assertNotEquals(k1, Duplicates.find(listOf(a, b, c)).single().key)
    }
}
