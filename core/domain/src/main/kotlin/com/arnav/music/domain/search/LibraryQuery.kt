package com.arnav.music.domain.search
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track

data class LibraryQuery(val text: String, val artist: String? = null, val source: SourceType? = null,
    val years: IntRange? = null, val seconds: LongRange? = null, val structured: Boolean = false, val error: String? = null) {
    fun accepts(track: Track): Boolean = error == null &&
        (artist == null || QueryNormalizer.matchScore(artist, track.artist) >= 0.5f) &&
        (source == null || track.source == source) &&
        (years == null || track.year?.let { it in years } == true) &&
        (seconds == null || track.durationMs?.let { it / 1000 in seconds } == true)
    companion object {
        private val operator = Regex("\\b(artist|source|year|duration):(?:\"([^\"]*)\"|(\\S+))", RegexOption.IGNORE_CASE)
        fun parse(raw: String): LibraryQuery {
            val found = operator.findAll(raw).toList()
            val values = found.associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
            var error: String? = null
            fun range(name: String, min: Long, max: Long): LongRange? {
                val v = values[name] ?: return null
                val pieces = v.split("..")
                val start = pieces.firstOrNull()?.toLongOrNull()
                val end = pieces.lastOrNull()?.toLongOrNull()
                if (pieces.size !in 1..2 || start == null || end == null || start !in min..max || end !in start..max) {
                    error = "Invalid " + name + " filter"; return null
                }
                return start..end
            }
            val source = values["source"]?.let {
                when (it.lowercase()) {
                    "local" -> SourceType.LOCAL
                    "youtube", "yt" -> SourceType.YOUTUBE
                    else -> { error = "Use source:local or source:youtube"; null }
                }
            }
            val years = range("year", 1000, 3000)?.let { it.first.toInt()..it.last.toInt() }
            val seconds = range("duration", 0, 86400)
            return LibraryQuery(operator.replace(raw, " ").trim(), values["artist"], source, years, seconds, found.isNotEmpty(), error)
        }
    }
}
