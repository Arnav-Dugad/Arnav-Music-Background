package com.arnav.music.domain

import com.arnav.music.domain.stats.ClockPlay
import com.arnav.music.domain.stats.Discovery
import com.arnav.music.domain.stats.GenreFallback
import com.arnav.music.domain.stats.GenreNames
import com.arnav.music.domain.stats.GenreSource
import com.arnav.music.domain.stats.HistoryRow
import com.arnav.music.domain.stats.ListenRecord
import com.arnav.music.domain.stats.ListeningClock
import com.arnav.music.domain.stats.SkipMark
import com.arnav.music.domain.stats.SkipSpots
import com.arnav.music.domain.stats.StatsCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ListeningStatsTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val min = 60_000L
    private val day = 86_400_000L

    private fun at(h: Int, m: Int = 0, d: Int = 1, zone: ZoneId = utc) =
        LocalDateTime.of(2026, 3, d, h, m).atZone(zone).toInstant().toEpochMilli()

    // ---- Listening clock ----

    @Test fun `clock splits a listen across the hour boundary and sums correctly`() {
        val c = ListeningClock.build(listOf(ClockPlay(at(8, 50), 20 * min, listOf("indie"))), utc)
        assertEquals(24, c.hours.size)
        assertEquals(10 * min, c.hours[8].totalMs)
        assertEquals(10 * min, c.hours[9].totalMs)
        assertEquals(20 * min, c.totalMs)
        assertEquals(listOf("Indie"), c.genres)
        assertEquals("Indie", c.dominantGenre(8))
        assertTrue(c.hours.all { it.genreMs.sum() == it.totalMs })
    }

    @Test fun `clock uses local time of the zone`() {
        val ist = ZoneId.of("Asia/Kolkata")
        val c = ListeningClock.build(listOf(ClockPlay(at(22, 0, zone = ist), 30 * min, listOf("lofi"))), ist)
        assertEquals(30 * min, c.hours[22].totalMs)
    }

    @Test fun `top genres get colours, the rest and unknown fold into Other`() {
        val plays = listOf(
            ClockPlay(at(9), 50 * min, listOf("indie")),
            ClockPlay(at(10), 40 * min, listOf("pop")),
            ClockPlay(at(11), 30 * min, listOf("jazz")),
            ClockPlay(at(12), 20 * min, emptyList()),
        )
        val c = ListeningClock.build(plays, utc, maxGenres = 2)
        assertEquals(listOf("Indie", "Pop", ListeningClock.OTHER), c.genres)
        assertEquals(listOf(50 * min, 40 * min, 50 * min), c.genreTotals)
        assertEquals("Other", c.dominantGenre(12))
    }

    @Test fun `multi-genre songs share their time`() {
        val c = ListeningClock.build(listOf(ClockPlay(at(14), 30 * min, listOf("Hip Hop", "r&b"))), utc)
        assertEquals(setOf("Hip hop", "R&B"), c.genres.toSet())
        assertEquals(listOf(15 * min, 15 * min), c.genreTotals)
    }

    @Test fun `summary names mornings and late nights`() {
        val plays = listOf(
            ClockPlay(at(8), 40 * min, listOf("indie")),
            ClockPlay(at(9), 20 * min, listOf("pop")),
            ClockPlay(at(23), 30 * min, listOf("lofi")),
            ClockPlay(at(1, d = 2), 20 * min, listOf("lofi")),
        )
        val c = ListeningClock.build(plays, utc)
        assertEquals("Mornings are Indie, late nights are Lo-fi", c.summary)
        assertTrue(c.describe().contains("Mornings are Indie"))
    }

    @Test fun `summary merges periods with the same genre and skips thin periods`() {
        val plays = listOf(
            ClockPlay(at(8), 40 * min, listOf("indie")),
            ClockPlay(at(14), 40 * min, listOf("indie")),
            ClockPlay(at(19), 40 * min, listOf("edm")),
            ClockPlay(at(23), 2 * min, listOf("lofi")), // under 12 % of the day: no clause
        )
        assertEquals("Mornings and afternoons are Indie, evenings are EDM", ListeningClock.build(plays, utc).summary)
    }

    @Test fun `same genre all day and unknown genres`() {
        val all = listOf(at(8), at(14), at(19)).map { ClockPlay(it, 30 * min, listOf("pop")) }
        assertEquals("Pop, morning to night", ListeningClock.build(all, utc).summary)
        val unknown = ListeningClock.build(listOf(ClockPlay(at(8), 30 * min, emptyList())), utc)
        assertNull(unknown.summary)
        assertEquals(8, unknown.peakHour)
        assertTrue(ListeningClock.build(emptyList(), utc).isEmpty)
    }

    @Test fun `artist genres fill in for songs without hints`() {
        val m = GenreFallback.resolve(
            listOf(
                GenreSource("a1", "artist", listOf("Indie")),
                GenreSource("a2", "artist", listOf("indie", "pop")),
                GenreSource("a3", "artist", emptyList()),
                GenreSource("b1", "other", emptyList()),
            ),
        )
        assertEquals(listOf("indie", "pop"), m["a3"])
        assertEquals(emptyList<String>(), m["b1"])
        assertEquals(listOf("indie"), m["a1"])
        assertEquals("Lo-fi", GenreNames.display("lofi"))
    }

    // ---- Discovery ----

    private fun rec(track: String, artist: String, t: Long, ms: Long = 3 * min) = ListenRecord(track, artist, t, ms)

    @Test fun `discovery buckets new artists, new songs and familiar songs`() {
        val now = 100 * day
        val history = listOf(
            rec("old1", "A", now - 30 * day),
            rec("old1", "A", now - 2 * day), // familiar
            rec("new1", "A", now - 1 * day), // new song, known artist
            rec("new1", "A", now - 1 * day + 1000), // still new this week
            rec("x1", "B", now - 3 * day), // new artist
        )
        val r = Discovery.report(history, now)
        val w = r.thisWeek
        assertEquals(4, w.plays)
        assertEquals(3 * min, w.familiarMs)
        assertEquals(6 * min, w.newSongMs)
        assertEquals(3 * min, w.newArtistMs)
        assertEquals(1, w.newArtists); assertEquals(1, w.newSongs); assertEquals(1, w.familiarSongs)
        assertEquals(75, w.percentNew)
        assertTrue(r.lastWeek.isEmpty)
        assertNull(r.deltaPoints)
    }

    @Test fun `discovery compares with last week`() {
        val now = 100 * day
        val history = listOf(
            rec("s1", "A", now - 10 * day), // last week: new artist
            rec("s1", "A", now - 2 * day), // this week: familiar
            rec("s2", "C", now - 2 * day), // this week: new artist
        )
        val r = Discovery.report(history, now)
        assertEquals(100, r.lastWeek.percentNew)
        assertEquals(50, r.thisWeek.percentNew)
        assertEquals(-50, r.deltaPoints)
    }

    // ---- Skip spots ----

    private fun skip(track: String, pos: Long, at: Long = pos) = SkipMark(track, pos, 200_000L, at)

    @Test fun `three skips within plus-minus six seconds make a spot`() {
        val spots = SkipSpots.detect(listOf(skip("t", 151_000), skip("t", 148_000), skip("t", 157_000), skip("u", 30_000)))
        assertEquals(1, spots.size)
        val s = spots[0]
        assertEquals("t", s.trackId)
        assertEquals(3, s.count)
        assertEquals(151_000L, s.positionMs)
        assertEquals("2:31", SkipSpots.formatPosition(s.positionMs))
        assertTrue(s.canTrim)
    }

    @Test fun `skips spread wider than twelve seconds are not a spot`() {
        assertTrue(SkipSpots.detect(listOf(skip("t", 100_000), skip("t", 107_000), skip("t", 113_000))).isEmpty())
        assertTrue(SkipSpots.detect(listOf(skip("t", 100_000), skip("t", 104_000))).isEmpty())
    }

    @Test fun `scattered skips need the cluster to be at least half`() {
        val marks = listOf(10_000L, 11_000L, 12_000L, 60_000L, 90_000L, 120_000L, 150_000L).map { skip("t", it) }
        assertTrue(SkipSpots.detect(marks).isEmpty())
        val spot = SkipSpots.detect(marks.take(5)).single()
        assertEquals(11_000L, spot.positionMs)
        assertEquals(5, spot.totalSkips)
        assertFalse(spot.canTrim) // first half of the song
    }

    @Test fun `largest cluster wins and results are ordered by count`() {
        val marks = listOf(20_000L, 21_000L, 22_000L, 160_000L, 161_000L, 162_000L, 163_000L).map { skip("a", it) } +
            listOf(50_000L, 52_000L, 54_000L).map { skip("b", it) }
        val spots = SkipSpots.detect(marks)
        assertEquals(listOf("a", "b"), spots.map { it.trackId })
        assertEquals(4, spots[0].count)
        assertEquals(161_500L, spots[0].positionMs)
        assertEquals(160_000L, spots[0].fromMs)
    }

    @Test fun `meaningful and trim windows`() {
        assertFalse(SkipSpots.isMeaningful(3_000, 200_000))
        assertFalse(SkipSpots.isMeaningful(197_000, 200_000))
        assertTrue(SkipSpots.isMeaningful(120_000, 200_000))
        assertTrue(SkipSpots.isMeaningful(120_000, null))
        assertFalse(SkipSpots.canTrim(120_000, null))
        assertFalse(SkipSpots.canTrim(199_000, 200_000))
        assertTrue(SkipSpots.canTrim(100_000, 200_000))
    }

    // ---- CSV ----

    @Test fun `rfc 4180 escaping`() {
        assertEquals("plain", StatsCsv.escape("plain"))
        assertEquals("\"a,b\"", StatsCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", StatsCsv.escape("say \"hi\""))
        assertEquals("\"line\r\nbreak\"", StatsCsv.escape("line\r\nbreak"))
        assertEquals("\"two\nlines\"", StatsCsv.escape("two\nlines"))
        assertEquals("\" padded \"", StatsCsv.escape(" padded "))
        assertEquals("", StatsCsv.escape(""))
        assertEquals("a,\"b,c\",d\r\n", StatsCsv.line(listOf("a", "b,c", "d")))
    }

    @Test fun `formula-looking text is neutralised`() {
        assertEquals("'=HYPERLINK(\"x\")", StatsCsv.safeText("=HYPERLINK(\"x\")"))
        assertEquals("'+1", StatsCsv.safeText("+1"))
        assertEquals("'@me", StatsCsv.safeText("@me"))
        assertEquals("Song", StatsCsv.safeText("Song"))
    }

    @Test fun `history csv has header, local ISO timestamps and seconds`() {
        val ist = ZoneId.of("Asia/Kolkata")
        val t = LocalDateTime.of(2026, 10, 6, 21, 14, 3).atZone(ist).toInstant().toEpochMilli() + 456
        val sb = StringBuilder()
        StatsCsv.writeHistory(
            listOf(
                HistoryRow(t, "Hello, \"World\"", "Ärtist", null, "YOUTUBE", 183_000, 200_000, false, true, 151_400),
                HistoryRow(t, "Plain", "B", "LP", "LOCAL", 4_500, null, true, false, null),
            ),
            ist, sb,
        )
        val lines = sb.toString().split("\r\n")
        assertEquals('﻿', sb[0])
        assertEquals(StatsCsv.HISTORY_HEADER.joinToString(","), lines[0].removePrefix("﻿"))
        assertEquals("2026-10-06T21:14:03+05:30,\"Hello, \"\"World\"\"\",Ärtist,,youtube,183,200,false,true,151.4", lines[1])
        assertEquals("2026-10-06T21:14:03+05:30,Plain,B,LP,local,4.5,,true,false,", lines[2])
        assertEquals("", lines[3])
        assertEquals(4, lines.size)
    }
}
