package com.arnav.music.domain.metadata

import com.arnav.music.domain.importer.MatchScorer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** What we know about an untagged on-device song, cleaned up from its title and file name. */
data class TagHints(
    val title: String,
    val artist: String?,
    val featured: List<String> = emptyList(),
    /** True when [title] was rebuilt from a file-name-like title ("01 - Artist - Song.mp3"). */
    val titleFromFileName: Boolean = false,
)

/** One MusicBrainz release a recording appears on. */
data class MbRelease(
    val id: String,
    val title: String,
    val date: String?,
    val status: String?,
    val primaryType: String?,
    val secondaryTypes: List<String> = emptyList(),
) {
    val year: Int? get() = date?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 }
}

/** One MusicBrainz recording search result. */
data class MbRecording(
    val id: String,
    val title: String,
    val artist: String,
    val lengthMs: Long?,
    val releases: List<MbRelease>,
    /** MusicBrainz's own relevance score, 0..100. */
    val searchScore: Int = 0,
    val disambiguation: String? = null,
    val firstReleaseDate: String? = null,
)

/** An accepted match, ready to be stored as a tag override. */
data class AutoTagMatch(
    val recordingId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val releaseId: String?,
    val year: Int?,
    val confidence: Float,
)

/**
 * Finds songs on the device whose tags are missing and builds MusicBrainz searches for them.
 */
