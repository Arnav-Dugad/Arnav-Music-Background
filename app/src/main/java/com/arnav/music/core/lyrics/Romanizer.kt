package com.arnav.music.core.lyrics

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.arnav.music.domain.lyrics.LyricScripts
import com.arnav.music.domain.lyrics.RomanizationMode

/**
 * Romanisation of lyric lines with the platform's ICU transliterators (Android 10+, fully on device).
 *
 * - Most scripts (Devanagari, Bengali, Gurmukhi, Tamil, Telugu, Arabic, Cyrillic, Greek, Hangul,
 *   Thai, Hebrew…): `Any-Latin; Latin-ASCII`.
 * - Japanese: kana → rōmaji only. Kanji are left as written, because ICU has no reading
 *   dictionary (a known limitation: lines that are all kanji get no romanisation).
 * - Chinese: pinyin with tone marks (`Han-Latin`).
 * Lines already in Latin script are skipped.
 */
object Romanizer {
    /** Romanisation needs android.icu.text.Transliterator (API 29). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private const val GENERAL_ID = "Any-Latin; Latin-ASCII"
    private const val JAPANESE_ID = "Hiragana-Latin; Katakana-Latin; Latin-ASCII"
    private const val CHINESE_ID = "Han-Latin"

    private val cache = HashMap<String, Any?>()

    /**
     * Romanisations for [lines] (trimmed original text → romanised), only where it adds something.
     * Blocking but fast; call off the main thread for long songs.
     */
    fun romanize(lines: List<String>): Map<String, String> {
        if (!supported) return emptyMap()
        val japanese = LyricScripts.isJapaneseSong(lines)
        val out = HashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || out.containsKey(line)) continue
            val r = romanizeLine(line, japanese) ?: continue
            out[line] = r
        }
        return out
    }

    /** One line, or null when it's already Latin / nothing useful could be produced. */
    fun romanizeLine(line: String, japaneseSong: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val id = when (LyricScripts.modeFor(line, japaneseSong)) {
            RomanizationMode.NONE -> return null
            RomanizationMode.GENERAL -> GENERAL_ID
            RomanizationMode.JAPANESE -> JAPANESE_ID
            RomanizationMode.CHINESE -> CHINESE_ID
        }
        val result = Api29.transliterate(id, line)?.replace(Regex("\\s+"), " ")?.trim()
        return result?.takeIf { LyricScripts.isUseful(line, it) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun transliterate(id: String, text: String): String? {
            val t = synchronized(cache) {
                if (cache.containsKey(id)) {
                    cache[id] as? Transliterator
                } else {
                    val created = try { Transliterator.getInstance(id) } catch (e: Exception) { null }
                    cache[id] = created
                    created
                }
            } ?: return null
            // Transliterator instances aren't documented as thread-safe: one call at a time.
            return synchronized(t) {
                try { t.transliterate(text) } catch (e: Exception) { null }
            }
        }
    }
}
