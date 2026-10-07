package com.arnav.music.domain.stats

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One listen for the clock: when it started, how long it ran and the genres it counts toward (empty = unknown). */
data class ClockPlay(val startedAt: Long, val listenedMs: Long, val genres: List<String>)

/** One hour of the day (local time). [genreMs] follows [ListeningClock.genres] and sums to [totalMs]. */
data class ClockHour(val hour: Int, val totalMs: Long, val genreMs: List<Long>)

/**
 * "When in the day you play which genres": listening time per local hour, split by genre.
 * Only the top few genres get their own colour; the rest (and songs without any genre hint) fold into
 * [OTHER], which is always last in [genres] when present.
 */
data class ListeningClock(
    /** Always 24 entries, midnight first. */
    val hours: List<ClockHour>,
    /** Legend: display names, most-listened first; [OTHER] last when present. */
    val genres: List<String>,
    /** Listening per legend genre, same order as [genres]. */
    val genreTotals: List<Long>,
    val totalMs: Long,
    /** "Mornings are Indie, late nights are Lo-fi" — null when there's too little genre information. */
    val summary: String?,
) {
    val isEmpty: Boolean get() = totalMs <= 0L
    val maxHourMs: Long get() = hours.maxOfOrNull { it.totalMs } ?: 0L
    val peakHour: Int? get() = hours.filter { it.totalMs > 0 }.maxByOrNull { it.totalMs }?.hour

    /** The biggest named genre in [hour] (falls back to [OTHER] only when nothing else was played). */
    fun dominantGenre(hour: Int): String? {
        val h = hours.getOrNull(hour) ?: return null
        if (h.totalMs <= 0) return null
        val named = h.genreMs.indices.filter { genres[it] != OTHER && h.genreMs[it] > 0 }.maxByOrNull { h.genreMs[it] }
        return named?.let { genres[it] } ?: OTHER
    }

    /** Spoken description for screen readers. */
    fun describe(): String {
        if (isEmpty) return "Listening clock: no listening yet."
        val peak = peakHour
        return buildString {
            append("Listening clock for the day.")
            if (peak != null) append(" Most listening around ${hourLabel(peak)}.")
            summary?.let { append(' ').append(it).append('.') }
            val legend = genres.indices.filter { genreTotals[it] > 0 }
                .joinToString(", ") { "${genres[it]} ${(genreTotals[it] * 100 / totalMs.coerceAtLeast(1))} percent" }
            if (legend.isNotEmpty()) append(" Genres: ").append(legend).append('.')
        }
    }

    /** A part of the day used for the summary sentence. */
    enum class Period(val plural: String, val hours: Set<Int>) {
        MORNING("mornings", (5..11).toSet()),
        AFTERNOON("afternoons", (12..16).toSet()),
        EVENING("evenings", (17..21).toSet()),
        LATE_NIGHT("late nights", setOf(22, 23, 0, 1, 2, 3, 4)),
    }

    companion object {
        const val OTHER = "Other"
        private const val HOUR_MS = 3_600_000L
        /** A single listen never spreads over more than this (protects against bogus rows). */
        private const val MAX_LISTEN_MS = 12 * HOUR_MS
        /** A part of the day needs this share of all listening to get a clause in the summary. */
        private const val PERIOD_MIN_SHARE = 0.12
        /** …and its top genre needs this share of that part of the day. */
        private const val DOMINANT_MIN_SHARE = 0.25

        fun hourLabel(h: Int): String = "%d %s".format(if (h % 12 == 0) 12 else h % 12, if (h < 12) "AM" else "PM")

        /**
         * Builds the clock. A listen that crosses an hour boundary is split across both hours; a song with
         * several genres shares its time between them equally.
         */
        fun build(plays: List<ClockPlay>, zone: ZoneId, maxGenres: Int = 5): ListeningClock {
            val perHour = Array(24) { HashMap<String, Double>() }
            for (p in plays) {
                if (p.listenedMs <= 0) continue
                val keys = p.genres.map(GenreNames::key).filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(OTHER_KEY) }
                var t = p.startedAt
                var remaining = p.listenedMs.coerceAtMost(MAX_LISTEN_MS)
                while (remaining > 0) {
                    val at = Instant.ofEpochMilli(t).atZone(zone)
                    val boundary = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                    val chunk = minOf(remaining, (boundary - t).coerceAtLeast(1))
                    val share = chunk.toDouble() / keys.size
                    keys.forEach { k -> perHour[at.hour].merge(k, share, Double::plus) }
                    t += chunk
                    remaining -= chunk
                }
            }
            val totals = HashMap<String, Double>()
            perHour.forEach { m -> m.forEach { (k, v) -> totals.merge(k, v, Double::plus) } }
            val top = totals.filterKeys { it != OTHER_KEY }.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .take(maxGenres.coerceAtLeast(0)).map { it.key }
            val hasOther = totals.keys.any { it !in top }
            val legendKeys = if (hasOther) top + OTHER_KEY else top
            fun slot(k: String) = if (k in top) top.indexOf(k) else legendKeys.lastIndex

            val hours = (0 until 24).map { h ->
                val ms = DoubleArray(legendKeys.size)
                perHour[h].forEach { (k, v) -> ms[slot(k)] += v }
                val rounded = ms.map { Math.round(it) }
                ClockHour(h, rounded.sum(), rounded)
            }
            val genreTotals = legendKeys.indices.map { i -> hours.sumOf { it.genreMs[i] } }
            val names = legendKeys.map { if (it == OTHER_KEY) OTHER else GenreNames.display(it) }
            val total = hours.sumOf { it.totalMs }
            val partial = ListeningClock(hours, names, genreTotals, total, null)
            return partial.copy(summary = summarize(partial))
        }

        /** "Mornings are Indie, late nights are Lo-fi". Periods with the same top genre are merged. */
        internal fun summarize(clock: ListeningClock): String? {
            if (clock.isEmpty) return null
            val named = clock.genres.indices.filter { clock.genres[it] != OTHER }
            if (named.isEmpty()) return null
            val winners = Period.entries.mapNotNull { p ->
                val hrs = clock.hours.filter { it.hour in p.hours }
                val periodMs = hrs.sumOf { it.totalMs }
                if (periodMs <= 0 || periodMs < clock.totalMs * PERIOD_MIN_SHARE) return@mapNotNull null
                val best = named.maxByOrNull { i -> hrs.sumOf { it.genreMs[i] } } ?: return@mapNotNull null
                val bestMs = hrs.sumOf { it.genreMs[best] }
                if (bestMs <= 0 || bestMs < periodMs * DOMINANT_MIN_SHARE) null else p to clock.genres[best]
            }
            if (winners.isEmpty()) return null
            val byGenre = LinkedHashMap<String, MutableList<Period>>()
            winners.forEach { (p, g) -> byGenre.getOrPut(g) { mutableListOf() }.add(p) }
            if (byGenre.size == 1 && winners.size >= 3) return "${byGenre.keys.first()}, morning to night"
            val sentence = byGenre.entries.take(3).joinToString(", ") { (g, periods) ->
                val who = when (periods.size) {
                    1 -> periods[0].plural
                    else -> periods.dropLast(1).joinToString(", ") { it.plural } + " and " + periods.last().plural
                }
                "$who are $g"
            }
            return sentence.replaceFirstChar { it.uppercaseChar() }
        }

        private const val OTHER_KEY = "\u0000other"
    }
}

