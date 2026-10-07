package com.arnav.music.domain.search

import java.text.Normalizer

/**
 * Normalises search input so that "  Daft PUNK!! " and "daft punk" share one cache entry —
 * every cache hit is a YouTube search (100 quota units) not spent.
 */
object QueryNormalizer {
    private val diacritics = Regex("""\p{Mn}+""")
    private val punctuation = Regex("""[^\p{L}\p{N}\s&']""")
    private val spaces = Regex("""\s+""")
    private val noise = setOf("official", "video", "audio", "lyrics", "lyric", "hd", "4k", "mv")

    const val MIN_REMOTE_LENGTH = 2
    const val DEBOUNCE_MS = 650L

    fun normalize(raw: String): String {
        val decomposed = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(diacritics, "")
            .replace(punctuation, " ")
            .replace(spaces, " ")
            .trim()
    }

    /** Cache key: normalised, with noise words dropped and tokens kept in order. */
    fun cacheKey(raw: String, filter: String = "all"): String {
        val tokens = normalize(raw).split(' ').filter { it.isNotEmpty() && it !in noise }
        return filter + "|" + tokens.joinToString(" ")
    }

    fun isRemoteWorthy(raw: String): Boolean {
        val n = normalize(raw)
        return n.length >= MIN_REMOTE_LENGTH && n.any { it.isLetterOrDigit() }
    }

    /** Local fuzzy match score 0..1 used for instant suggestions from cache/library. */
    fun matchScore(query: String, candidate: String): Float {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0f
        if (c == q) return 1f
        if (c.startsWith(q)) return 0.9f
        val words = c.split(' ')
        if (words.any { it.startsWith(q) }) return 0.75f
        if (c.contains(q)) return 0.6f
        val qt = q.split(' ').filter { it.isNotEmpty() }
        val hits = qt.count { t -> words.any { it.startsWith(t) } }
        val prefix = if (qt.isEmpty()) 0f else 0.5f * hits / qt.size
        val fuzzyHits = qt.count { t -> t.length >= 4 && words.any { w -> editDistance(t, w) <= (if (t.length >= 8) 2 else 1) } }
        return maxOf(prefix, if (qt.isNotEmpty() && fuzzyHits == qt.size) 0.55f else 0f)
    }
    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2 || a.length > 80 || b.length > 80) return 99
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