object AutoTagQuery {
    private val placeholderArtists = setOf("", "<unknown>", "unknown", "unknown artist", "artist", "various", "n/a", "none", "null", "-", "track", "untitled")
    private val placeholderAlbums = setOf("", "<unknown>", "unknown", "unknown album", "music", "download", "downloads", "audio", "sounds", "n/a", "none", "null", "-", "whatsapp audio", "bluetooth", "0", "sdcard", "emulated", "storage")
    private val audioExtension = Regex("""\.(mp3|flac|m4a|mp4|aac|ogg|oga|opus|wav|wma|alac|aiff?|ape|mka|3gp|amr)$""", RegexOption.IGNORE_CASE)
    private val leadingNumber = Regex("""^(?:\d{1,2}[-.])?\d{1,3}\s*[-.)_]\s*|^0\d\s+""")
    private val featBracket = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
    private val featTrailing = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val noiseBracket = Regex(
        """\s*[(\[](?:official|lyric|lyrics|lyrical|audio|video|music video|visuali[sz]er|hd|hq|4k|mp3|320\s*kbps|128\s*kbps|kbps|explicit|clean|www\.[^)\]]*|[a-z0-9-]+\.(?:com|net|org|in|me|cc|co))[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val siteSuffix = Regex("""\s*[-–|]?\s*(?:www\.)?[a-z0-9-]+\.(?:com|net|org|in|me|cc|co|info|pk)\s*$""", RegexOption.IGNORE_CASE)
    private val dashSplit = Regex("""\s+[-–—]\s+|\s*_-_\s*""")
    private val ws = Regex("""\s+""")

    fun isPlaceholderArtist(artist: String?): Boolean = artist == null || artist.trim().lowercase() in placeholderArtists

    fun isPlaceholderAlbum(album: String?): Boolean = album == null || album.trim().lowercase() in placeholderAlbums

    /** Songs worth looking up: unknown artist, or no album. */
    fun needsTagging(title: String, artist: String?, album: String?): Boolean =
        title.isNotBlank() && (isPlaceholderArtist(artist) || isPlaceholderAlbum(album))

    /** File name without folders and audio extension, underscores read as spaces. */
    fun stem(fileName: String): String =
        fileName.substringAfterLast('/').replace(audioExtension, "").replace('_', ' ').replace(ws, " ").trim()

    /**
     * Cleans [title] and pulls hints out of file-name patterns: "Artist - Title", "01 - Title",
     * "01. Artist - Title", "Artist - Album - 03 - Title", "Title (feat. X)". [artist] is used as is
     * unless it's a placeholder.
     */
    fun hints(title: String, artist: String?, fileName: String?): TagHints {
        val fileStem = fileName?.let(::stem).orEmpty()
        val rawTitle = title.trim()
        val titleIsFile = rawTitle.isEmpty() || rawTitle.equals("untitled", true) ||
            (fileStem.isNotEmpty() && (rawTitle.equals(fileStem, true) || stem(rawTitle).equals(fileStem, true)))
        var text = if (rawTitle.isEmpty() || rawTitle.equals("untitled", true)) fileStem else stem(rawTitle)
        val knownArtist = artist?.trim()?.takeUnless { isPlaceholderArtist(it) }

        text = text.replace(noiseBracket, "").replace(siteSuffix, "").replace(ws, " ").trim()
        val featured = ArrayList<String>()
        featBracket.find(text)?.let { m -> featured += MatchScorer.artists(m.groupValues[1]); text = text.replace(m.value, "").trim() }
        featTrailing.find(text)?.let { m -> featured += MatchScorer.artists(m.groupValues[1]); text = text.substring(0, m.range.first).trim() }

        val before = text
        text = text.replace(leadingNumber, "").trim().ifEmpty { before }
        var parts = text.split(dashSplit).map { it.trim() }.filter { it.isNotEmpty() }
        // "Artist - Album - 03 - Title": numbers are track numbers, not names.
        if (parts.size > 2) parts = parts.filter { p -> !p.all { it.isDigit() } }
        var hintArtist = knownArtist
        var hintTitle = text
        if (parts.size >= 2) {
            val first = parts.first()
            val last = parts.last()
            if (knownArtist != null) {
                // "Adele - Hello" with artist already Adele: drop the repeated artist.
                if (MatchScorer.artistKey(first) == MatchScorer.artistKey(knownArtist)) hintTitle = parts.drop(1).joinToString(" - ")
                else if (MatchScorer.artistKey(last) == MatchScorer.artistKey(knownArtist)) hintTitle = parts.dropLast(1).joinToString(" - ")
            } else {
                hintArtist = first.replace(leadingNumber, "").trim().ifEmpty { first }
                hintTitle = last
            }
        }
        hintTitle = hintTitle.replace(ws, " ").trim().ifEmpty { rawTitle.ifEmpty { fileStem } }
        return TagHints(
            title = hintTitle,
            artist = hintArtist?.takeIf { it.isNotBlank() },
            featured = featured.filter { it.isNotBlank() }.distinct(),
            titleFromFileName = titleIsFile || hintTitle != rawTitle,
        )
    }

    /**
     * Lucene query for MusicBrainz's recording search. Without an artist the length (±4 s) narrows
     * the results instead.
     */
    fun query(hints: TagHints, durationMs: Long?): String {
        val parts = ArrayList<String>()
        parts += "recording:\"${phrase(MatchScorer.cleanTitle(hints.title))}\""
        val artist = hints.artist
        if (artist != null) parts += "artist:\"${phrase(artist)}\""
        else if (durationMs != null && durationMs > 0) parts += "dur:[${(durationMs - 4_000).coerceAtLeast(0)} TO ${durationMs + 4_000}]"
        return parts.joinToString(" AND ")
    }

    /** Text safe inside a quoted Lucene phrase. */
    fun phrase(s: String): String = s.replace('"', ' ').replace('\\', ' ').replace(ws, " ").trim().take(120)
}

/** Scores MusicBrainz candidates against an untagged song. */
object AutoTagScorer {
    /** Matches below this confidence are never stored. */
    const val ACCEPT = 0.85f
    /** Length difference that still counts as the same recording. */
    const val DURATION_TOLERANCE_MS = 3_000L

    fun artistSimilarity(hint: String, candidate: String): Float {
        val wanted = (listOf(MatchScorer.artistKey(hint)) + MatchScorer.artists(hint).map(MatchScorer::artistKey)).filter { it.isNotEmpty() }.distinct()
        val have = (listOf(MatchScorer.artistKey(candidate)) + MatchScorer.artists(candidate).map(MatchScorer::artistKey)).filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty() || have.isEmpty()) return 0f
        var best = 0f
        for (w in wanted) for (h in have) {
            val s = when {
                w == h -> 1f
                w.length >= 4 && h.length >= 4 && (w.contains(h) || h.contains(w)) -> 0.85f
                else -> 0f
            }
            if (s > best) best = s
        }
        if (best == 0f) {
            val a = MatchScorer.tokens(hint).toSet()
            val b = MatchScorer.tokens(candidate).toSet()
            if (a.isNotEmpty() && b.isNotEmpty()) best = 0.6f * a.intersect(b).size / maxOf(a.size, b.size)
        }
        return best
    }

    /** 0..1 confidence that [c] is the song described by [hints] (length [durationMs]). */
    fun score(hints: TagHints, durationMs: Long?, c: MbRecording): Float {
        val title = MatchScorer.titleSimilarity(hints.title, c.title)
        val known = durationMs != null && durationMs > 0 && c.lengthMs != null && c.lengthMs > 0
        val duration = if (known) (if (abs(durationMs!! - c.lengthMs!!) <= DURATION_TOLERANCE_MS) 1f else 0f) else null
        val artistHint = hints.artist
        var s = when {
            artistHint != null && duration != null -> 0.5f * title + 0.25f * artistSimilarity(artistHint, c.artist) + 0.25f * duration
            artistHint != null -> 0.9f * (0.6f * title + 0.4f * artistSimilarity(artistHint, c.artist))
            duration != null -> {
                // Title and length only: never enough for one-word titles ("Intro", "Hello").
                if (MatchScorer.tokens(MatchScorer.cleanTitle(hints.title)).size < 2) 0.6f * title
                else 0.6f * title + 0.26f * duration
            }
            else -> 0f
        }
        if (title < 0.8f) s *= 0.7f
        if (hints.featured.isNotEmpty() && hints.featured.any { f -> artistSimilarity(f, c.artist) >= 0.85f }) s += 0.03f
        val candidateText = listOfNotNull(c.title, c.disambiguation).joinToString(" ")
        s -= 0.25f * MatchScorer.unwantedVersions(hints.title, candidateText).coerceAtMost(2)
        return s.coerceIn(0f, 1f)
    }

    /**
     * The best candidate when it scores at least [threshold] and isn't ambiguous (another artist
     * scoring just as well means we can't tell who sang it).
     */
    fun best(hints: TagHints, durationMs: Long?, candidates: List<MbRecording>, albumHint: String? = null, threshold: Float = ACCEPT): AutoTagMatch? {
        val scored = candidates.map { it to score(hints, durationMs, it) }.sortedWith(compareByDescending<Pair<MbRecording, Float>> { it.second }.thenByDescending { it.first.searchScore })
        val (top, topScore) = scored.firstOrNull() ?: return null
        if (topScore < threshold) return null
        val topArtist = MatchScorer.artistKey(top.artist)
        val rival = scored.drop(1).firstOrNull { (c, s) -> s >= threshold && s >= topScore - 0.05f && MatchScorer.artistKey(c.artist) != topArtist }
        if (rival != null && hints.artist == null) return null
        val release = bestRelease(top, albumHint)
        return AutoTagMatch(
            recordingId = top.id,
            title = top.title,
            artist = top.artist,
            album = release?.title,
            releaseId = release?.id,
            year = release?.year ?: top.firstReleaseDate?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 },
            confidence = topScore,
        )
    }

    /** Prefers the official studio album with the earliest date; an album named like [albumHint] wins. */
    fun bestRelease(c: MbRecording, albumHint: String? = null): MbRelease? {
        if (c.releases.isEmpty()) return null
        fun points(r: MbRelease): Float {
            var p = 0f
            if (r.status.equals("official", true)) p += 2f
            when (r.primaryType?.lowercase()) {
                "album" -> p += 1.5f
                "ep" -> p += 1f
                "single" -> p += 0.5f
            }
            if (r.secondaryTypes.isEmpty()) p += 1f
            else if (r.secondaryTypes.any { it.equals("compilation", true) || it.equals("live", true) || it.equals("remix", true) || it.equals("dj-mix", true) }) p -= 1f
            if (r.date != null) p += 0.25f
            if (albumHint != null && !AutoTagQuery.isPlaceholderAlbum(albumHint) && MatchScorer.titleKey(albumHint) == MatchScorer.titleKey(r.title)) p += 3f
            return p
        }
        return c.releases.sortedWith(compareByDescending<MbRelease> { points(it) }.thenBy { it.date ?: "9999" }).first()
    }
}

/** Parses MusicBrainz `/ws/2/recording?fmt=json` search responses. Never throws. */
object MusicBrainzJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun recordings(body: String): List<MbRecording> = try {
        val root = json.parseToJsonElement(body) as? JsonObject
        val list = root?.get("recordings") as? JsonArray
        list.orEmpty().mapNotNull { (it as? JsonObject)?.let(::recording) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun recording(o: JsonObject): MbRecording? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val releases = (o["releases"] as? JsonArray).orEmpty().mapNotNull { r ->
            val ro = r as? JsonObject ?: return@mapNotNull null
            val rid = ro.str("id") ?: return@mapNotNull null
            val group = ro["release-group"] as? JsonObject
            MbRelease(
                id = rid,
                title = ro.str("title").orEmpty(),
                date = ro.str("date")?.takeIf { it.isNotBlank() },
                status = ro.str("status"),
                primaryType = group?.str("primary-type"),
                secondaryTypes = (group?.get("secondary-types") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() },
            )
        }
        return MbRecording(
            id = id,
            title = title,
            artist = artistCredit(o["artist-credit"]),
            lengthMs = (o["length"] as? JsonPrimitive)?.contentOrNullSafe()?.toLongOrNull(),
            releases = releases,
            searchScore = (o["score"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull() ?: 0,
            disambiguation = o.str("disambiguation")?.takeIf { it.isNotBlank() },
            firstReleaseDate = o.str("first-release-date")?.takeIf { it.isNotBlank() },
        )
    }

    /** "Daft Punk feat. Pharrell Williams" from name + joinphrase pairs. */
    private fun artistCredit(e: JsonElement?): String {
        val arr = e as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            val name = o.str("name") ?: (o["artist"] as? JsonObject)?.str("name") ?: continue
            sb.append(name).append(o.str("joinphrase").orEmpty())
        }
        return sb.toString().trim()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this.toString() == "null") null else content
}
