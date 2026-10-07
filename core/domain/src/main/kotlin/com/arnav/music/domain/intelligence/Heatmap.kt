package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.sqrt

/** One local calendar day in the heatmap. [inRange] is false for days after today (the rest of this week). */
data class HeatmapDay(val date: LocalDate, val minutes: Int, val level: Int, val inRange: Boolean)

/** A Monday-to-Sunday column. */
data class HeatmapWeek(val start: LocalDate, val days: List<HeatmapDay>) {
    val totalMinutes: Int get() = days.sumOf { it.minutes }
    val activeDays: Int get() = days.count { it.minutes > 0 }
}

/** A month name placed above the column where that month starts. */
data class HeatmapMonthLabel(val weekIndex: Int, val month: YearMonth)

data class HeatmapGrid(
    /** Oldest first; the last column holds today. */
    val weeks: List<HeatmapWeek>,
    val today: LocalDate,
    val monthLabels: List<HeatmapMonthLabel>,
    /** Busiest day's minutes; levels are relative to it. */
    val maxMinutes: Int,
) {
    val totalMinutes: Int get() = weeks.sumOf { it.totalMinutes }
    val activeDays: Int get() = weeks.sumOf { it.activeDays }
    val isEmpty: Boolean get() = maxMinutes == 0

    /** Column index of the week containing [today] (always the last). */
    val currentWeekIndex: Int get() = weeks.lastIndex

    fun day(date: LocalDate): HeatmapDay? = weeks.asSequence().flatMap { it.days.asSequence() }.firstOrNull { it.date == date }
}

/**
 * GitHub-style listening calendar: [Heatmap.WEEKS] Monday-first columns × 7 days, each day shaded by
 * the minutes listened on that local calendar day. Days come from [java.time.LocalDate] in the given
 * zone, so 23- and 25-hour DST days land on the right square.
 */
object Heatmap {
    const val WEEKS = 53
    /** Levels 0 (no music) to [MAX_LEVEL]. */
    const val MAX_LEVEL = 4

    fun build(events: List<PlayEvent>, today: LocalDate, zone: ZoneId, weeks: Int = WEEKS): HeatmapGrid {
        require(weeks > 0)
        val currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstDay = currentWeekStart.minusWeeks((weeks - 1).toLong())

        val msPerDay = HashMap<LocalDate, Long>()
        for (e in events) {
            if (e.listenedMs <= 0) continue
            val d = Instant.ofEpochMilli(e.startedAt).atZone(zone).toLocalDate()
            if (d.isBefore(firstDay) || d.isAfter(today)) continue
            msPerDay[d] = (msPerDay[d] ?: 0L) + e.listenedMs
        }
        val minutesPerDay = msPerDay.mapValues { (_, ms) -> minutes(ms) }
        val max = minutesPerDay.values.maxOrNull() ?: 0

        val columns = (0 until weeks).map { w ->
            val start = firstDay.plusWeeks(w.toLong())
            HeatmapWeek(
                start,
                (0 until 7).map { i ->
                    val date = start.plusDays(i.toLong())
                    val inRange = !date.isAfter(today)
                    val m = if (inRange) minutesPerDay[date] ?: 0 else 0
                    HeatmapDay(date, m, level(m, max), inRange)
                },
            )
        }
        return HeatmapGrid(columns, today, monthLabels(columns), max)
    }

    /** Whole minutes, rounded to the nearest minute. */
    fun minutes(ms: Long): Int = ((ms + 30_000L) / 60_000L).toInt()

    /**
     * 0 for no music; otherwise 1..4 on a square-root scale of the busiest day, so a single marathon
     * day doesn't wash every other day out to the palest shade.
     */
    fun level(minutes: Int, maxMinutes: Int): Int {
        if (minutes <= 0 || maxMinutes <= 0) return 0
        val f = sqrt(minutes.toDouble() / maxMinutes).coerceIn(0.0, 1.0)
        return ceil(f * MAX_LEVEL).toInt().coerceIn(1, MAX_LEVEL)
    }

    /**
     * A label over each column whose Monday starts a new month. The very first column is labelled
     * only when the next label is at least 3 columns away, so names never overlap.
     */
    fun monthLabels(weeks: List<HeatmapWeek>): List<HeatmapMonthLabel> {
        val raw = ArrayList<HeatmapMonthLabel>()
        weeks.forEachIndexed { i, w ->
            val month = YearMonth.from(w.start)
            if (i == 0 || YearMonth.from(weeks[i - 1].start) != month) raw += HeatmapMonthLabel(i, month)
        }
        if (raw.size >= 2 && raw[0].weekIndex == 0 && raw[1].weekIndex < 3) raw.removeAt(0)
        return raw
    }
}
