package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class RecapPeriod(val days: Int, val label: String) {
    DAY(1, "Today"), WEEK(7, "This week"), MONTH(30, "This month"), YEAR(365, "This year")
}

data class Recap(
    val period: RecapPeriod,
    val minutesListened: Long,
    val topArtists: List<Pair<String, Long>>, // artistKey → minutes
    val topTracks: List<Pair<TrackId, Int>>,
    val discoveryPercent: Int,
    val favoriteHour: Int?,
    val mostRepeated: Pair<TrackId, Int>?,
    val sessions: Int,
) {
    val isEmpty get() = minutesListened == 0L
}

data class TasteDna(
    val topArtists: List<Pair<String, Float>>,
    val topGenres: List<Pair<String, Float>>,
    val hourHistogram: List<Float>,
    val dayHistogram: List<Float>,
    val discoveryRatio: Float,
    val repeatTendency: Float,
    val skipRate: Float,
    /** Release-year decades → share, only from tracks with known years. */
    val eras: List<Pair<Int, Float>>,
    /** Artists whose share rose/fell most between the last 14 days and the 60 days before. */
    val risingArtists: List<String>,
    val fadingArtists: List<String>,
    val totalMinutes: Long,
    val confidence: Confidence,
) {
    enum class Confidence { NONE, LOW, MEDIUM, HIGH }
}

/** Time Machine insight. Only states what the data can support — no invented life events. */
data class TimeMachineInsight(
    val kind: Kind,
    val headline: String,
    val trackIds: List<TrackId>,
    val artistKey: String? = null,
    val anchorDay: LocalDate? = null,
) {
    enum class Kind { MONTHS_AGO, ARTIST_ABSENT, ERA, ON_THIS_DAY }
}

object InsightsEngine {
    private const val DAY = 86_400_000L

    fun recap(period: RecapPeriod, events: List<PlayEvent>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Recap {
        val start = if (period == RecapPeriod.DAY) {
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        } else now - period.days * DAY
        val inRange = events.filter { it.startedAt in start..now }
        val before = events.filter { it.startedAt < start }.map { it.trackId }.toHashSet()
        val minutes = inRange.sumOf { it.listenedMs } / 60_000
        val artists = inRange.groupBy { it.artistKey }.mapValues { (_, v) -> v.sumOf { it.listenedMs } / 60_000 }
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        val trackCounts = inRange.filter { !it.skipped }.groupingBy { it.trackId }.eachCount()
        val top = trackCounts.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        val distinct = inRange.map { it.trackId }.toSet()
        val discovered = distinct.count { it !in before }
        val hours = IntArray(24)
        inRange.forEach { hours[Instant.ofEpochMilli(it.startedAt).atZone(zone).hour] += (it.listenedMs / 60_000).toInt() + 1 }
        val favHour = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 }
        // Session = gap of >30 min between plays.
        var sessions = 0
        var last = Long.MIN_VALUE
        inRange.sortedBy { it.startedAt }.forEach { if (it.startedAt - last > 30 * 60_000L) sessions++; last = it.startedAt + it.listenedMs }
        return Recap(
            period, minutes, artists, top,
            if (distinct.isEmpty()) 0 else discovered * 100 / distinct.size,
            favHour, top.firstOrNull()?.takeIf { it.second >= 2 }, sessions,
        )
    }

