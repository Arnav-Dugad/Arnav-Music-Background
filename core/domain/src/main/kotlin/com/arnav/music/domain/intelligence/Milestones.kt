package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Listening streak. A listening day is any local day with at least one play of 30 s or more.
 * [current] counts consecutive days ending today or yesterday (so it isn't "lost" mid-morning).
 * [last7] is oldest → today.
 */
data class StreakInfo(val current: Int, val longest: Int, val activeToday: Boolean, val last7: List<Boolean>) {
    companion object {
        val EMPTY = StreakInfo(0, 0, false, List(7) { false })
    }
}

/** A quiet celebration of something the listener already did. [achievedAt] is the event that crossed it. */
data class Milestone(val id: String, val title: String, val detail: String, val progress: Float, val achievedAt: Long?) {
    val achieved: Boolean get() = achievedAt != null
}

object Milestones {
    /** Shorter plays don't count toward days, songs or artists. */
    const val MIN_LISTEN_MS = 30_000L

    private enum class Kind { MINUTES, SONGS, ARTISTS, STREAK, COMPLETED }

    private class Def(val id: String, val kind: Kind, val target: Long, val title: String, val detail: String)

    private val defs = listOf(
        Def("minutes_1000", Kind.MINUTES, 1_000, "1,000 minutes", "Over sixteen hours of music"),
        Def("minutes_10000", Kind.MINUTES, 10_000, "10,000 minutes", "About a week of music, end to end"),
        Def("songs_100", Kind.SONGS, 100, "100 songs", "A hundred different songs played"),
        Def("songs_500", Kind.SONGS, 500, "500 songs", "Five hundred different songs played"),
        Def("songs_1000", Kind.SONGS, 1_000, "1,000 songs", "A thousand different songs played"),
        Def("artists_25", Kind.ARTISTS, 25, "25 artists", "Twenty-five different artists"),
        Def("artists_100", Kind.ARTISTS, 100, "100 artists", "A hundred different artists"),
        Def("streak_7", Kind.STREAK, 7, "7-day streak", "Music every day for a week"),
        Def("streak_30", Kind.STREAK, 30, "30-day streak", "Music every day for a month"),
        Def("completed_100", Kind.COMPLETED, 100, "100 full plays", "A hundred songs heard start to finish"),
    )

    private fun counts(e: PlayEvent) = e.listenedMs >= MIN_LISTEN_MS

    private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun streak(events: List<PlayEvent>, now: Long, zone: ZoneId): StreakInfo {
        val days = HashSet<LocalDate>()
        for (e in events) if (counts(e)) days += day(e.startedAt, zone)
        val today = day(now, zone)
        val last7 = (6 downTo 0).map { today.minusDays(it.toLong()) in days }
        if (days.isEmpty()) return StreakInfo(0, 0, false, last7)
        val activeToday = today in days
        var current = 0
        var cursor: LocalDate? = when {
            activeToday -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> null
        }
        while (cursor != null && cursor in days) {
            current++
            cursor = cursor.minusDays(1)
        }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            if (run > longest) longest = run
            prev = d
        }
        return StreakInfo(current, maxOf(longest, current), activeToday, last7)
    }

    /** All milestones in a fixed order, replayed chronologically so each knows when it was reached. */
    fun compute(events: List<PlayEvent>, zone: ZoneId): List<Milestone> {
        val achieved = HashMap<String, Long>()
        var listenedMs = 0L
        val songs = HashSet<TrackId>()
        val artists = HashSet<String>()
        var completed = 0L
        var lastDay: LocalDate? = null
        var run = 0L
        var bestRun = 0L
        for (e in events.sortedBy { it.startedAt }) {
            listenedMs += e.listenedMs.coerceAtLeast(0L)
            if (e.completed) completed++
            if (counts(e)) {
                songs += e.trackId
                artists += e.artistKey
                val d = day(e.startedAt, zone)
                val last = lastDay
                if (last == null || d != last) {
                    run = if (last != null && last.plusDays(1) == d) run + 1 else 1
                    lastDay = d
                }
                if (run > bestRun) bestRun = run
            }
            for (def in defs) {
                if (def.id in achieved) continue
                if (value(def.kind, listenedMs, songs.size, artists.size, bestRun, completed) >= def.target) achieved[def.id] = e.startedAt
            }
        }
        return defs.map { def ->
            val at = achieved[def.id]
            val v = value(def.kind, listenedMs, songs.size, artists.size, bestRun, completed)
            Milestone(
                id = def.id,
                title = def.title,
                detail = def.detail,
                progress = if (at != null) 1f else (v.toFloat() / def.target).coerceIn(0f, 1f),
                achievedAt = at,
            )
        }
    }

    /** The closest not-yet-reached milestones, most progressed first. */
    fun upcoming(milestones: List<Milestone>, count: Int = 2): List<Milestone> =
        milestones.filter { !it.achieved }.sortedByDescending { it.progress }.take(count)

    private fun value(kind: Kind, listenedMs: Long, songs: Int, artists: Int, bestRun: Long, completed: Long): Long = when (kind) {
        Kind.MINUTES -> listenedMs / 60_000L
        Kind.SONGS -> songs.toLong()
        Kind.ARTISTS -> artists.toLong()
        Kind.STREAK -> bestRun
        Kind.COMPLETED -> completed
    }
}
