package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

enum class SmartPlaylist(val title: String, val blurb: String) {
    TOP_THIS_MONTH("Top songs this month", "Most listened since the 1st, refreshed as you play"),
    HEAVY_ROTATION("Heavy Rotation", "What you keep coming back to this month"),
    FORGOTTEN_FAVORITES("Forgotten Favorites", "Loved once, quiet lately"),
    RECENTLY_DISCOVERED("Recently Discovered", "First heard in the last two weeks"),
    MOST_REPLAYED("Most Replayed", "Your all-time repeats"),
    NIGHT_OWL("Night Owl", "What plays after midnight"),
    SUNDAY_MORNING("Sunday Morning", "Slow-start weekend picks"),
    NEVER_FINISHED("Never Finished", "Started, never quite completed"),
    FRESH_FINDS("Fresh Finds", "New to you, already sticking"),
    REDISCOVER("Rediscover", "Not played in over a month"),
}

/** Deterministic, local smart playlists computed from Arnav Music listening activity. */
object SmartPlaylistEngine {
    private const val DAY = 86_400_000L

    fun compute(
        kind: SmartPlaylist,
        events: List<PlayEvent>,
        liked: Set<TrackId>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = 50,
    ): List<TrackId> {
        if (events.isEmpty() && liked.isEmpty()) return emptyList()
        val byTrack = events.groupBy { it.trackId }
        val firstPlayed = byTrack.mapValues { (_, v) -> v.minOf { it.startedAt } }
        val lastPlayed = byTrack.mapValues { (_, v) -> v.maxOf { it.startedAt } }
        val goodPlays = byTrack.mapValues { (_, v) -> v.count { !it.skipped || it.completionRatio > 0.6f } }

        fun hour(e: PlayEvent) = Instant.ofEpochMilli(e.startedAt).atZone(zone).hour
        fun dow(e: PlayEvent) = Instant.ofEpochMilli(e.startedAt).atZone(zone).dayOfWeek

        val result: List<TrackId> = when (kind) {
            SmartPlaylist.TOP_THIS_MONTH -> topThisMonth(events, now, zone)

            SmartPlaylist.HEAVY_ROTATION -> events.filter { now - it.startedAt < 30 * DAY && !it.skipped }
                .groupingBy { it.trackId }.eachCount().filterValues { it >= 2 }
                .entries.sortedByDescending { it.value }.map { it.key }

            SmartPlaylist.FORGOTTEN_FAVORITES -> (liked + goodPlays.filterValues { it >= 4 }.keys)
                .filter { id -> (lastPlayed[id] ?: 0L).let { it == 0L || now - it > 45 * DAY } }
                .sortedByDescending { goodPlays[it] ?: 0 }

            SmartPlaylist.RECENTLY_DISCOVERED -> firstPlayed.filterValues { now - it < 14 * DAY }
                .entries.sortedByDescending { it.value }.map { it.key }

            SmartPlaylist.MOST_REPLAYED -> goodPlays.filterValues { it >= 3 }
                .entries.sortedByDescending { it.value }.map { it.key }

            SmartPlaylist.NIGHT_OWL -> events.filter { hour(it) in 0..4 && !it.skipped }
                .groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

            SmartPlaylist.SUNDAY_MORNING -> events.filter { dow(it) == DayOfWeek.SUNDAY && hour(it) in 6..12 && !it.skipped }
                .groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

            SmartPlaylist.NEVER_FINISHED -> byTrack.filter { (_, v) -> v.size >= 2 && v.none { it.completed } }
                .keys.sortedByDescending { byTrack[it]?.size ?: 0 }

            SmartPlaylist.FRESH_FINDS -> firstPlayed.filter { (id, first) -> now - first < 30 * DAY && (goodPlays[id] ?: 0) >= 2 }
                .entries.sortedByDescending { goodPlays[it.key] ?: 0 }.map { it.key }

            SmartPlaylist.REDISCOVER -> lastPlayed.filter { (id, last) -> now - last > 30 * DAY && (goodPlays[id] ?: 0) >= 2 }
                .entries.sortedBy { it.value }.map { it.key }
        }
        return result.distinct().take(limit)
    }

    /**
     * Songs of the current calendar month (in [zone], from the 1st at 00:00 up to [now]) ranked by
     * total listening time, ties broken by number of plays, then id. Only plays of at least
     * [Milestones.MIN_LISTEN_MS] count, and a song needs [minPlays] of them.
     */
    fun topThisMonth(events: List<PlayEvent>, now: Long, zone: ZoneId, minPlays: Int = 2): List<TrackId> {
        val monthStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val listened = HashMap<TrackId, Long>()
        val plays = HashMap<TrackId, Int>()
        for (e in events) {
            if (e.startedAt < monthStart || e.startedAt > now || e.listenedMs < Milestones.MIN_LISTEN_MS) continue
            listened.merge(e.trackId, e.listenedMs, Long::plus)
            plays.merge(e.trackId, 1, Int::plus)
        }
        return plays.filterValues { it >= minPlays }.keys.sortedWith(
            compareByDescending<TrackId> { listened[it] ?: 0L }.thenByDescending { plays[it] ?: 0 }.thenBy { it.value },
        )
    }
}
