package com.arnav.music.domain

import com.arnav.music.domain.importer.ImportFormatException
import com.arnav.music.domain.importer.ImportSource
import com.arnav.music.domain.importer.ImportedSong
import com.arnav.music.domain.importer.LocalMatchIndex
import com.arnav.music.domain.importer.MatchScorer
import com.arnav.music.domain.importer.PlaylistFiles
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImporterTest {
    // ------------------------------------------------------------------ Spotify JSON

    @Test fun `spotify playlist json keeps tracks and skips episodes and local files`() {
        val json = """
            {"playlists":[
              {"name":"Road Trip","lastModifiedDate":"2024-01-01","items":[
                {"track":{"trackName":"Blinding Lights","artistName":"The Weeknd","albumName":"After Hours","trackUri":"spotify:track:1"},"episode":null,"localTrack":null,"addedDate":"2024-01-01"},
                {"track":null,"episode":{"episodeName":"Some podcast","showName":"Show","episodeUri":"spotify:episode:2"},"localTrack":null},
                {"track":null,"episode":null,"localTrack":{"uri":"spotify:local:a:b:c:200"}},
                {"track":{"trackName":"  Levitating ","artistName":"Dua Lipa","albumName":"","trackUri":"spotify:track:3"}}
              ]},
              {"name":"Empty","items":[]},
              {"name":"","items":[{"track":{"trackName":"Song","artistName":"A","albumName":"B"}}]}
            ]}
        """.trimIndent()
        val parsed = PlaylistFiles.parse("Playlist1.json", json)
        assertEquals(ImportSource.SPOTIFY, parsed.source)
        assertEquals(2, parsed.playlists.size)
        val road = parsed.playlists[0]
        assertEquals("Road Trip", road.name)
        assertEquals(listOf("Blinding Lights", "Levitating"), road.songs.map { it.title })
        assertEquals("The Weeknd", road.songs[0].artist)
        assertEquals("After Hours", road.songs[0].album)
        assertNull("blank album becomes null", road.songs[1].album)
        assertNull(road.songs[0].durationMs)
        assertEquals("Spotify playlist", parsed.playlists[1].name)
    }

    @Test fun `spotify library json becomes liked songs`() {
        val json = """{"tracks":[{"artist":"Daft Punk","album":"Discovery","track":"One More Time","uri":"spotify:track:x"},{"artist":"X","album":"Y","track":"","uri":"z"}],"albums":[],"shows":[]}"""
        val parsed = PlaylistFiles.parse("YourLibrary.json", json)
        assertEquals(1, parsed.playlists.size)
        assertEquals(PlaylistFiles.LIKED_SONGS_NAME, parsed.playlists[0].name)
        assertEquals(ImportedSong("One More Time", "Daft Punk", "Discovery", null), parsed.playlists[0].songs.single())
    }

    @Test fun `unrelated or broken json is rejected with a friendly message`() {
        listOf("""{"foo":1}""", """[1,2,3]""", """{"playlists": [""", "{}").forEach { text ->
            try {
                PlaylistFiles.parse("x.json", text)
                fail("expected rejection for $text")
            } catch (e: ImportFormatException) {
                assertEquals(PlaylistFiles.NOT_A_PLAYLIST, e.message)
            }
        }
    }

    // ------------------------------------------------------------------ CSV

    @Test fun `exportify csv with quoted multi-artist fields`() {
        val q = "\""
        val csv = "\uFEFF" + listOf(
            "${q}Track URI$q,${q}Track Name$q,${q}Album Name$q,${q}Artist Name(s)$q,${q}Album Artist Name(s)$q,${q}Release Date$q,${q}Duration (ms)$q,${q}Popularity$q",
            "${q}spotify:track:1$q,${q}One Dance$q,${q}Views$q,${q}Drake,Wizkid,Kyla$q,${q}Drake$q,${q}2016-05-06$q,${q}173986$q,${q}80$q",
            "${q}spotify:track:2$q,${q}Say $q${q}Hello$q$q$q,${q}Album, With Comma$q,${q}Someone$q,${q}Someone$q,${q}2020$q,${q}215000$q,${q}10$q",
            "${q}spotify:track:3$q,$q$q,${q}Local$q,${q}Nobody$q,$q$q,$q$q,${q}0$q,${q}0$q",
        ).joinToString("\r\n")
        val parsed = PlaylistFiles.parse("Summer_Hits.csv", csv)
        assertEquals(ImportSource.CSV, parsed.source)
        val p = parsed.playlists.single()
        assertEquals("Summer Hits", p.name)
        assertEquals(2, p.songs.size)
        assertEquals(ImportedSong("One Dance", "Drake, Wizkid, Kyla", "Views", 173_986), p.songs[0])
        assertEquals("Say \"Hello\"", p.songs[1].title)
        assertEquals("Album, With Comma", p.songs[1].album)
        assertEquals(215_000L, p.songs[1].durationMs)
    }

    @Test fun `generic csv with semicolons, m-ss durations and embedded newlines`() {
        val csv = "Song;Artist;Length\r\n\"Bohemian\nRhapsody\";Queen;5:55\r\nImagine;John Lennon;3:03\r\n\r\n"
        val p = PlaylistFiles.parse("classics.csv", csv).playlists.single()
        assertEquals("classics", p.name)
        assertEquals(2, p.songs.size)
        assertEquals("Bohemian\nRhapsody", p.songs[0].title)
        assertEquals(355_000L, p.songs[0].durationMs)
        assertEquals(ImportedSong("Imagine", "John Lennon", null, 183_000), p.songs[1])
    }

    @Test fun `csv header detection by name`() {
        val p = PlaylistFiles.parseCsv("#,Title,Artists,Album,Duration\n1,Halo,Beyoncé,I Am... Sasha Fierce,261\n2,Hello,Adele,25,295000\n", "Mix")
        assertEquals(listOf("Halo", "Hello"), p.songs.map { it.title })
        assertEquals("Beyoncé", p.songs[0].artist)
        assertEquals(261_000L, p.songs[0].durationMs)
        assertEquals(295_000L, p.songs[1].durationMs)
        val tsv = PlaylistFiles.parseCsv("name\tartist\nA\tB\n", "T")
        assertEquals(ImportedSong("A", "B"), tsv.songs.single())
        val titleOnly = PlaylistFiles.parseCsv("Track\nSolo\n", "T")
        assertEquals(ImportedSong("Solo", ""), titleOnly.songs.single())
    }

    @Test fun `csv without a title column is rejected`() {
        try {
            PlaylistFiles.parse("numbers.csv", "a,b,c\n1,2,3\n")
            fail("expected rejection")
        } catch (e: ImportFormatException) {
            assertEquals(PlaylistFiles.NOT_A_PLAYLIST, e.message)
        }
        try {
            PlaylistFiles.parse("empty.csv", "")
            fail("expected rejection")
        } catch (_: ImportFormatException) {
        }
    }

    @Test fun `rfc 4180 reader edge cases`() {
        assertEquals(listOf(listOf("a", "b,c", "d\"e", "")), PlaylistFiles.readCsv("a,\"b,c\",\"d\"\"e\",\n"))
        assertEquals(listOf(listOf("x", "y"), listOf("1", "2")), PlaylistFiles.readCsv("x,y\r1,2"))
        assertEquals(listOf(listOf("q", "w")), PlaylistFiles.readCsv("q, \"w\""))
        assertEquals(';', PlaylistFiles.detectDelimiter("a;b;\"c,d,e\"\n1,2,3"))
        assertEquals(',', PlaylistFiles.detectDelimiter("single"))
    }

    @Test fun `durations and names`() {
        assertEquals(215_000L, PlaylistFiles.parseDuration("3:35"))
        assertEquals(3_723_000L, PlaylistFiles.parseDuration("1:02:03"))
        assertEquals(215_000L, PlaylistFiles.parseDuration("215000"))
        assertEquals(215_000L, PlaylistFiles.parseDuration("215"))
        assertEquals(900L, PlaylistFiles.parseDuration("900", "duration ms"))
        assertEquals(12_000_000L, PlaylistFiles.parseDuration("12000", "duration s"))
        assertNull(PlaylistFiles.parseDuration("soon"))
        assertNull(PlaylistFiles.parseDuration(""))
        assertEquals("My Mix.v2", PlaylistFiles.nameFromFile("/storage/My Mix.v2.csv"))
        assertEquals("Imported playlist", PlaylistFiles.nameFromFile(".csv"))
        assertEquals("artist names", PlaylistFiles.normalizeHeader("Artist Name(s)"))
        assertEquals("duration ms", PlaylistFiles.normalizeHeader("Duration (ms)"))
    }

    // ------------------------------------------------------------------ Matching

    private fun yt(n: Int, title: String, artist: String, durationMs: Long? = 200_000, variant: MediaVariant? = null, album: String? = null, compilation: Boolean = false) =
        Track(TrackId.youtube("m$n"), title, artist, album = album, durationMs = durationMs, playbackRef = "m$n", variant = variant, compilation = compilation)

    @Test fun `title cleaning strips credits and editions but keeps versions`() {
        assertEquals("Don't Stop Me Now", MatchScorer.cleanTitle("Don't Stop Me Now - Remastered 2011"))
        assertEquals("Yesterday", MatchScorer.cleanTitle("Yesterday - 2009 Remaster"))
        assertEquals("One Dance", MatchScorer.cleanTitle("One Dance (feat. Wizkid & Kyla)"))
        assertEquals("Kesariya", MatchScorer.cleanTitle("Kesariya (From \"Brahmastra\")"))
        assertEquals("Kesariya", MatchScorer.cleanTitle("Kesariya - From \"Brahmastra\""))
        assertEquals("Song", MatchScorer.cleanTitle("Song ft. Somebody"))
        assertEquals("Hello (Live at the BBC)", MatchScorer.cleanTitle("Hello (Live at the BBC)"))
        assertEquals("dont stop me now", MatchScorer.titleKey("Don't Stop Me Now (Remastered)"))
        assertEquals(listOf("A", "B", "C", "D"), MatchScorer.artists("A, B & C feat. D"))
        assertEquals("The Weeknd Blinding Lights", MatchScorer.query(ImportedSong("Blinding Lights", "The Weeknd, Someone")))
    }

    @Test fun `exact official upload scores high and wins over a video`() {
        val song = ImportedSong("Blinding Lights", "The Weeknd", "After Hours", 200_040)
        val topic = yt(1, "Blinding Lights", "The Weeknd", 200_000, MediaVariant.SONG)
        val video = yt(2, "Blinding Lights", "The Weeknd", 262_000, MediaVariant.VIDEO)
        assertTrue(MatchScorer.score(song, topic) > 0.95f)
        assertEquals(topic, MatchScorer.pick(song, listOf(video, topic)))
    }

    @Test fun `remastered and featuring suffixes still match`() {
        val song = ImportedSong("Don't Stop Me Now - Remastered 2011", "Queen", durationMs = 209_000)
        val c = yt(3, "Don't Stop Me Now", "Queen", 210_000, MediaVariant.SONG)
        assertTrue(MatchScorer.score(song, c) >= MatchScorer.DEFAULT_THRESHOLD)
        val feat = ImportedSong("One Dance", "Drake, Wizkid, Kyla")
        assertNotNull(MatchScorer.pick(feat, listOf(yt(4, "One Dance (feat. Wizkid & Kyla)", "Drake", 174_000))))
    }

    @Test fun `artist name in the uploaded title is ignored for title similarity`() {
        assertEquals(1f, MatchScorer.titleSimilarity("Blinding Lights", "The Weeknd Blinding Lights Official Video", "The Weeknd"), 0.001f)
    }

    @Test fun `duration closeness`() {
        assertEquals(1f, MatchScorer.durationCloseness(200_000, 202_500)!!, 0.001f)
        assertEquals(0f, MatchScorer.durationCloseness(200_000, 230_000)!!, 0.001f)
        val mid = MatchScorer.durationCloseness(200_000, 211_500)!!
        assertTrue(mid > 0.4f && mid < 0.6f)
        assertNull(MatchScorer.durationCloseness(null, 200_000))
        val song = ImportedSong("Halo", "Beyoncé", durationMs = 261_000)
        assertTrue(MatchScorer.score(song, yt(5, "Halo", "Beyoncé", 262_000)) > MatchScorer.score(song, yt(6, "Halo", "Beyoncé", 300_000)))
    }

    @Test fun `live, cover, karaoke and remix versions are penalised unless asked for`() {
        val song = ImportedSong("Hello", "Adele", durationMs = 295_000)
        val studio = yt(7, "Hello", "Adele", 295_500)
        val live = yt(8, "Hello (Live at the NRJ Awards)", "Adele", 296_000)
        val karaoke = yt(9, "Hello (Karaoke Version)", "Sing King", 295_000)
        val cover = yt(10, "Hello - Adele cover", "Some Singer", 290_000)
        assertTrue(MatchScorer.score(song, live) < MatchScorer.score(song, studio))
        assertTrue(MatchScorer.score(song, live) < MatchScorer.DEFAULT_THRESHOLD)
        assertTrue(MatchScorer.score(song, karaoke) < MatchScorer.DEFAULT_THRESHOLD)
        assertTrue(MatchScorer.score(song, cover) < MatchScorer.DEFAULT_THRESHOLD)
        assertEquals(studio, MatchScorer.pick(song, listOf(karaoke, live, cover, studio)))
        val liveSong = ImportedSong("Hello - Live at the NRJ Awards", "Adele")
        assertTrue(MatchScorer.score(liveSong, live) >= MatchScorer.DEFAULT_THRESHOLD)
        val remix = ImportedSong("Levitating (DaBaby Remix)", "Dua Lipa")
        assertTrue(MatchScorer.score(remix, yt(11, "Levitating (DaBaby Remix)", "Dua Lipa")) >= MatchScorer.DEFAULT_THRESHOLD)
        assertTrue(MatchScorer.score(ImportedSong("Levitating", "Dua Lipa"), yt(12, "Levitating (DaBaby Remix)", "Dua Lipa")) < MatchScorer.DEFAULT_THRESHOLD)
    }

    @Test fun `compilations, different songs and different artists are rejected`() {
        val song = ImportedSong("Halo", "Beyoncé")
        assertNull(MatchScorer.pick(song, listOf(yt(13, "Halo", "Beyoncé", 3_600_000, compilation = true))))
        assertNull(MatchScorer.pick(song, listOf(yt(14, "Halo Theme Orchestral Suite", "Beyoncé"))))
        assertNull(MatchScorer.pick(song, listOf(yt(15, "Halo", "Starset"))))
        assertNull(MatchScorer.pick(song, emptyList()))
    }

    @Test fun `label uploads credit the singer outside the channel name`() {
        val song = ImportedSong("Kesariya", "Arijit Singh", durationMs = 268_000)
        val label = yt(16, "Kesariya", "Sony Music India", 268_500, album = "Brahmastra").copy(credits = "Arijit Singh, Pritam")
        assertNotNull(MatchScorer.pick(song, listOf(label)))
    }

    @Test fun `local index finds exact-ish titles quickly`() {
        val local = listOf(
            Track(TrackId.local(1), "Don't Stop Me Now (Remastered 2011)", "Queen", durationMs = 209_000, playbackRef = "content://1"),
            Track(TrackId.local(2), "Imagine", "John Lennon", durationMs = 183_000, playbackRef = "content://2"),
        )
        val index = LocalMatchIndex(local)
        assertEquals(local[0], index.match(ImportedSong("Don't Stop Me Now", "Queen")))
        assertEquals(local[1], index.match(ImportedSong("Imagine", "John Lennon", durationMs = 184_000)))
        assertNull(index.match(ImportedSong("Imagine", "A Perfect Circle")))
        assertNull(index.match(ImportedSong("Bohemian Rhapsody", "Queen")))
    }
}