    fun tasteDna(profile: TasteProfile, events: List<PlayEvent>, years: Map<TrackId, Int>, now: Long): TasteDna {
        val recent = events.filter { now - it.startedAt < 14 * DAY }
        val older = events.filter { now - it.startedAt in (14 * DAY)..(74 * DAY) }
        fun share(list: List<PlayEvent>): Map<String, Float> {
            val total = list.sumOf { it.listenedMs }.coerceAtLeast(1)
            return list.groupBy { it.artistKey }.mapValues { (_, v) -> v.sumOf { it.listenedMs }.toFloat() / total }
        }
        val rs = share(recent)
        val os = share(older)
        val deltas = (rs.keys + os.keys).associateWith { (rs[it] ?: 0f) - (os[it] ?: 0f) }
        val eraCounts = events.mapNotNull { years[it.trackId] }.groupingBy { it / 10 * 10 }.eachCount()
        val eraTotal = eraCounts.values.sum().coerceAtLeast(1)
        val confidence = when {
            events.size < 5 -> TasteDna.Confidence.NONE
            events.size < 30 -> TasteDna.Confidence.LOW
            events.size < 150 -> TasteDna.Confidence.MEDIUM
            else -> TasteDna.Confidence.HIGH
        }
        return TasteDna(
            topArtists = profile.artistAffinity.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
            topGenres = profile.genreAffinity.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
            hourHistogram = profile.hourHistogram,
            dayHistogram = profile.dayHistogram,
            discoveryRatio = profile.discoveryRatio,
            repeatTendency = profile.repeatTendency,
            skipRate = profile.skipRate,
            eras = eraCounts.entries.sortedBy { it.key }.map { it.key to it.value.toFloat() / eraTotal },
            risingArtists = if (older.isEmpty()) emptyList() else deltas.filterValues { it > 0.05f }.entries.sortedByDescending { it.value }.take(3).map { it.key },
            fadingArtists = if (recent.isEmpty()) emptyList() else deltas.filterValues { it < -0.05f }.entries.sortedBy { it.value }.take(3).map { it.key },
            totalMinutes = profile.totalListenMs / 60_000,
            confidence = confidence,
        )
    }

    fun timeMachine(events: List<PlayEvent>, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<TimeMachineInsight> {
        if (events.size < 10) return emptyList()
        val out = ArrayList<TimeMachineInsight>()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        // "You loved these three months ago"
        val window = events.filter { now - it.startedAt in (80 * DAY)..(100 * DAY) && !it.skipped }
        val recentIds = events.filter { now - it.startedAt < 30 * DAY }.map { it.trackId }.toSet()
        val loved = window.groupingBy { it.trackId }.eachCount().filter { it.value >= 2 && it.key !in recentIds }
            .entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (loved.isNotEmpty()) out += TimeMachineInsight(TimeMachineInsight.Kind.MONTHS_AGO, "You loved these three months ago", loved)

        // "You haven't heard this artist recently"
        val artistTotals = events.groupBy { it.artistKey }
        val absent = artistTotals.filter { (_, v) -> v.size >= 5 && now - v.maxOf { it.startedAt } > 40 * DAY }
            .maxByOrNull { it.value.size }
        if (absent != null) {
            val ids = absent.value.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key }
            out += TimeMachineInsight(TimeMachineInsight.Kind.ARTIST_ABSENT, "You haven't heard this artist in a while", ids, artistKey = absent.key)
        }

        // "Your January era": the most-listened past month (not current) with a dominant artist.
        val byMonth = events.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).let { d -> d.year * 100 + d.monthValue } }
        val currentKey = today.year * 100 + today.monthValue
        val era = byMonth.filterKeys { it != currentKey }.maxByOrNull { (_, v) -> v.sumOf { it.listenedMs } }
        if (era != null && era.value.size >= 8) {
            val month = java.time.Month.of(era.key % 100).getDisplayName(TextStyle.FULL, locale)
            val ids = era.value.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key }
            out += TimeMachineInsight(TimeMachineInsight.Kind.ERA, "Your $month era", ids)
        }

        // On this day, a year (or a month) ago
        listOf(365L, 30L).forEach { back ->
            val day = today.minusDays(back)
            val s = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val ids = events.filter { it.startedAt in s until s + DAY }.map { it.trackId }.distinct().take(8)
            if (ids.size >= 2) {
                out += TimeMachineInsight(
                    TimeMachineInsight.Kind.ON_THIS_DAY,
                    if (back == 365L) "On this day last year" else "One month ago today", ids, anchorDay = day,
                )
                return out
            }
        }
        return out
    }
}
