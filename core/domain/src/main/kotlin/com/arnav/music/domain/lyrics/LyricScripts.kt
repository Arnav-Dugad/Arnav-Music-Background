package com.arnav.music.domain.lyrics

/** How a lyric line should be romanised (see the app's Romanizer). */
enum class RomanizationMode {
    /** Already Latin (or no letters): nothing to show. */
    NONE,

    /** Any non-Latin script → Latin → plain ASCII (Devanagari, Cyrillic, Hangul, Arabic, Thai…). */
    GENERAL,

    /** Japanese: kana → rōmaji; kanji are left as written (no reading dictionary on device). */
    JAPANESE,

    /** Chinese characters → pinyin. */
    CHINESE,
}

/** Script detection for lyric lines, used for romanisation and to skip lines already in Latin. */
object LyricScripts {
    /** True when the text has no letters outside the Latin script (digits/punctuation don't count). */
    fun isLatin(text: String): Boolean = nonLatinLetters(text) == 0

    /** Letters that are not Latin (Common/Inherited code points such as punctuation are ignored). */
    fun nonLatinLetters(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN,
                Character.UnicodeScript.COMMON,
                Character.UnicodeScript.INHERITED,
                -> Unit
                else -> count++
            }
        }
        return count
    }

    fun hasKana(text: String): Boolean = anyScript(text, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

    fun hasHan(text: String): Boolean = anyScript(text, Character.UnicodeScript.HAN)

    /** True when any line of the song contains kana, i.e. the song is (at least partly) Japanese. */
    fun isJapaneseSong(lines: List<String>): Boolean = lines.any { hasKana(it) }

    /**
     * The romanisation for one line. [japaneseSong] (from [isJapaneseSong]) makes kanji-only lines of
     * a Japanese song use the Japanese mode instead of Chinese pinyin.
     */
    fun modeFor(line: String, japaneseSong: Boolean): RomanizationMode {
        if (line.isBlank() || isLatin(line)) return RomanizationMode.NONE
        val kana = hasKana(line)
        val han = hasHan(line)
        return when {
            kana || (han && japaneseSong) -> RomanizationMode.JAPANESE
            han -> RomanizationMode.CHINESE
            else -> RomanizationMode.GENERAL
        }
    }

    /** Whether [romanized] adds something over [original] (e.g. a kanji-only line stays unchanged). */
    fun isUseful(original: String, romanized: String?): Boolean {
        if (romanized.isNullOrBlank()) return false
        if (normalize(romanized) == normalize(original)) return false
        // Something must actually have been converted to Latin.
        return nonLatinLetters(romanized) < nonLatinLetters(original)
    }

    /** Whether a translation differs meaningfully from the line (ignoring case, spaces, punctuation). */
    fun differs(original: String, translated: String?): Boolean =
        !translated.isNullOrBlank() && normalize(original) != normalize(translated)

    private fun normalize(s: String): String = buildString(s.length) {
        for (ch in s) if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
    }

    private fun anyScript(text: String, vararg scripts: Character.UnicodeScript): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val s = Character.UnicodeScript.of(cp)
            if (scripts.any { it == s }) return true
        }
        return false
    }
}

/**
 * Spaces out network requests: [acquire] returns how long to wait (ms) before the next request so
 * that no more than one starts every [minIntervalMs], and reserves that slot.
 */
class RatePacer(private val minIntervalMs: Long) {
    private var nextAllowedAt = Long.MIN_VALUE

    fun acquire(nowMs: Long): Long {
        val start = if (nextAllowedAt == Long.MIN_VALUE) nowMs else maxOf(nowMs, nextAllowedAt)
        nextAllowedAt = start + minIntervalMs
        return start - nowMs
    }
}
