package com.arnav.music.domain

import com.arnav.music.domain.metadata.AutoTagQuery
import com.arnav.music.domain.metadata.AutoTagScorer
import com.arnav.music.domain.metadata.MbRecording
import com.arnav.music.domain.metadata.MbRelease
import com.arnav.music.domain.metadata.MusicBrainzJson
import com.arnav.music.domain.metadata.TagHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTagTest {

    // region what needs tagging

    @Test fun `placeholders need tagging`() {
        assertTrue(AutoTagQuery.needsTagging("Song", "<unknown>", "Album"))
        assertTrue(AutoTagQuery.needsTagging("Song", "Unknown artist", "Album"))
        assertTrue(AutoTagQuery.needsTagging("Song", "", "Album"))
        assertTrue(AutoTagQuery.needsTagging("Song", "Adele", null))
        assertTrue(AutoTagQuery.needsTagging("Song", "Adele", "Download"))
        assertFalse(AutoTagQuery.needsTagging("Song", "Adele", "25"))
        assertFalse(AutoTagQuery.needsTagging("", "<unknown>", null))
    }

    // endregion

    // region hints

    @Test fun `artist dash title file name`() {
        val h = AutoTagQuery.hints("Adele - Hello", "<unknown>", "Adele - Hello.mp3")
        assertEquals(TagHints("Hello", "Adele", titleFromFileName = true), h)
    }

    @Test fun `track number then title`() {
        val h = AutoTagQuery.hints("01 - Hello", "Adele", "01 - Hello.flac")
        assertEquals("Hello", h.title)
        assertEquals("Adele", h.artist)
        assertTrue(h.titleFromFileName)
    }

    @Test fun `numbered artist title with underscores and site noise`() {
        val h = AutoTagQuery.hints("", null, "03._Daft_Punk_-_Get_Lucky_(Official_Audio)_[www.songs.pk].mp3")
        assertEquals("Get Lucky", h.title)
        assertEquals("Daft Punk", h.artist)
    }

    @Test fun `artist album number title`() {
        val h = AutoTagQuery.hints("Radiohead - OK Computer - 04 - Exit Music", "<unknown>", null)
        assertEquals("Exit Music", h.title)
        assertEquals("Radiohead", h.artist)
    }

    @Test fun `featured artists are pulled out`() {
        val h = AutoTagQuery.hints("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk", null)
        assertEquals("Get Lucky", h.title)
        assertEquals("Daft Punk", h.artist)
        assertEquals(listOf("Pharrell Williams", "Nile Rodgers"), h.featured)
        val t = AutoTagQuery.hints("Stay ft. Justin Bieber", "<unknown>", null)
        assertEquals("Stay", t.title)
        assertNull(t.artist)
        assertEquals(listOf("Justin Bieber"), t.featured)
    }

    @Test fun `numbers that are part of the title stay`() {
        assertEquals("7 Rings", AutoTagQuery.hints("7 Rings", "<unknown>", null).title)
        assertEquals("22", AutoTagQuery.hints("22", "Taylor Swift", null).title)
        assertEquals("1999", AutoTagQuery.hints("1999", "Prince", "1999.mp3").title)
    }

    @Test fun `repeated known artist is dropped from title`() {
        val h = AutoTagQuery.hints("Coldplay - Yellow", "Coldplay", null)
        assertEquals("Yellow", h.title)
        assertEquals("Coldplay", h.artist)
    }

    @Test fun `query uses artist or duration`() {
        assertEquals("recording:\"Hello\" AND artist:\"Adele\"", AutoTagQuery.query(TagHints("Hello", "Adele"), 295_000))
        assertEquals("recording:\"Exit Music\" AND dur:[260000 TO 268000]", AutoTagQuery.query(TagHints("Exit Music", null), 264_000))
        assertEquals("recording:\"Say  Hi\"".replace("  ", " "), AutoTagQuery.query(TagHints("Say \"Hi\"", null), null))
    }

    // endregion

    // region scoring

    private fun rec(
        id: String, title: String, artist: String, length: Long?, score: Int = 100,
        releases: List<MbRelease> = listOf(MbRelease("r-$id", "Album $id", "2015-10-23", "Official", "Album")),
        disambiguation: String? = null,
    ) = MbRecording(id, title, artist, length, releases, score, disambiguation)

    @Test fun `exact title artist and length is accepted`() {
        val h = TagHints("Hello", "Adele")
        val m = AutoTagScorer.best(h, 295_500, listOf(rec("a", "Hello", "Adele", 295_000), rec("b", "Hello", "Lionel Richie", 250_000)))
        assertNotNull(m)
        assertEquals("a", m!!.recordingId)
        assertEquals("Album a", m.album)
        assertEquals(2015, m.year)
        assertTrue(m.confidence >= AutoTagScorer.ACCEPT)
    }

    @Test fun `length more than three seconds off is rejected`() {
        val h = TagHints("Hello", "Adele")
        assertNull(AutoTagScorer.best(h, 300_000, listOf(rec("a", "Hello", "Adele", 295_000))))
        assertTrue(AutoTagScorer.score(h, 298_000, rec("a", "Hello", "Adele", 295_000)) >= AutoTagScorer.ACCEPT)
    }

    @Test fun `wrong artist is rejected`() {
        assertNull(AutoTagScorer.best(TagHints("Hello", "Adele"), 295_000, listOf(rec("b", "Hello", "Lionel Richie", 295_000))))
    }

    @Test fun `live and remix versions are penalised`() {
        val h = TagHints("Hello", "Adele")
        val live = rec("l", "Hello (Live at the Church Studios)", "Adele", 295_000)
        val remix = rec("r", "Hello", "Adele", 295_000, disambiguation = "remix")
        assertTrue(AutoTagScorer.score(h, 295_000, live) < AutoTagScorer.ACCEPT)
        assertTrue(AutoTagScorer.score(h, 295_000, remix) < AutoTagScorer.ACCEPT)
    }

    @Test fun `title and length only needs a distinctive title and no rival artist`() {
        val h = TagHints("Exit Music For A Film", null)
        val one = AutoTagScorer.best(h, 264_000, listOf(rec("x", "Exit Music (For a Film)", "Radiohead", 265_000)))
        assertNotNull(one)
        val generic = TagHints("Hello", null)
        assertNull(AutoTagScorer.best(generic, 295_000, listOf(rec("a", "Hello", "Adele", 295_000))))
        val rivals = listOf(rec("x", "Exit Music For A Film", "Radiohead", 265_000), rec("y", "Exit Music For A Film", "Cover Band", 264_500))
        assertNull(AutoTagScorer.best(h, 264_000, rivals))
    }

    @Test fun `no length and no artist is never enough`() {
        assertNull(AutoTagScorer.best(TagHints("Exit Music For A Film", null), null, listOf(rec("x", "Exit Music For A Film", "Radiohead", 265_000))))
    }

    @Test fun `best release prefers official studio album, then hint`() {
        val releases = listOf(
            MbRelease("comp", "Now 92", "2015-11-01", "Official", "Album", listOf("Compilation")),
            MbRelease("single", "Hello", "2015-10-23", "Official", "Single"),
            MbRelease("album", "25", "2015-11-20", "Official", "Album"),
            MbRelease("boot", "Live Bootleg", "2016-01-01", "Bootleg", "Album", listOf("Live")),
        )
        val r = rec("a", "Hello", "Adele", 295_000, releases = releases)
        assertEquals("album", AutoTagScorer.bestRelease(r)?.id)
        assertEquals("single", AutoTagScorer.bestRelease(r, albumHint = "Hello")?.id)
    }

    // endregion

    // region MusicBrainz JSON

    @Test fun `parses recording search response`() {
        val body = """
            {"created":"2024-01-01T00:00:00.000Z","count":2,"offset":0,"recordings":[
              {"id":"rec-1","score":100,"title":"Get Lucky","length":369000,"disambiguation":"",
               "artist-credit":[{"name":"Daft Punk","joinphrase":" feat. ","artist":{"id":"a1","name":"Daft Punk"}},
                                {"name":"Pharrell Williams","artist":{"id":"a2","name":"Pharrell Williams"}}],
               "first-release-date":"2013-04-19",
               "releases":[{"id":"rel-1","title":"Random Access Memories","status":"Official","date":"2013-05-17",
                            "release-group":{"id":"rg","primary-type":"Album","secondary-types":[]}},
                           {"id":"rel-2","title":"Get Lucky","status":"Official","date":null,
                            "release-group":{"primary-type":"Single"}}]},
              {"id":"rec-2","score":80,"title":"Get Lucky","length":null,"artist-credit":[{"name":"Someone"}]}
            ]}
        """.trimIndent()
        val list = MusicBrainzJson.recordings(body)
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("rec-1", a.id)
        assertEquals("Daft Punk feat. Pharrell Williams", a.artist)
        assertEquals(369_000L, a.lengthMs)
        assertEquals(100, a.searchScore)
        assertNull(a.disambiguation)
        assertEquals("2013-04-19", a.firstReleaseDate)
        assertEquals(listOf("rel-1", "rel-2"), a.releases.map { it.id })
        assertEquals("Album", a.releases[0].primaryType)
        assertNull(a.releases[1].date)
        assertNull(list[1].lengthMs)
        assertEquals("Someone", list[1].artist)
        assertEquals(emptyList<MbRecording>(), MusicBrainzJson.recordings("not json"))
        assertEquals(emptyList<MbRecording>(), MusicBrainzJson.recordings("{\"error\":\"rate limited\"}"))
    }

    @Test fun `end to end with featured artist hint`() {
        val body = """{"recordings":[{"id":"r","score":98,"title":"Get Lucky","length":248000,
            "artist-credit":[{"name":"Daft Punk","joinphrase":" feat. "},{"name":"Pharrell Williams"}],
            "releases":[{"id":"rel","title":"Random Access Memories","status":"Official","date":"2013-05-17","release-group":{"primary-type":"Album"}}]}]}"""
        val hints = AutoTagQuery.hints("", "<unknown>", "Daft Punk - Get Lucky (feat. Pharrell Williams).mp3")
        val m = AutoTagScorer.best(hints, 249_000, MusicBrainzJson.recordings(body))
        assertNotNull(m)
        assertEquals("Random Access Memories", m!!.album)
        assertEquals(2013, m.year)
        assertEquals("Daft Punk feat. Pharrell Williams", m.artist)
    }

    // endregion
}
