package com.arnav.music.domain.format

object Formatters {
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "–:––"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun longDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h hr $m min"
            h > 0 -> "$h hr"
            else -> "$m min"
        }
    }

    fun compactCount(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9).replace(".0B", "B")
        n >= 1_000_000 -> "%.1fM".format(n / 1e6).replace(".0M", "M")
        n >= 1_000 -> "%.1fK".format(n / 1e3).replace(".0K", "K")
        else -> n.toString()
    }

    fun relative(then: Long, now: Long): String {
        val d = (now - then).coerceAtLeast(0)
        val min = d / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} hr ago"
            min < 2 * 24 * 60 -> "yesterday"
            min < 30 * 24 * 60 -> "${min / (24 * 60)} days ago"
            min < 365L * 24 * 60 -> "${min / (30 * 24 * 60)} mo ago"
            else -> "${min / (365L * 24 * 60)} yr ago"
        }
    }

    /** ISO-8601 duration from YouTube ("PT4M13S") → ms. */
    fun parseIsoDuration(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso.trim()) ?: return null
        val (d, h, mi, s) = m.destructured
        val total = (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return if (total == 0L) null else total * 1000
    }

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late night"
    }

    private val bracketNoise = Regex(
        """\s*[(\[](official|lyric|lyrics|lyrical|audio|video|music video|visualizer|hd|4k|mv|official audio|official music video|official video|full video|full song|video song|audio song)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val segmentSeparators = Regex("""\s+(?:\|\|?|‖|•)\s+""")
    /** " I " is used as a separator by many label channels — only trusted when it repeats. */
    private val capitalISeparator = Regex("""\s+I\s+""")
    private val trailingNoise = Regex(
        """\s*[-–:]?\s*\b(official\s+)?(full\s+)?(lyric(al)?\s+video|lyrics?\s+video|video\s+song|audio\s+song|full\s+song|music\s+video|official\s+video|official\s+audio|lyrical|lyrics)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    data class ParsedTitle(val artist: String, val title: String, val album: String?, val credits: String?)

    private val segmentNoise = Regex(
        """(#|\b(songs?|music|video|lyric(al|s)?|latest|new|hits|telugu|hindi|tamil|kannada|malayalam|punjabi|marathi|bengali|bollywood|tollywood|kollywood|full|hd|4k|official|trending|audio|jukebox|(19|20)\d\d)\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val movieWords = Regex("""\s*\b(movie|film|songs?|ost)\b\s*""", RegexOption.IGNORE_CASE)

    /** Cleans YouTube titles into (artist, title). See [parseYouTubeTitle]. */
    fun splitYouTubeTitle(raw: String, channel: String): Pair<String, String> =
        parseYouTubeTitle(raw, channel).let { it.artist to it.title }

    /**
     * "Daft Punk - Get Lucky (Official Video) [4K]" → artist Daft Punk, title Get Lucky.
     * Label uploads chain context: "Narayanamma Lyric Video I Aadarsha Kutumbam I Venkatesh, Shriya"
     * → title Narayanamma, album Aadarsha Kutumbam, credits Venkatesh, Shriya (artist = channel).
     */
    fun parseYouTubeTitle(raw: String, channel: String): ParsedTitle {
        val noBrackets = raw.replace(bracketNoise, "").replace(Regex("""\s+"""), " ").trim()
        var segments = noBrackets.split(segmentSeparators).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size == 1 && capitalISeparator.findAll(segments[0]).count() >= 2) segments = segments[0].split(capitalISeparator).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) segments = listOf(noBrackets.ifBlank { raw })

        fun clean(s: String): String { var c = s; repeat(2) { c = c.replace(trailingNoise, "").trim() }; return c }
        // The song is the first segment that survives noise removal ("Music Video | Song Name").
        var titleIndex = segments.indexOfFirst { clean(it).isNotBlank() }
        if (titleIndex < 0) titleIndex = 0
        val cleaned = clean(segments[titleIndex]).ifBlank { segments[titleIndex] }
        val extras = segments.drop(titleIndex + 1).filter { !segmentNoise.containsMatchIn(it) || movieWords.containsMatchIn(it) && it.split(' ').size <= 5 }
            .map { it.replace(movieWords, " ").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length in 2..60 && !segmentNoise.containsMatchIn(it) }

        val parts = cleaned.split(" - ", " – ", " — ", limit = 2)
        val artistFromChannel = channel.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace(Regex("""VEVO$"""), "").trim()
        val (artist, title) = if (parts.size == 2 && parts[0].length in 1..60 && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim()
        else artistFromChannel.ifBlank { "Unknown artist" } to cleaned.ifBlank { raw }
        val album = extras.firstOrNull { !it.contains(',') }
        val credits = extras.firstOrNull { it != album && (it.contains(',') || it.split(' ').size <= 4) }
        return ParsedTitle(artist, title, album, credits)
    }

    /** Human-readable byte sizes: 950 B, 12.4 KB, 6.1 MB, 1.20 GB. */
    fun bytes(n: Long): String = when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> "%.1f KB".format(n / 1e3)
        n < 1_000_000_000 -> "%.1f MB".format(n / 1e6)
        else -> "%.2f GB".format(n / 1e9)
    }
}
