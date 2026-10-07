package com.arnav.music.domain.chapters

/**
 * Chapters written as timestamps in a video description, following YouTube's own rules: at
 * least [MIN_ENTRIES] timestamps, the first one at 0:00, in ascending order.
 *
 * Understood line shapes (one chapter per line):
 * - `0:00 Intro`, `12:34 - Song name`, `1:02:03 Title`, `[00:00] Title`, `(0:00) Title`
 * - `1. 0:00 Title` (numbered lists), `0:00 - 3:45 Title` (ranges; the start is used)
 * - `Title - 0:00` (timestamp at the end of the line)
 *
 * A timestamp in the middle of prose ("my favourite part is at 3:45 lol") is ignored. When a
 * description holds several lists, the first valid one wins.
 */
object DescriptionChapters {
    const val MIN_ENTRIES = 3

    private val TIMESTAMP = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,3}):([0-5]\d)(?![\d:])""")
    private const val LEADING_JUNK = " \t-–—:|•·.,)]}>~*_=→»"
    private const val TRAILING_JUNK = " \t-–—:|•·,([{<~*_=←«"

    /** Parses [description]; returns an empty list when it holds no valid chapter list. */
    fun parse(description: String, durationMs: Long? = null): List<Chapter> {
        if (description.isBlank()) return emptyList()
        val entries = description.lineSequence().take(MAX_LINES).mapNotNull(::parseLine).toList()
        if (entries.size < MIN_ENTRIES) return emptyList()

        var chosen: List<Chapter>? = null
        var current = ArrayList<Chapter>()
        fun close() {
            if (chosen == null && current.size >= MIN_ENTRIES) chosen = current
            current = ArrayList()
        }
        for (e in entries) {
            when {
                e.startMs == 0L -> { close(); current.add(e) }
                current.isEmpty() -> Unit // not inside a list that started at 0:00
                e.startMs > current.last().startMs -> current.add(e)
                else -> close() // out of order: this list is over
            }
            if (chosen != null) break
        }
        close()
        val list = chosen ?: return emptyList()
        return ChapterMath.normalize(list, durationMs, minCount = MIN_ENTRIES)
    }

    /** Converts h/m/s groups to milliseconds; null for impossible values (e.g. 1:75:00). */
    fun toMillis(match: MatchResult): Long? {
        val h = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val m = match.groupValues[2].toLongOrNull() ?: return null
        val s = match.groupValues[3].toLongOrNull() ?: return null
        if (h != null && m >= 60) return null
        return ((h ?: 0L) * 3600L + m * 60L + s) * 1000L
    }

    private fun parseLine(raw: String): Chapter? {
        val line = raw.trim()
        if (line.isEmpty() || line.length > MAX_LINE) return null
        val match = TIMESTAMP.find(line) ?: return null
        val start = toMillis(match) ?: return null
        val before = line.substring(0, match.range.first)
        val after = line.substring(match.range.last + 1)
        val title = when {
            isPrefix(before) -> stripRangeEnd(after)
            after.none { it.isLetterOrDigit() } -> before
            else -> return null
        }
        return Chapter(start, clean(title))
    }

    /** Text allowed before a leading timestamp: bullets, brackets, emoji and list numbers ("1.", "01)"). */
    private fun isPrefix(s: String): Boolean = s.length <= 8 && s.none { it.isLetter() }

    /** "0:00 - 3:45 Title" → "Title". */
    private fun stripRangeEnd(after: String): String {
        val t = after.trimStart(*LEADING_JUNK.toCharArray())
        val m = TIMESTAMP.find(t) ?: return after
        if (m.range.first != 0) return after
        return t.substring(m.range.last + 1)
    }

    private fun clean(title: String): String =
        title.trimStart(*LEADING_JUNK.toCharArray()).trimEnd(*TRAILING_JUNK.toCharArray()).trim()

    private const val MAX_LINES = 2_000
    private const val MAX_LINE = 300
}
