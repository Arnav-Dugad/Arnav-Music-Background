package com.arnav.music.domain.lyrics

/**
 * Backing vocals written in parentheses, the way most lyric sites and LRC files mark them:
 * "I'm on my way (on my way)" is the lead "I'm on my way" with the backing "on my way", and a line
 * that is entirely "(Oh, oh, oh)" is backing vocals only. Leading and trailing parenthetical
 * groups count (full-width "（…）" too); parentheses in the middle of a line stay in the lead text.
 * Section labels such as "(Chorus)" or "(x2)" are never treated as vocals.
 */
object BackgroundVocals {
    /** The lead part of a line, its backing vocals (null when none), and the words of each. */
    class Split(
        val text: String,
        val words: List<LyricWord>,
        val background: String?,
        val backgroundWords: List<LyricWord>,
    )

    private const val OPEN = "(（"
    private const val CLOSE = ")）"

    /**
     * Splits [text] into lead and backing vocals. Timed [words] (enhanced LRC) are divided too, their
     * parentheses removed; when a word can't be placed in the text, the line is left as it is.
     */
    fun split(text: String, words: List<LyricWord> = emptyList()): Split {
        val none = Split(text, words, null, emptyList())
        if (text.isBlank() || LyricAligner.isSectionLabel(text)) return none
        var lo = 0
        var hi = text.length
        while (lo < hi && text[lo].isWhitespace()) lo++
        while (hi > lo && text[hi - 1].isWhitespace()) hi--

        val leading = ArrayList<IntRange>()
        while (lo < hi && text[lo] in OPEN) {
            val close = matchForward(text, lo, hi) ?: break
            val content = lo + 1 until close
            if (!isVocal(text, content)) break
            leading += content
            lo = close + 1
            while (lo < hi && text[lo].isWhitespace()) lo++
        }
        val trailing = ArrayList<IntRange>()
        while (hi > lo && text[hi - 1] in CLOSE) {
            val open = matchBackward(text, hi - 1, lo) ?: break
            val content = open + 1 until hi - 1
            if (!isVocal(text, content)) break
            trailing.add(0, content)
            hi = open
            while (hi > lo && text[hi - 1].isWhitespace()) hi--
        }
        val groups = leading + trailing
        if (groups.isEmpty()) return none

        val lead = text.substring(lo, hi).trim()
        val background = groups.joinToString(" ") { text.substring(it.first, it.last + 1).trim() }
        if (words.isEmpty()) return Split(lead, emptyList(), background, emptyList())

        val leadWords = ArrayList<LyricWord>()
        val bgWords = ArrayList<LyricWord>()
        var cursor = 0
        for (w in words) {
            if (w.text.isEmpty()) continue
            val at = text.indexOf(w.text, cursor)
            if (at < 0) return none
            val end = at + w.text.length
            cursor = end
            val inLead = at < hi && end > lo
            if (inLead) {
                // A word straddling lead and backing text: leave the line alone.
                if (at < lo || end > hi) return none
                leadWords += w
            } else {
                val clean = w.text.filterNot { it in OPEN || it in CLOSE }.trim()
                if (clean.isNotEmpty()) bgWords += w.copy(text = clean)
            }
        }
        return Split(lead, leadWords, background, bgWords)
    }

    /** A parenthetical with something to sing in it (not a label such as "(Chorus)" or "(x2)"). */
    private fun isVocal(text: String, content: IntRange): Boolean {
        if (content.isEmpty()) return false
        val inner = text.substring(content.first, content.last + 1).trim()
        if (inner.none { it.isLetter() }) return false
        return !LyricAligner.isSectionLabel("($inner)") && !LyricAligner.isSectionLabel(inner)
    }

    /** Index of the bracket closing the one at [open], searching before [limit]; null when unbalanced. */
    private fun matchForward(text: String, open: Int, limit: Int): Int? {
        var depth = 0
        for (i in open until limit) {
            val c = text[i]
            if (c in OPEN) depth++
            else if (c in CLOSE) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    /** Index of the bracket opening the one at [close], searching from [floor]; null when unbalanced. */
    private fun matchBackward(text: String, close: Int, floor: Int): Int? {
        var depth = 0
        for (i in close downTo floor) {
            val c = text[i]
            if (c in CLOSE) depth++
            else if (c in OPEN) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }
}
