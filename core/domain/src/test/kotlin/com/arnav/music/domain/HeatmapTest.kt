package com.arnav.music.domain

import com.arnav.music.domain.intelligence.Heatmap
import com.arnav.music.domain.intelligence.PlayStats
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class HeatmapTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val min = 60_000L

    private fun ev(at: LocalDateTime, zone: ZoneId, listenedMin: Long) =
        PlayEvent(TrackId("local:1"), "a", at.atZone(zone).toInstant().toEpochMilli(), listenedMin * min, 200_000L, true, false)

    @Test fun `53 monday-first columns ending with the week of today`() {
        val today = LocalDate.of(2025, 3, 5) // a Wednesday
        val g = Heatmap.build(emptyList(), today, utc)
        assertEquals(53, g.weeks.size)
        assertTrue(g.weeks.all { it.days.size == 7 && it.start.dayOfWeek == DayOfWeek.MONDAY })
        val last = g.weeks.last()
        assertEquals(LocalDate.of(2025, 3, 3), last.start)
        assertEquals(listOf(true, true, true, false, false, false, false), last.days.map { it.inRange })
        assertEquals(LocalDate.of(2025, 3, 3).minusWeeks(52), g.weeks.first().start)
        assertEquals(52, g.currentWeekIndex)
        assertTrue(g.isEmpty)
        // consecutive days, no gaps or repeats
        val all = g.weeks.flatMap { it.days }.map { it.date }
        assertEquals(53 * 7, all.toSet().size)
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
    }

    @Test fun `today on a monday or sunday`() {
        val monday = Heatmap.build(emptyList(), LocalDate.of(2025, 3, 10), utc)
        assertEquals(LocalDate.of(2025, 3, 10), monday.weeks.last().start)
        assertEquals(1, monday.weeks.last().days.count { it.inRange })
        val sunday = Heatmap.build(emptyList(), LocalDate.of(2025, 3, 16), utc)
        assertEquals(LocalDate.of(2025, 3, 10), sunday.weeks.last().start)
        assertEquals(7, sunday.weeks.last().days.count { it.inRange })
    }

    @Test fun `minutes are summed per local day in the given zone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.of(2025, 3, 5)
        // 01:00 local on 5 Mar is still 4 Mar in UTC.
        val events = listOf(
            ev(LocalDateTime.of(2025, 3, 5, 1, 0), kolkata, 20),
            ev(LocalDateTime.of(2025, 3, 5, 22, 0), kolkata, 27),
            ev(LocalDateTime.of(2025, 3, 4, 12, 0), kolkata, 10),
        )
        val g = Heatmap.build(events, today, kolkata)
        assertEquals(47, g.day(LocalDate.of(2025, 3, 5))!!.minutes)
        assertEquals(10, g.day(LocalDate.of(2025, 3, 4))!!.minutes)
        assertEquals(57, g.totalMinutes)
        assertEquals(2, g.activeDays)
        val inUtc = Heatmap.build(events, today, utc)
        assertEquals(30, inUtc.day(LocalDate.of(2025, 3, 4))!!.minutes)
    }

    @Test fun `DST days keep late-evening plays on the right date`() {
        val london = ZoneId.of("Europe/London")
        // Clocks went forward on 30 Mar 2025 (23-hour day) and back on 26 Oct 2025 (25-hour day).
        val events = listOf(
            ev(LocalDateTime.of(2025, 3, 30, 23, 30), london, 15),
            ev(LocalDateTime.of(2025, 3, 31, 0, 15), london, 5),
            ev(LocalDateTime.of(2025, 10, 26, 23, 45), london, 12),
            ev(LocalDateTime.of(2025, 10, 27, 0, 30), london, 8),
        )
        val g = Heatmap.build(events, LocalDate.of(2025, 11, 2), london)
        assertEquals(15, g.day(LocalDate.of(2025, 3, 30))!!.minutes)
        assertEquals(5, g.day(LocalDate.of(2025, 3, 31))!!.minutes)
        assertEquals(12, g.day(LocalDate.of(2025, 10, 26))!!.minutes)
        assertEquals(8, g.day(LocalDate.of(2025, 10, 27))!!.minutes)
        // Week columns stay Monday-aligned across the change.
        assertTrue(g.weeks.all { it.start.dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test fun `events outside the window and in the future are ignored`() {
        val today = LocalDate.of(2025, 3, 5)
        val events = listOf(
            ev(LocalDateTime.of(2023, 1, 1, 12, 0), utc, 60),
            ev(LocalDateTime.of(2025, 3, 6, 12, 0), utc, 60),
            ev(LocalDateTime.of(2025, 3, 5, 12, 0), utc, 3),
        )
        val g = Heatmap.build(events, today, utc)
        assertEquals(3, g.totalMinutes)
        assertEquals(0, g.weeks.last().days[3].minutes) // Thursday 6 Mar, not yet
        assertFalse(g.weeks.last().days[3].inRange)
    }

    @Test fun `levels scale with the busiest day`() {
        assertEquals(0, Heatmap.level(0, 100))
        assertEquals(1, Heatmap.level(1, 100))
        assertEquals(1, Heatmap.level(6, 100))
        assertEquals(2, Heatmap.level(25, 100))
        assertEquals(3, Heatmap.level(50, 100))
        assertEquals(4, Heatmap.level(100, 100))
        assertEquals(4, Heatmap.level(5, 5))
        val today = LocalDate.of(2025, 3, 5)
        val g = Heatmap.build(listOf(ev(LocalDateTime.of(2025, 3, 5, 9, 0), utc, 40), ev(LocalDateTime.of(2025, 3, 4, 9, 0), utc, 2)), today, utc)
        assertEquals(40, g.maxMinutes)
        assertEquals(4, g.day(today)!!.level)
        assertEquals(1, g.day(today.minusDays(1))!!.level)
        assertEquals(0, g.day(today.minusDays(2))!!.level)
    }

    @Test fun `short plays round to whole minutes`() {
        assertEquals(0, Heatmap.minutes(29_000))
        assertEquals(1, Heatmap.minutes(30_000))
        assertEquals(47, Heatmap.minutes(47 * min + 10_000))
    }

    @Test fun `month labels sit over the first column of each month`() {
        val g = Heatmap.build(emptyList(), LocalDate.of(2025, 3, 5), utc)
        val labels = g.monthLabels
        assertTrue(labels.zipWithNext().all { (a, b) -> b.weekIndex - a.weekIndex >= 3 })
        labels.forEach { l -> assertEquals(l.month, YearMonth.from(g.weeks[l.weekIndex].start)) }
        val march = labels.last()
        assertEquals(YearMonth.of(2025, 3), march.month)
        assertEquals(LocalDate.of(2025, 3, 3), g.weeks[march.weekIndex].start)
        assertNotNull(labels.firstOrNull { it.month == YearMonth.of(2024, 12) })
    }

    @Test fun `play stats line`() {
        val at = LocalDateTime.of(2025, 3, 4, 18, 0).atZone(utc).toInstant().toEpochMilli()
        assertEquals("Not played yet", PlayStats.line(null, utc, Locale.ENGLISH))
        assertEquals("Not played yet", PlayStats.line(PlayStats(0, null, null), utc, Locale.ENGLISH))
        assertEquals("Played once · on 4 Mar 2025", PlayStats.line(PlayStats(1, at, at), utc, Locale.ENGLISH))
        assertEquals("Played 23 times · first on 4 Mar 2025", PlayStats.line(PlayStats(23, at, at + 1), utc, Locale.ENGLISH))
    }
}
