package com.arnav.music.domain.search
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.*
import org.junit.Test
class LibraryQueryTest {
    private val song = Track(TrackId.local(1), "Get Lucky", "Daft Punk", durationMs = 240000, playbackRef = "content://song", year = 2013)
    @Test fun filtersCombineAndUnknownMetadataDoesNotMatch() {
        val q = LibraryQuery.parse("Get artist:\"Daft Punk\" source:local year:2010..2024 duration:180..360")
        assertTrue(q.structured); assertEquals("Get", q.text); assertTrue(q.accepts(song))
        assertFalse(q.accepts(song.copy(year = null)))
        assertFalse(q.accepts(song.copy(durationMs = 400000)))
        assertFalse(q.accepts(song.copy(id = TrackId.youtube("x"))))
    }
    @Test fun invalidFiltersFailClosed() {
        listOf("source:other", "year:2024..2010", "duration:-1", "duration:abc", "year:2010..2011..2012").forEach {
            val q = LibraryQuery.parse(it); assertNotNull(it, q.error); assertFalse(q.accepts(song))
        }
    }
    @Test fun singleYearPlainTextAndTypos() {
        assertTrue(LibraryQuery.parse("year:2013").accepts(song))
        assertFalse(LibraryQuery.parse("Daft Punk").structured)
        assertTrue(QueryNormalizer.matchScore("daft pnuk", "Daft Punk") >= 0.5f)
        assertTrue(QueryNormalizer.matchScore("lukcy", "Get Lucky") >= 0.5f)
        assertEquals(0f, QueryNormalizer.matchScore("unrelated", "Get Lucky"), 0f)
    }
}
