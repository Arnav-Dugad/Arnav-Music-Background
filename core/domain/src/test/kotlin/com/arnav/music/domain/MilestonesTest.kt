package com.arnav.music.domain

import com.arnav.music.domain.intelligence.Milestones
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MilestonesTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val hour = 3_600_000L
    private val day0 = LocalDate.of(2025, 3, 10).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun ev(at: Long, track: String = "t", artist: String = "a", listened: Long = 180_000L, completed: Boolean = true) =
        PlayEvent(TrackId("local:$track"), artist, at, listened, 200_000L, completed, !completed)

    /** One play at noon on each given day offset relative to [day0]. */
    private fun onDays(vararg offsets: Int) = offsets.map { ev(day0 + it * DAY + 12 * hour) }

    @Test fun `current streak counts back from today`() {
        val now = day0 + 8 * DAY + 20 * hour // "today" is day 8
        val s = Milestones.streak(onDays(0, 1, 2, 3, 6, 7, 8), now, utc)
        assertEquals(3, s.current)
        assertEquals(4, s.longest)
        assertTrue(s.activeToday)
        // days 2..8, oldest first
        assertEquals(listOf(true, true, false, false, true, true, true), s.last7)
    }

    @Test fun `streak ending yesterday still counts`() {
        val now = day0 + 5 * DAY + 9 * hour
        val s = Milestones.streak(onDays(3, 4), now, utc)
        assertEquals(2, s.current)
        assertFalse(s.activeToday)
        assertEquals(listOf(false, false, false, false, true, true, false), s.last7)
    }

    @Test fun `gap of a full day resets the current streak but not the longest`() {
        val now = day0 + 6 * DAY
        val s = Milestones.streak(onDays(0, 1, 2, 4), now, utc)
        assertEquals(0, s.current)
        assertEquals(3, s.longest)
    }

    @Test fun `short listens and duplicate plays do not inflate days`() {
        val now = day0 + 1 * DAY + 10 * hour
        val events = listOf(
            ev(day0 + 1 * hour), ev(day0 + 2 * hour), // two plays on day 0
            ev(day0 + DAY + hour, listened = 10_000L), // too short on day 1
        )
        val s = Milestones.streak(events, now, utc)
        assertEquals(1, s.current)
        assertEquals(1, s.longest)
        assertFalse(s.activeToday)
    }

    @Test fun `days follow the given zone`() {
        val lateUtc = day0 + 23 * hour // 23:00 UTC on day 0 is already day 1 in UTC+5
        val now = day0 + DAY + 12 * hour
        assertFalse(Milestones.streak(listOf(ev(lateUtc)), now, utc).activeToday)
        assertTrue(Milestones.streak(listOf(ev(lateUtc)), now, ZoneOffset.ofHours(5)).activeToday)
    }

    @Test fun `empty history`() {
        val s = Milestones.streak(emptyList(), day0, utc)
        assertEquals(0, s.current)
        assertEquals(0, s.longest)
        assertEquals(7, s.last7.size)
        val ms = Milestones.compute(emptyList(), utc)
        assertTrue(ms.none { it.achieved })
        assertTrue(ms.all { it.progress == 0f })
    }

    @Test fun `milestones remember the event that crossed them`() {
        // 250 hourly plays of distinct songs, 5 minutes each, 30 artists, from midnight on day 0.
        val events = (0 until 250).map { i ->
            ev(day0 + i * hour, track = "s$i", artist = "artist${i % 30}", listened = 300_000L)
        }.shuffled(kotlin.random.Random(3)) // order of input must not matter
        val m = Milestones.compute(events, utc).associateBy { it.id }

        assertEquals(day0 + 199 * hour, m.getValue("minutes_1000").achievedAt) // 5 × 200 = 1,000
        assertEquals(day0 + 99 * hour, m.getValue("songs_100").achievedAt)
        assertEquals(day0 + 24 * hour, m.getValue("artists_25").achievedAt)
        assertEquals(day0 + 99 * hour, m.getValue("completed_100").achievedAt)
        assertEquals(day0 + 6 * DAY, m.getValue("streak_7").achievedAt) // first play of the 7th day
        assertEquals(1f, m.getValue("songs_100").progress, 0f)

        assertNull(m.getValue("minutes_10000").achievedAt)
        assertEquals(0.125f, m.getValue("minutes_10000").progress, 0.001f) // 1,250 / 10,000
        assertEquals(0.5f, m.getValue("songs_500").progress, 0.001f)
        assertEquals(0.3f, m.getValue("artists_100").progress, 0.001f)
        assertEquals(11f / 30f, m.getValue("streak_30").progress, 0.001f) // hours 0..249 span days 0..10

        val next = Milestones.upcoming(m.values.toList(), 2)
        assertEquals(listOf("songs_500", "streak_30"), next.map { it.id })
    }

    @Test fun `short plays count toward minutes but not songs`() {
        val events = (0 until 150).map { i -> ev(day0 + i * hour, track = "s$i", listened = 20_000L, completed = false) }
        val m = Milestones.compute(events, utc).associateBy { it.id }
        assertEquals(0f, m.getValue("songs_100").progress, 0f)
        assertEquals(0.05f, m.getValue("minutes_1000").progress, 0.001f) // 150 × 20 s = 50 min
        assertEquals(0f, m.getValue("streak_7").progress, 0f)
    }
}
