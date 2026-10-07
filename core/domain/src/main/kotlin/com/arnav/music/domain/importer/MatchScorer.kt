package com.arnav.music.domain.importer

import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import kotlin.math.abs

/**
 * Scores how likely a [Track] is the same recording as an [ImportedSong] from another service.
 * 0..1; [DEFAULT_THRESHOLD] and above is treated as a match. Title carries most weight, then
 * artist, then duration (when the export has one). Official "Topic" art tracks get a small bonus;
 * live, cover, karaoke, remix and similar versions are penalised unless the source asks for them.
 */
object MatchScorer {
    const val DEFAULT_THRESHOLD = 0.68f

    // --------------------------------------------------------------- normalisation

    private val bracketed = Regex("""\s*[(\[{]([^)\]}]*)[)\]}]""")
    private val bracketNoise = Regex(
        """^\s*(feat\.?|ft\.?|featuring|with|prod\.?|from|remaster(ed)?|\d{4}\s+remaster(ed)?|mono|stereo|deluxe|bonus|explicit|clean|single|album|radio|official|lyrics?|lyrical|audio|video|music video|visuali[sz]er|hd|hq|4k|original)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketNoiseAnywhere = Regex(
        """\b(remaster(ed)?|single version|album version|radio edit|radio version|mono version|stereo version|original mix|explicit version|clean version|official (music )?video|official audio|lyric video|visuali[sz]er)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val dashSuffix = Regex(
        """\s+[-–—]\s+((\d{4}\s+)?remaster(ed)?(\s+\d{4})?(\s+version)?|single version|album version|radio edit|radio version|mono|stereo|mono version|stereo version|original mix|explicit|clean|bonus track|from\s.*|feat\.?\s.*|ft\.?\s.*|with\s.*)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingFeat = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /** Removes credits and edition noise: "Song (feat. X) - Remastered 2011" → "Song". Keeps live/remix markers. */
    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        repeat(2) {
            s = s.replace(dashSuffix, "")
            s = bracketed.replace(s) { m ->
                val inner = m.groupValues[1]
                if (bracketNoise.containsMatchIn(inner) || bracketNoiseAnywhere.containsMatchIn(inner)) "" else m.value
            }
        }
        s = s.replace(trailingFeat, "")
        return s.trim().ifEmpty { raw.trim() }
    }

    /** Lowercase words without punctuation; apostrophes join ("Don't" → "dont"), "&" reads as "and". */
    fun tokens(s: String): List<String> =
        s.lowercase().replace("&", " and ").replace("'", "").replace("’", "")
            .split(nonWord).filter { it.isNotEmpty() }

    /** Normalised comparison key for a title: "Don't Stop Me Now - Remastered 2011" → "dont stop me now". */
    fun titleKey(raw: String): String = tokens(cleanTitle(raw)).joinToString(" ")

    /** Individual artists from "A, B & C feat. D" / "A; B" / "A x B". */
    fun artists(raw: String): List<String> =
        raw.split(Regex("""\s*(,|;|&|/|\s+x\s+|\s+and\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+with\s+)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }

    private val artistNoise = Regex("""(\s*-\s*topic$|vevo$|\s+official$|\s+music$|\s+official\s+channel$)""", RegexOption.IGNORE_CASE)

    fun artistKey(raw: String): String = tokens(raw.trim().replace(artistNoise, "")).joinToString("")

    /** Search text for one song: primary artist + cleaned title, at most 100 characters. */
    fun query(song: ImportedSong): String {
        val artist = artists(song.artist).firstOrNull().orEmpty()
        return listOf(artist, cleanTitle(song.title)).filter { it.isNotBlank() }.joinToString(" ").take(100)
    }

    // --------------------------------------------------------------- scoring parts

    private val candidateNoiseTokens = setOf(
        "official", "audio", "video", "lyric", "lyrics", "lyrical", "hd", "hq", "4k", "visualizer", "visualiser", "mv", "music", "full", "song", "topic",
    )

    /** Dice similarity of title words, ignoring the artist's name and upload noise when the source title lacks them. */
    fun titleSimilarity(source: String, candidate: String, artist: String = ""): Float {
        val a = tokens(cleanTitle(source))
        if (a.isEmpty()) return 0f
        val aSet = a.toSet()
        val artistTokens = tokens(artist).toSet() - aSet
        val b = tokens(cleanTitle(candidate)).filter { it in aSet || (it !in artistTokens && it !in candidateNoiseTokens) }
        if (b.isEmpty()) return 0f
        if (a == b) return 1f
        val bSet = b.toSet()
        val common = aSet.intersect(bSet).size
        return (2f * common / (aSet.size + bSet.size)).coerceIn(0f, 1f)
    }

    /** 1 when an artist matches exactly, partial credit for overlapping names, 0.5 when the source has no artist. */
    fun artistSimilarity(sourceArtist: String, candidate: Track): Float {
        val wanted = (listOf(artistKey(sourceArtist)) + artists(sourceArtist).map(::artistKey)).filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return 0.5f
        val haveRaw = listOfNotNull(candidate.artist, candidate.credits)
        val have = haveRaw.flatMap { artists(it) }.map(::artistKey).filter { it.isNotEmpty() } + artistKey(candidate.artist)
        var best = 0f
        for ((i, w) in wanted.withIndex()) {
            // The whole credit and the first-listed artist count fully; featured artists a little less.
            val weight = if (i <= 1) 1f else 0.85f
            for (h in have) {
                val s = when {
                    w == h -> 1f
                    w.length >= 4 && h.length >= 4 && (h.contains(w) || w.contains(h)) -> 0.85f
                    else -> 0f
                }
                if (s * weight > best) best = s * weight
            }
        }
        if (best < 0.8f) {
            // Label uploads put the singer in the title or album rather than the channel name.
            val text = tokens(listOfNotNull(candidate.title, candidate.album, candidate.credits).joinToString(" ")).joinToString("")
            if (wanted.any { it.length >= 4 && text.contains(it) }) best = maxOf(best, 0.75f)
        }
        if (best == 0f) {
            val wt = tokens(sourceArtist).toSet()
            val ht = haveRaw.flatMap { tokens(it) }.toSet()
            if (wt.isNotEmpty() && ht.isNotEmpty()) best = 0.6f * wt.intersect(ht).size / maxOf(wt.size, ht.size)
        }
        return best
    }

    /** 1 within 3 s, sliding to 0 at 20 s apart; null when either duration is unknown. */
    fun durationCloseness(sourceMs: Long?, candidateMs: Long?): Float? {
        if (sourceMs == null || candidateMs == null || sourceMs <= 0 || candidateMs <= 0) return null
        val diff = abs(sourceMs - candidateMs)
        return when {
            diff <= 3_000 -> 1f
            diff >= 20_000 -> 0f
            else -> 1f - (diff - 3_000).toFloat() / 17_000f
        }
    }

    private val versionMarkers = listOf(
        "live" to Regex("""\blive\b|\bunplugged\b|\bconcert\b""", RegexOption.IGNORE_CASE),
        "cover" to Regex("""\bcover(ed)?\b|\btribute\b|\bin the style of\b""", RegexOption.IGNORE_CASE),
        "karaoke" to Regex("""\bkaraoke\b|\binstrumental\b|\bbacking track\b|\bminus one\b""", RegexOption.IGNORE_CASE),
        "remix" to Regex("""\bremix(ed)?\b|\brmx\b|\bbootleg\b|\bmashup\b""", RegexOption.IGNORE_CASE),
        "speed" to Regex("""\bsped\s*up\b|\bslowed\b|\breverb\b|\bnightcore\b|\b8d\b|\bnight\s*core\b|\bbass\s*boosted\b""", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("""\bacoustic\b|\bpiano version\b|\blo-?fi\b""", RegexOption.IGNORE_CASE),
        "practice" to Regex("""\breaction\b|\btutorial\b|\blesson\b|\bdance practice\b|\bfan\s*made\b|\bshorts\b""", RegexOption.IGNORE_CASE),
    )

    /** Version markers in [candidateText] that [sourceText] doesn't ask for. */
    fun unwantedVersions(sourceText: String, candidateText: String): Int =
        versionMarkers.count { (_, r) -> r.containsMatchIn(candidateText) && !r.containsMatchIn(sourceText) }

    // --------------------------------------------------------------- score / pick

    fun score(song: ImportedSong, candidate: Track): Float {
        val title = titleSimilarity(song.title, candidate.title, candidate.artist)
        val artist = artistSimilarity(song.artist, candidate)
        val sourceMs = song.durationMs
        val candidateMs = candidate.durationMs
        val duration = durationCloseness(sourceMs, candidateMs)
        var s = if (duration != null) 0.55f * title + 0.30f * artist + 0.15f * duration
        else 0.62f * title + 0.38f * artist
        // Artist and length alone never make a match.
        if (title < 0.6f) s *= 0.6f
        if (duration != null && sourceMs != null && candidateMs != null && abs(sourceMs - candidateMs) > 60_000) s -= 0.15f
        if (candidate.variant == MediaVariant.SONG) s += 0.06f
        val sourceText = listOfNotNull(song.title, song.album).joinToString(" ")
        val candidateText = listOfNotNull(candidate.title, candidate.album).joinToString(" ")
        s -= 0.25f * unwantedVersions(sourceText, candidateText).coerceAtMost(2)
        if (candidate.compilation) s -= 0.4f
        return s.coerceIn(0f, 1f)
    }

    /** Best candidate scoring at least [threshold]; ties go to official art tracks, then earlier results. */
    fun pick(song: ImportedSong, candidates: List<Track>, threshold: Float = DEFAULT_THRESHOLD): Track? {
        var best: Track? = null
        var bestScore = -1f
        for (c in candidates) {
            val s = score(song, c)
            if (s < threshold) continue
            val better = s > bestScore + 0.0001f ||
                (abs(s - bestScore) <= 0.0001f && c.variant == MediaVariant.SONG && best?.variant != MediaVariant.SONG)
            if (better) { best = c; bestScore = s }
        }
        return best
    }
}

/**
 * Fast exact-ish lookup over a large set of tracks (e.g. every song on the device): candidates
 * share the normalised title, so matching thousands of imported songs stays cheap.
 */
class LocalMatchIndex(tracks: List<Track>) {
    private val byTitle: Map<String, List<Track>> = tracks.groupBy { MatchScorer.titleKey(it.title) }

    fun candidates(song: ImportedSong): List<Track> = byTitle[MatchScorer.titleKey(song.title)].orEmpty()

    fun match(song: ImportedSong, threshold: Float = MatchScorer.DEFAULT_THRESHOLD): Track? =
        MatchScorer.pick(song, candidates(song), threshold)

    val isEmpty: Boolean get() = byTitle.isEmpty()
}
