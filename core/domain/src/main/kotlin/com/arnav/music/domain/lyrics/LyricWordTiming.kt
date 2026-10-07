package com.arnav.music.domain.lyrics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Estimated word timing for lines that only have a start time (auto-timed plain lyrics, ordinary
 * line-synced LRC), so they can fill word by word too.
 *
 * Each line is sung during the first part of its span (about [MS_PER_SYLLABLE] per syllable, at
 * least 55% and at most 92% of the time until the next line). Inside that stretch every word gets
 * time in proportion to its syllables ([LyricAligner.syllables]); a word ending a phrase (comma,
 * full stop…) is held a little longer and followed by a short breath, and the last word is held
 * longer still. Text without spaces in Chinese/Japanese script is timed per character.
 *
 * Lines that already carry word timing, and lines of backing vocals only, are left alone. The
 * results are marked [LyricLine.estimated] so they can be drawn as a softer sweep and are never
 * saved as real word timing. Pure and deterministic.
 */
object LyricWordTiming {
    const val MS_PER_SYLLABLE = 280L
    private const val LEAD_IN_MS = 250L
    private const val MIN_SUNG_SHARE = 0.55
    private const val MAX_SUNG_SHARE = 0.92
    private const val PHRASE_HOLD = 0.35
    private const val PHRASE_BREATH = 0.4
    private const val LAST_HOLD = 0.6

    private const val PHRASE_END = ",;:.!?…—–、，。！？；："

    /** [lines] with estimated words for every sung line that has none; earlier estimates are redone. */
    fun estimate(lines: List<LyricLine>): List<LyricLine> {
        if (lines.none { it.needsEstimate() }) return lines
        return lines.map { if (it.needsEstimate()) estimateLine(it) else it }
    }

    private fun LyricLine.needsEstimate(): Boolean = text.isNotBlank() && (estimated || words.isEmpty())

    /**
     * One line with estimated [LyricLine.words]. Backing vocals are left as they are: untimed ones
     * follow the lead (see [LyricsTiming.backgroundSpan]).
     */
    fun estimateLine(line: LyricLine): LyricLine {
        if (line.text.isBlank()) return line
        return line.copy(words = spread(line.text, line.startMs, line.endMs), estimated = true)
    }

    /** Words of [text] spread over the sung part of [startMs]..[endMs]. */
    fun spread(text: String, startMs: Long, endMs: Long): List<LyricWord> {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return emptyList()
        val span = max(0L, endMs - startMs).toDouble()
        val syllables = tokens.map { max(1, LyricAligner.syllables(it)) }
        val natural = (syllables.sum() * MS_PER_SYLLABLE + LEAD_IN_MS).toDouble()
        val sung = min(span * MAX_SUNG_SHARE, max(natural, span * MIN_SUNG_SHARE))
        val hold = DoubleArray(tokens.size)
        val breath = DoubleArray(tokens.size)
        for (i in tokens.indices) {
            val last = i == tokens.lastIndex
            val phraseEnd = tokens[i].last() in PHRASE_END
            hold[i] = syllables[i] + when {
                last -> LAST_HOLD
                phraseEnd -> PHRASE_HOLD
                else -> 0.0
            }
            breath[i] = if (!last && phraseEnd) PHRASE_BREATH else 0.0
        }
        val units = hold.sum() + breath.sum()
        val perUnit = if (units > 0) sung / units else 0.0
        val out = ArrayList<LyricWord>(tokens.size)
        var t = startMs.toDouble()
        for (i in tokens.indices) {
            val s = t.roundToLong()
            t += hold[i] * perUnit
            val e = t.roundToLong().coerceAtLeast(s)
            out += LyricWord(s, e, tokens[i])
            t += breath[i] * perUnit
        }
        return out
    }

    /**
     * Words as they appear in [text]: whitespace-separated, with each Chinese/Japanese character its
     * own unit (punctuation stays with the unit before it).
     */
    internal fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (word in text.split(' ', '\t', '\n', '　')) {
            if (word.isEmpty()) continue
            var cur = StringBuilder()
            var curIsCjk = false
            var curHasLetter = false
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                i += Character.charCount(cp)
                val cjk = isCjk(cp)
                val letter = Character.isLetterOrDigit(cp)
                val type = Character.getType(cp)
                val opening = type == Character.START_PUNCTUATION.toInt() || type == Character.INITIAL_QUOTE_PUNCTUATION.toInt()
                // Opening punctuation ("「") goes with the unit that follows it.
                if (curHasLetter && (cjk || (curIsCjk && (letter || opening)))) {
                    out += cur.toString()
                    cur = StringBuilder()
                    curHasLetter = false
                }
                if (!curHasLetter && letter) curIsCjk = cjk
                curHasLetter = curHasLetter || letter
                cur.appendCodePoint(cp)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }

    private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}
