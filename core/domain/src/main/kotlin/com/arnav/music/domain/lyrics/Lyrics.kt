package com.arnav.music.domain.lyrics

/** One sung word (or syllable) with absolute timing in milliseconds. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One displayed lyric line. A line with neither [text] nor [background] marks an instrumental break.
 *
 * [words] is non-empty for word-synced ("enhanced") lyrics, and for lines whose word timing was
 * estimated ([estimated]; see [LyricWordTiming]). [background] holds backing vocals sung with the
 * line (an enhanced-LRC `[bg: …]` line, or a parenthetical such as the "(on my way)" in
 * "I'm on my way (on my way)"); [text] is then the lead vocal only, and may be empty when the whole
 * line is backing vocals. [backgroundWords] times the backing vocals when known.
 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val background: String? = null,
    val backgroundWords: List<LyricWord> = emptyList(),
    /** True when [words] are spread by syllables ([LyricWordTiming]), not timed by the source. */
    val estimated: Boolean = false,
) {
    val isInstrumental: Boolean get() = text.isBlank() && background.isNullOrBlank()

    /** Lead and backing vocals as one readable line, e.g. "I'm on my way (on my way)". */
    val fullText: String get() {
        val bg = background?.takeIf { it.isNotBlank() } ?: return text
        return if (text.isBlank()) "($bg)" else "$text ($bg)"
    }
}

sealed interface Lyrics {
    /** Time-synced lyrics, sorted by [LyricLine.startMs]. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    /** Lyrics without timing. Blank entries separate stanzas. */
    data class Plain(val lines: List<String>) : Lyrics
}

/** Readable text lines regardless of timing (instrumental breaks become stanza gaps). */
fun Lyrics.displayLines(): List<String> = when (this) {
    is Lyrics.Plain -> lines
    is Lyrics.Synced -> {
        val out = ArrayList<String>(lines.size)
        for (l in lines) {
            if (l.isInstrumental) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
            } else out.add(l.fullText)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        out
    }
}

object LyricsTiming {
    /** Index of the line being sung at [positionMs]; -1 before the first line (or when empty). */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines[0].startMs) return -1
        var lo = 0
        var hi = lines.lastIndex
        // Largest index whose start <= position.
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lines[mid].startMs <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 0..1 progress through [line]. */
    fun lineProgress(line: LyricLine, positionMs: Long): Float = progress(line.startMs, line.endMs, positionMs)

    /** 0..1 progress through [word]. */
    fun wordProgress(word: LyricWord, positionMs: Long): Float = progress(word.startMs, word.endMs, positionMs)

    /** Backing vocals trail the lead slightly when they have no timing of their own. */
    const val BACKGROUND_DELAY_MS = 350L

    /**
     * Start and end (ms) of the backing vocals of [line]: their own word timing when known, else the
     * lead vocal's sung stretch (its words, or most of the line) delayed by [BACKGROUND_DELAY_MS] and
     * kept inside the line. A line of backing vocals only starts with the line.
     */
    fun backgroundSpan(line: LyricLine): LongRange {
        val bw = line.backgroundWords
        if (bw.isNotEmpty()) return bw.first().startMs..maxOf(bw.first().startMs, bw.last().endMs)
        val span = (line.endMs - line.startMs).coerceAtLeast(0L)
        val delay = if (line.text.isBlank()) 0L else minOf(BACKGROUND_DELAY_MS, span / 4)
        val leadEnd = line.words.lastOrNull()?.endMs?.takeIf { line.text.isNotBlank() }
            ?: (line.startMs + span * 85 / 100)
        val start = line.startMs + delay
        val end = (leadEnd + delay).coerceAtMost(line.endMs).coerceAtLeast(start)
        return start..end
    }

    /** 0..1 progress through the backing vocals of [line] (see [backgroundSpan]). */
    fun backgroundProgress(line: LyricLine, positionMs: Long): Float {
        val s = backgroundSpan(line)
        return progress(s.first, s.last, positionMs)
    }

    private fun progress(start: Long, end: Long, pos: Long): Float {
        if (pos <= start) return if (end <= start && pos >= start) 1f else 0f
        if (end <= start || pos >= end) return 1f
        return ((pos - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    }
}