/** Normalised genre keys ("lofi", "hip hop") and their display names ("Lo-fi", "Hip hop"). */
object GenreNames {
    private val special = mapOf(
        "lofi" to "Lo-fi", "lo-fi" to "Lo-fi", "edm" to "EDM", "r&b" to "R&B", "rnb" to "R&B", "hip hop" to "Hip hop",
        "k-pop" to "K-pop", "kpop" to "K-pop", "ost" to "Soundtrack",
    )

    fun key(raw: String): String = raw.trim().lowercase().replace(Regex("\\s+"), " ")

    fun display(key: String): String = special[key] ?: key.replaceFirstChar { it.uppercaseChar() }
}

/** What's known about one song's genres, for [GenreFallback]. */
data class GenreSource(val trackId: String, val artistKey: String, val genres: List<String>)

object GenreFallback {
    /**
     * Genres per track id. Songs without any genre hint borrow their artist's most common genres
     * (from the artist's other songs); songs with neither stay empty (shown as "Other").
     */
    fun resolve(tracks: List<GenreSource>, perTrack: Int = 2): Map<String, List<String>> {
        val byArtist = HashMap<String, HashMap<String, Int>>()
        for (t in tracks) {
            if (t.artistKey.isBlank()) continue
            t.genres.map(GenreNames::key).filter { it.isNotEmpty() }.distinct().forEach { g ->
                byArtist.getOrPut(t.artistKey) { HashMap() }.merge(g, 1, Int::plus)
            }
        }
        val artistTop = byArtist.mapValues { (_, counts) ->
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(perTrack).map { it.key }
        }
        return tracks.associate { t ->
            val own = t.genres.map(GenreNames::key).filter { it.isNotEmpty() }.distinct()
            t.trackId to own.ifEmpty { artistTop[t.artistKey].orEmpty() }
        }
    }
}
