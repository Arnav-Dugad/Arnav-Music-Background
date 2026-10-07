package com.arnav.music.domain.recommend

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * How much one listen says about taste. Positive = liked, negative = rejected.
 *
 * - finished: +1
 * - stopped part-way without skipping (paused, app closed): +0.2…+0.9 by how much was heard
 * - skipped in the first 30 s: −1 (a clear "not this")
 * - skipped later: −0.2 (heard a good part of it, still moved on)
 * - a replay (the same song again within the hour): +1 on top — the strongest positive there is
 */
object Engagement {
    const val EARLY_SKIP_MS = 30_000L
    const val REPLAY_WINDOW_MS = 60 * 60_000L

    const val COMPLETED = 1.0
    const val EARLY_SKIP = -1.0
    const val LATE_SKIP = -0.2
    const val REPLAY_BONUS = 1.0

    fun isEarlySkip(e: PlayEvent): Boolean = e.skipped && e.listenedMs < EARLY_SKIP_MS

    fun weight(e: PlayEvent, replay: Boolean = false): Double {
        val base = when {
            isEarlySkip(e) -> EARLY_SKIP
            e.skipped -> LATE_SKIP
            e.completed -> COMPLETED
            else -> 0.2 + 0.7 * e.completionRatio
        }
        return if (replay && !isEarlySkip(e)) base + REPLAY_BONUS else base
    }

    /**
     * Marks events that are replays: the same track started again within [REPLAY_WINDOW_MS] of a
     * previous listen of it. [events] must be sorted by start time.
     */
    fun replays(events: List<PlayEvent>): BooleanArray {
        val out = BooleanArray(events.size)
        val last = HashMap<TrackId, Long>()
        for ((i, e) in events.withIndex()) {
            val prev = last[e.trackId]
            if (prev != null && e.startedAt - prev <= REPLAY_WINDOW_MS) out[i] = true
            last[e.trackId] = e.startedAt + e.listenedMs
        }
        return out
    }
}

/** Exponential decay with a half-life in days. */
class Decay(val halfLifeDays: Double) {
    private val lambda = ln(2.0) / halfLifeDays
    fun factor(ageMs: Long): Double = exp(-lambda * max(0L, ageMs) / DAY_MS)

    companion object {
        const val DAY_MS = 86_400_000.0
        /** "What you're into this week." */
        val Short = Decay(7.0)
        /** "What you've loved over the last half year." */
        val Long = Decay(180.0)
    }
}

/** One listening session: plays separated by less than [Sessionizer.GAP_MS] of silence. */
data class ListeningSession(val events: List<PlayEvent>) {
    val start: Long get() = events.first().startedAt
    val end: Long get() = events.last().let { it.startedAt + it.listenedMs }
    val trackIds: List<TrackId> get() = events.map { it.trackId }
}

object Sessionizer {
    const val GAP_MS = 30 * 60_000L

    /** Splits [events] (any order) into sessions wherever the gap between plays exceeds [gapMs]. */
    fun split(events: List<PlayEvent>, gapMs: Long = GAP_MS): List<ListeningSession> {
        if (events.isEmpty()) return emptyList()
        val sorted = if (events.isSortedByStart()) events else events.sortedBy { it.startedAt }
        val out = ArrayList<ListeningSession>()
        var cur = ArrayList<PlayEvent>()
        var curEnd = Long.MIN_VALUE
        for (e in sorted) {
            if (cur.isNotEmpty() && e.startedAt - curEnd > gapMs) {
                out += ListeningSession(cur)
                cur = ArrayList()
            }
            cur += e
            curEnd = max(curEnd, e.startedAt + e.listenedMs)
        }
        if (cur.isNotEmpty()) out += ListeningSession(cur)
        return out
    }

    /** True while more plays could still join [session] (its last play ended less than [gapMs] ago). */
    fun isOpen(session: ListeningSession, now: Long, gapMs: Long = GAP_MS): Boolean = now - session.end <= gapMs

    private fun List<PlayEvent>.isSortedByStart(): Boolean {
        for (i in 1 until size) if (this[i].startedAt < this[i - 1].startedAt) return false
        return true
    }
}

enum class DayPart(val label: String) {
    MORNING("mornings"), AFTERNOON("afternoons"), EVENING("evenings"), NIGHT("late nights");

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 5..10 -> MORNING
            in 11..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** A listening context: part of the day × weekday/weekend (8 buckets). */
data class ListeningContext(val part: DayPart, val weekend: Boolean) {
    /** "weekday evenings", "weekend mornings". */
    val label: String get() = (if (weekend) "weekend " else "weekday ") + part.label
    val index: Int get() = part.ordinal * 2 + if (weekend) 1 else 0

    companion object {
        const val COUNT = 8

        fun at(epochMs: Long, zone: ZoneId): ListeningContext {
            val offset = zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds
            val local = Math.floorDiv(epochMs, 1000L) + offset
            var day = Math.floorDiv(local, 86_400L)
            val hour = ((local - day * 86_400L) / 3_600L).toInt()
            // A Saturday 1 am is still Friday night out; count late nights with the day they began.
            if (hour < 5) day -= 1
            // 1970-01-01 was a Thursday; 0 = Monday … 6 = Sunday.
            val dow = Math.floorMod(day + 3, 7L).toInt()
            val part = DayPart.of(hour)
            val weekend = dow >= 5 || (dow == 4 && part == DayPart.NIGHT)
            return ListeningContext(part, weekend)
        }

        fun fromIndex(i: Int) = ListeningContext(DayPart.entries[i / 2], i % 2 == 1)
    }
}
