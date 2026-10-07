package com.arnav.music.domain

import com.arnav.music.domain.intelligence.ArtistHistories
import com.arnav.music.domain.intelligence.DayPart
import com.arnav.music.domain.intelligence.ListeningPattern
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class ArtistHistoryTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    /** [weeks] weeks after Sunday 2 Feb 2025 at [h]:00. */
    private fun sunday(weeks: Int, h: Int) = LocalDateTime.of(2025, 2, 2, h, 0).plusWeeks(weeks.toLong()).atZone(zone).toInstant().toEpochMilli()
    private fun ev(track: String, start: Long, listened: Long = 200_000, artist: String = "adele") =
        PlayEvent(TrackId("yt:$track"), artist, start, listened, 240_000, listened >= 200_000, false)

    // 2 Mar 2025 is a Sunday.
    private val now = at(2025, 3, 20, 12)

    @Test fun `empty when never played`() {
        val h = ArtistHistories.build(listOf(ev("a", at(2025, 3, 2, 19), artist = "someoneelse")), "adele", now, zone)
        assertTrue(h.isEmpty)
        assertEquals(0, h.plays)
        assertNull(h.firstListened)
        assertEquals(12, h.months.size)
        assertTrue(h.months.all { it.minutes == 0 })
    }

    @Test fun `totals, top tracks and months`() {
        val events = listOf(
            ev("hello", at(2024, 1, 5, 10)), // older than 12 months: counts in totals, not in months
            ev("hello", at(2025, 2, 10, 10)),
            ev("hello", at(2025, 3, 1, 10)),
            ev("skyfall", at(2025, 3, 2, 10)),
            ev("skyfall", at(2025, 3, 3, 10), listened = 10_000), // short: time only
            ev("someone", at(2025, 3, 4, 10), listened = 60_000),
        )
        val h = ArtistHistories.build(events, "adele", now, zone)
        assertEquals(5, h.plays)
        assertEquals(4 * 200_000L + 10_000L + 60_000L, h.listenedMs)
        assertEquals(at(2024, 1, 5, 10), h.firstListened)
        assertEquals(at(2025, 3, 4, 10), h.lastPlayed)
        assertEquals(listOf("yt:hello", "yt:skyfall", "yt:someone"), h.topTracks.map { it.trackId.value })
        assertEquals(listOf(3, 1, 1), h.topTracks.map { it.plays })
        assertEquals(YearMonth.of(2024, 4), h.months.first().month)
        assertEquals(YearMonth.of(2025, 3), h.months.last().month)
        assertEquals((2 * 200_000 + 10_000 + 60_000) / 60_000, h.months.last().minutes)
        assertEquals(200_000 / 60_000, h.months[10].minutes)
    }

    @Test fun `sunday evening pattern`() {
        val events = (0 until 5).map { w -> ev("s$w", sunday(w, 19)) } + listOf(ev("m", at(2025, 2, 4, 9)), ev("t", at(2025, 2, 5, 14)))
        val p = ArtistHistories.build(events, "adele", now, zone).pattern
        assertEquals(ListeningPattern(DayOfWeek.SUNDAY, DayPart.EVENING), p)
        assertEquals("You played them most on Sunday evenings", p!!.sentence(Locale.ENGLISH))
    }

    @Test fun `after midnight counts as the night before`() {
        // 1 am on Sundays → Saturday nights.
        val events = (0 until 6).map { w -> ev("n$w", sunday(w, 1)) }
        val p = ArtistHistories.build(events, "adele", now, zone).pattern
        assertEquals(ListeningPattern(DayOfWeek.SATURDAY, DayPart.NIGHT), p)
        assertEquals("You played them most on Saturday nights", p!!.sentence(Locale.ENGLISH))
    }

    @Test fun `only a time of day when days are spread`() {
        val events = (0 until 7).map { d -> ev("d$d", at(2025, 2, 3 + d, 8)) }
        val p = ArtistHistories.build(events, "adele", now, zone).pattern
        assertEquals(ListeningPattern(null, DayPart.MORNING), p)
        assertEquals("You mostly play them in the morning", p!!.sentence(Locale.ENGLISH))
    }

    @Test fun `no pattern with few or scattered plays`() {
        assertNull(ArtistHistories.build((0 until 3).map { ev("x$it", at(2025, 2, 2, 19)) }, "adele", now, zone).pattern)
        val scattered = listOf(
            at(2025, 2, 3, 8), at(2025, 2, 4, 13), at(2025, 2, 5, 18), at(2025, 2, 6, 23), at(2025, 2, 7, 9), at(2025, 2, 8, 15), at(2025, 2, 9, 20),
        ).mapIndexed { i, t -> ev("s$i", t) }
        assertNull(ArtistHistories.build(scattered, "adele", now, zone).pattern)
    }
}
