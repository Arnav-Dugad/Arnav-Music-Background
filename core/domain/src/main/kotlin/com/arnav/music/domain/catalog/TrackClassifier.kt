package com.arnav.music.domain.catalog

import com.arnav.music.domain.model.MediaVariant

/**
 * Classifies YouTube uploads so recommendations stay to real singles: mixes, mashups, jukeboxes,
 * "1 hour" loops and generic mood compilations are detected from title and duration.
 */
object TrackClassifier {
    private val compilationPatterns = listOf(
        """\bmix\b""", """\bmixes\b""", """mash\s?-?ups?""", """\bmegamix\b""", """non\s?-?stop""", """\bjukebox\b""",
        """\bcompilation\b""", """\bmedley\b""", """\bplaylist\b""", """back\s?to\s?back""", """\bb2b\b""", """full\s+album""",
        """album\s+collection""", """\bdj\s+set\b""", """\blive\s+set\b""", """\b\d+\s*(hours?|hrs?)\b""", """\b(one|two|three)\s+hours?\b""",
        """\btop\s+\d+\b""", """\btop\s+(hits|songs|tracks)\b""", """\bbest\s+of\b""", """\bhits\s+(of\s+)?(19|20)\d\d\b""",
        """\b(19|20)\d\d\s+(hits|mix|songs)\b""", """\bsongs\s+collection\b""", """\ball\s+songs\b""", """\bvideo\s+songs\s+jukebox\b""",
        """\b(workout|gym|motivation(al)?|study(ing)?|relaxing|sleep(ing)?|focus|meditation|background|lofi|lo-fi|chill|party|club|driving|coding)\s+(music|songs|beats|playlist)\b""",
        """\bmusic\s+for\s+(studying|sleep|work|focus|relaxation|coding)\b""", """\bradio\s*(24/7|live)\b""", """\b24/7\b""",
        """\bthrowback\s+(songs|hits)\b""", """\bsuper\s*hit\s+songs\b""", """\bplaylist\s+\d+\b""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** Longest plausible single. Above this, an upload is almost always a mix or long-form set. */
    const val MAX_SINGLE_MS = 13 * 60_000L
    const val MIN_SINGLE_MS = 45_000L

    fun isCompilation(title: String, durationMs: Long?): Boolean {
        if (durationMs != null && durationMs > MAX_SINGLE_MS) return true
        return compilationPatterns.any { it.containsMatchIn(title) }
    }

    fun isSingle(title: String, durationMs: Long?): Boolean =
        !isCompilation(title, durationMs) && (durationMs == null || durationMs >= MIN_SINGLE_MS)

    private val songHints = Regex("""\b(official\s+audio|audio\s+song|full\s+audio|\(audio\)|\[audio\]|art\s+track)\b""", RegexOption.IGNORE_CASE)
    private val videoHints = Regex("""\b(official\s+(music\s+)?video|music\s+video|lyric(al)?\s+video|full\s+video(\s+song)?|video\s+song|m/v|\bmv\b)\b""", RegexOption.IGNORE_CASE)

    /** SONG = audio/art-track upload (e.g. "Artist - Topic"), VIDEO = music/lyric video, null = unknown. */
    fun variant(channelTitle: String, rawTitle: String): MediaVariant? = when {
        channelTitle.trim().endsWith("- Topic", ignoreCase = true) -> MediaVariant.SONG
        songHints.containsMatchIn(rawTitle) -> MediaVariant.SONG
        videoHints.containsMatchIn(rawTitle) || channelTitle.trim().endsWith("VEVO", ignoreCase = true) -> MediaVariant.VIDEO
        else -> null
    }

    /** Similarity of two song titles (0..1) for matching the same song across uploads. */
    fun titleSimilarity(a: String, b: String): Float {
        fun tokens(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}\s]"""), " ").split(Regex("""\s+""")).filter { it.length > 1 }.toSet()
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return ta.intersect(tb).size.toFloat() / minOf(ta.size, tb.size)
    }
}

/** A real, single song suitable for recommendations (not a mix, mashup or long-form set). */
fun com.arnav.music.domain.model.Track.isSingle(): Boolean =
    !compilation && TrackClassifier.isSingle(listOfNotNull(title, album).joinToString(" "), durationMs)

/**
 * Orders search results like YouTube Music: singles first, the preferred upload type (official
 * "Topic" art tracks for songs, music videos for videos) ahead of others, original order otherwise.
 */
fun rankForListening(tracks: List<com.arnav.music.domain.model.Track>, prefer: com.arnav.music.domain.model.MediaVariant): List<com.arnav.music.domain.model.Track> =
    tracks.withIndex().sortedWith(
        compareBy<IndexedValue<com.arnav.music.domain.model.Track>>(
            { if (it.value.isSingle()) 0 else 1 },
            { when (it.value.variant) { prefer -> 0; null -> 1; else -> 2 } },
            { it.index },
        ),
    ).map { it.value }
