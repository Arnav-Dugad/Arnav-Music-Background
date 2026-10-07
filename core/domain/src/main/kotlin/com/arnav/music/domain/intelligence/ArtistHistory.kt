package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Parts of the day used for "You played them most on Sunday evenings". */
enum class DayPart(val plural: String, val phrase: String) {
    MORNING("mornings", "in the morning"),
    AFTERNOON("afternoons", "in the afternoon"),
    EVENING("evenings", "in the evening"),
    NIGHT("nights", "late at night");

    companion object {
        /** 5–11 morning, 12–16 afternoon, 17–21 evening, 22–4 night. */
        fun of(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

data class MonthMinutes(val month: YearMonth, val minutes: Int)

data class ArtistTopTrack(val trackId: TrackId, val plays: Int, val listenedMs: Long)

data class ListeningPattern(val day: DayOfWeek?, val part: DayPart?) {
    /** "You played them most on Sunday evenings", "You mostly play them in the evening", … */
    fun sentence(locale: Locale = Locale.getDefault()): String? {
        val dayName = day?.getDisplayName(TextStyle.FULL, locale)
        return when {
            dayName != null && part != null -> "You played them most on $dayName ${part.plural}"
            dayName != null -> "You played them most on ${dayName}s"
            part != null -> "You mostly play them ${part.phrase}"
            else -> null
        }
    }
}

/** Everything the artist page shows about the user's own listening to one artist. */
data class ArtistHistory(
    /** Plays of at least [PlayStats.MIN_LISTEN_MS]. */
    val plays: Int,
    /** All listening time, including short plays. */
    val listenedMs: Long,
    val firstListened: Long?,
    val lastPlayed: Long?,
    val topTracks: List<ArtistTopTrack>,
    /** The last 12 months, oldest first, ending with the current month. */
    val months: List<MonthMinutes>,
    val pattern: ListeningPattern?,
) {
    val isEmpty: Boolean get() = plays == 0 && listenedMs < PlayStats.MIN_LISTEN_MS
}

object ArtistHistories {
    /** Fewer counted plays than this and no listening pattern is claimed. */
    const val PATTERN_MIN_PLAYS = 6
    const val TOP_TRACKS = 5
    const val MONTHS = 12

    /**
     * Builds the history of [artistKey] from [events] (any artists; filtered here). Night plays
     * after midnight count towards the evening before ("Saturday nights" includes 1 am Sunday).
     */
    fun build(events: List<PlayEvent>, artistKey: String, now: Long, zone: ZoneId): ArtistHistory {
        val mine = events.filter { it.artistKey == artistKey && it.listenedMs > 0 }
        val counted = mine.filter { it.listenedMs >= PlayStats.MIN_LISTEN_MS }

        val top = counted.groupBy { it.trackId }
            .map { (id, ev) -> ArtistTopTrack(id, ev.size, ev.sumOf { it.listenedMs }) }
            .sortedWith(compareByDescending<ArtistTopTrack> { it.plays }.thenByDescending { it.listenedMs })
            .take(TOP_TRACKS)

        val thisMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val first = thisMonth.minusMonths((MONTHS - 1).toLong())
        val perMonth = HashMap<YearMonth, Long>()
        for (e in mine) {
            val m = YearMonth.from(Instant.ofEpochMilli(e.startedAt).atZone(zone))
            if (m < first || m > thisMonth) continue
            perMonth[m] = (perMonth[m] ?: 0L) + e.listenedMs
        }
        val months = (0 until MONTHS).map { i ->
            val m = first.plusMonths(i.toLong())
            MonthMinutes(m, ((perMonth[m] ?: 0L) / 60_000L).toInt())
        }

        return ArtistHistory(
            plays = counted.size,
            listenedMs = mine.sumOf { it.listenedMs },
            firstListened = mine.minOfOrNull { it.startedAt },
            lastPlayed = mine.maxOfOrNull { it.startedAt },
            topTracks = top,
            months = months,
            pattern = pattern(counted, zone),
        )
    }

    /**
     * The dominant weekday and part of the day, each only when it clearly stands out (a weekday
     * with at least 30 % of plays and twice the average; a part of the day with at least 45 %).
     */
    fun pattern(plays: List<PlayEvent>, zone: ZoneId): ListeningPattern? {
        if (plays.size < PATTERN_MIN_PLAYS) return null
        val days = IntArray(7)
        val parts = IntArray(DayPart.entries.size)
        for (e in plays) {
            val t = Instant.ofEpochMilli(e.startedAt).atZone(zone)
            val part = DayPart.of(t.hour)
            val day = if (part == DayPart.NIGHT && t.hour < 5) t.dayOfWeek.minus(1) else t.dayOfWeek
            days[day.value - 1]++
            parts[part.ordinal]++
        }
        val n = plays.size.toFloat()
        val topDay = days.indices.maxByOrNull { days[it] }!!
        val topPart = parts.indices.maxByOrNull { parts[it] }!!
        val dayShare = days[topDay] / n
        val partShare = parts[topPart] / n
        val day = if (dayShare >= 0.3f && days[topDay] >= 2 * (n / 7f) && days.count { it == days[topDay] } == 1) DayOfWeek.of(topDay + 1) else null
        val part = if (partShare >= 0.45f && parts.count { it == parts[topPart] } == 1) DayPart.entries[topPart] else null
        if (day == null && part == null) return null
        return ListeningPattern(day, part)
    }
}
