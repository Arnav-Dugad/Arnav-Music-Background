package com.arnav.music.domain.stats

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One listen as exported. */
data class HistoryRow(
    val startedAt: Long,
    val title: String,
    val artist: String,
    val album: String?,
    /** "YOUTUBE", "LOCAL", … */
    val source: String,
    val listenedMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
    /** Where the song was skipped, when that was recorded. */
    val skipPositionMs: Long?,
)

/** RFC 4180 CSV: comma-separated, CRLF line ends, fields quoted when needed with quotes doubled. */
object StatsCsv {
    val HISTORY_HEADER = listOf(
        "played_at", "title", "artist", "album", "source",
        "listened_seconds", "track_length_seconds", "completed", "skipped", "skip_position_seconds",
    )

    /** Quotes [field] when it holds a comma, quote, CR or LF (or leading/trailing spaces); doubles inner quotes. */
    fun escape(field: String): String {
        val needs = field.any { it == ',' || it == '"' || it == '\r' || it == '\n' } ||
            (field.isNotEmpty() && (field.first() == ' ' || field.last() == ' '))
        return if (needs) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }

    /**
     * Free text from the internet (titles, artists) can start with `=`, `+`, `-`, `@`, tab or CR, which
     * spreadsheet apps would run as a formula. Such cells get a leading apostrophe (OWASP CSV-injection advice).
     */
    fun safeText(text: String): String =
        if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text

    fun line(fields: List<String>): String = fields.joinToString(",") { escape(it) } + "\r\n"

    /** ISO-8601 local date-time with the UTC offset, to the second: 2026-10-06T21:14:03+05:30. */
    fun timestamp(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Whole seconds with up to one decimal ("183", "4.5"), ASCII digits whatever the locale. */
    fun seconds(ms: Long): String {
        val tenths = Math.round(ms / 100.0)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }

    fun historyFields(r: HistoryRow, zone: ZoneId): List<String> = listOf(
        timestamp(r.startedAt, zone),
        safeText(r.title),
        safeText(r.artist),
        safeText(r.album.orEmpty()),
        r.source.lowercase(),
        seconds(r.listenedMs),
        r.durationMs?.takeIf { it > 0 }?.let(::seconds).orEmpty(),
        r.completed.toString(),
        r.skipped.toString(),
        r.skipPositionMs?.let(::seconds).orEmpty(),
    )

    /**
     * Writes the header and one line per row. With [byteOrderMark] the file starts with U+FEFF so
     * spreadsheet apps read non-Latin titles as UTF-8.
     */
    fun writeHistory(rows: Iterable<HistoryRow>, zone: ZoneId, out: Appendable, byteOrderMark: Boolean = true) {
        if (byteOrderMark) out.append('﻿')
        out.append(line(HISTORY_HEADER))
        for (r in rows) out.append(line(historyFields(r, zone)))
    }
}
