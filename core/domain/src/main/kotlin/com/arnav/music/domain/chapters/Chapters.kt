package com.arnav.music.domain.chapters

/** One chapter of a long track (a DJ mix, a live set, an album upload, an audiobook part). */
data class Chapter(val startMs: Long, val title: String)

/** Pure helpers shared by the player UI, the media session and the parsers. */
object ChapterMath {
    /** A "previous chapter" press this far into a chapter restarts it instead of going back. */
    const val RESTART_WINDOW_MS = 3_000L

    /** Index of the chapter playing at [positionMs]; -1 when empty or before the first chapter. */
    fun activeIndex(chapters: List<Chapter>, positionMs: Long): Int {
        if (chapters.isEmpty() || positionMs < chapters[0].startMs) return -1
        var lo = 0
        var hi = chapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (chapters[mid].startMs <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Where "previous chapter" should seek: the start of the current chapter when more than
     * [restartWindowMs] into it, otherwise the start of the chapter before. Null when empty.
     */
    fun previousStart(chapters: List<Chapter>, positionMs: Long, restartWindowMs: Long = RESTART_WINDOW_MS): Long? {
        if (chapters.isEmpty()) return null
        val i = activeIndex(chapters, positionMs)
        if (i < 0) return chapters[0].startMs
        val current = chapters[i].startMs
        return when {
            positionMs - current > restartWindowMs -> current
            i > 0 -> chapters[i - 1].startMs
            else -> current
        }
    }

    /** Where "next chapter" should seek, or null when already in the last chapter. */
    fun nextStart(chapters: List<Chapter>, positionMs: Long): Long? {
        if (chapters.isEmpty()) return null
        val next = activeIndex(chapters, positionMs) + 1
        return chapters.getOrNull(next)?.startMs
    }

    /**
     * Sorts, de-duplicates and tidies raw chapters: drops negative starts and starts at or past
     * [durationMs] (when known), trims titles and names untitled ones "Chapter n". Fewer than
     * [minCount] usable chapters returns an empty list (a single chapter is no navigation at all).
     */
    fun normalize(raw: List<Chapter>, durationMs: Long? = null, minCount: Int = 2): List<Chapter> {
        val limit = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val sorted = raw.asSequence()
            .filter { it.startMs in 0 until limit }
            .sortedBy { it.startMs }
            .toList()
        val out = ArrayList<Chapter>(sorted.size)
        for (c in sorted) {
            if (out.isNotEmpty() && out.last().startMs == c.startMs) continue
            out += c
        }
        if (out.size < minCount) return emptyList()
        return out.mapIndexed { i, c ->
            val title = c.title.replace(Regex("""\s+"""), " ").trim().take(MAX_TITLE)
            Chapter(c.startMs, title.ifEmpty { "Chapter ${i + 1}" })
        }
    }

    internal const val MAX_TITLE = 120
}
