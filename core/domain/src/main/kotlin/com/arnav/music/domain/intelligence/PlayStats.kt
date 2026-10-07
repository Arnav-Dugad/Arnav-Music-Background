package com.arnav.music.domain.intelligence

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How often one song was played on this device. Only plays of at least [MIN_LISTEN_MS] count,
 * the same rule as streaks and milestones.
 */
data class PlayStats(val plays: Int, val firstPlayed: Long?, val lastPlayed: Long?) {
    val played: Boolean get() = plays > 0

    companion object {
        const val MIN_LISTEN_MS = Milestones.MIN_LISTEN_MS

        /** "Played 23 times · first on 4 Mar 2025", "Played once · on 4 Mar 2025", or "Not played yet". */
        fun line(stats: PlayStats?, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
            if (stats == null || !stats.played) return "Not played yet"
            val date = stats.firstPlayed?.let {
                Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
            }
            return when {
                stats.plays == 1 -> if (date != null) "Played once · on $date" else "Played once"
                date != null -> "Played ${stats.plays} times · first on $date"
                else -> "Played ${stats.plays} times"
            }
        }
    }
}
