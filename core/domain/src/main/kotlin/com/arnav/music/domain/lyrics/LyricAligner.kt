package com.arnav.music.domain.lyrics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * "Auto-timed" lyrics: estimates when each line of unsynced (plain) lyrics is sung.
 *
 * Lines are spread over the part of the song that has vocals, each taking time in proportion to how
 * much there is to sing (syllables for Latin/Cyrillic/Greek text, characters for CJK, Indic and
 * other syllabic scripts, plus a short breath per line). Blank lines and section labels
 * ("[Chorus]") separate stanzas; stanza breaks become instrumental gaps.
 *
 * With a vocal-activity curve (on-device songs, see [VocalActivityMeter]) the vocal timeline is the
 * voiced stretches only: stanza breaks are matched to the long quiet-voice gaps (instrumental
 * breaks), lines never start inside them, and each line start snaps to a nearby vocal onset.
 * Without one (YouTube), the vocals are assumed to begin after a short intro and end before a short
 * outro, both a fixed share of the song that shrinks a little for songs with many lines.
 *
 * Repeated stanzas (a chorus sung twice) get the same relative line timing. Every line also gets
 * estimated word timing ([LyricWordTiming], by syllables) so it fills word by word, and
 * parenthetical backing vocals are split off ([BackgroundVocals]). Pure and deterministic.
 */
object LyricAligner {
    const val DEFAULT_STEP_MS = 500L

    /** Gaps at least this long become an instrumental row ("•••") in the synced view. */
    const val INSTRUMENTAL_MIN_MS = 5_000L

    /** Lines never start closer together than this after snapping. */
    const val MIN_LINE_MS = 700L

    /** A line start moves to a vocal onset at most this far away. */
    const val SNAP_WINDOW_MS = 1_500L

    /** Quiet-voice stretches at least this long can hold a stanza break. */
    const val LONG_GAP_MS = 3_000L

    private const val MIN_DURATION_MS = 10_000L
    private const val BREATH_SYLLABLES = 1.2f
    private const val UNMATCHED_BREAK_COST = 0.15
    private const val MAX_MATCH_DISTANCE = 0.25

    private val sectionLabel = Regex(
        """^[\[(]?\s*(verse|chorus|pre[- ]?chorus|post[- ]?chorus|bridge|intro|outro|hook|refrain|interlude|instrumental|break|solo|coda|repeat|x\d)\b[^\])]{0,24}[\])]?\s*:?$""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketOnly = Regex("""^\[[^\]]{1,30}]$""")

    /** True for stanza headings such as "[Chorus]", "(Verse 2)", "Bridge:". They are not sung. */
    fun isSectionLabel(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 32) return false
        return sectionLabel.matches(t) || bracketOnly.matches(t)
    }

    /**
     * Timed lines for [lines] (plain lyrics; blank entries separate stanzas) in a song of
     * [durationMs]. [activity] is a 0..1 vocal-activity value per [stepMs] (null when unknown).
     * [introMs] is where the music starts and [outroMs] where the closing fade begins (0 = unknown),
     * as measured by on-device analysis. Instrumental gaps are returned as lines with empty text.
     * Sung lines carry estimated words ([LyricLine.estimated]). Returns an empty list when there's
     * nothing to time.
     */
    fun align(
        lines: List<String>,
        durationMs: Long,
        activity: FloatArray? = null,
        stepMs: Long = DEFAULT_STEP_MS,
        introMs: Long = 0L,
        outroMs: Long = 0L,
    ): List<LyricLine> {
        val stanzas = stanzasOf(lines)
        if (stanzas.isEmpty() || durationMs < MIN_DURATION_MS) return emptyList()
        val items = ArrayList<Item>()
        stanzas.forEachIndexed { s, st -> st.forEachIndexed { i, text -> items += Item(text, lineWeight(text), s, i) } }

        val musicStart = introMs.coerceIn(0L, durationMs / 3)
        val musicEnd = if (outroMs > musicStart + MIN_DURATION_MS && outroMs < durationMs) outroMs else durationMs
        val segments = if (activity != null && stepMs > 0) voicedSegments(activity, stepMs, durationMs) else null

        val layout = if (segments != null) layoutVoiced(items, stanzas.size, segments) else layoutEstimated(items, stanzas.size, musicStart, musicEnd)
        val starts = layout.starts
        if (segments != null) snap(starts, onsets(activity!!, stepMs, segments, durationMs), layout.vocalEndMs)
        keepRepeatsConsistent(stanzas, starts)
        return LyricWordTiming.estimate(build(items, starts, layout.silences, layout.vocalEndMs, durationMs))
    }

    // region weights

    /** How long a line takes to sing, in syllable units (plus a breath). */
    fun lineWeight(text: String): Float = syllables(text) + BREATH_SYLLABLES

    private val vowels: Set<Char> = (
        "aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüýÿœāăąēĕėęěīĭįıōŏőūŭůűųŷ" +
            "аеёиоуыэюяіїєў" +
            "αεηιουωάέήίόύώϊϋΐΰ"
        ).toSet()

    /**
     * Approximate sung syllables: vowel groups per word for alphabetic scripts (with a silent final
     * "e"), one per character for CJK, kana, Hangul, Indic and South-East Asian scripts (combining
     * vowel signs don't count), two per number. Every word counts at least one.
     */
    fun syllables(text: String): Int {
        var total = 0
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            total += wordSyllables(word.toString())
            word.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) {
                if (word.isNotEmpty()) word.appendCodePoint(cp)
                continue
            }
            when {
                isSyllabic(cp) -> { flush(); if (Character.isLetter(cp)) total++ }
                Character.isLetter(cp) -> word.appendCodePoint(cp)
                Character.isDigit(cp) -> word.appendCodePoint(cp)
                cp == '\''.code || cp == '’'.code -> if (word.isNotEmpty()) word.appendCodePoint(cp)
                else -> flush()
            }
        }
        flush()
        return total
    }

    private fun wordSyllables(w: String): Int {
        if (w.all { it.isDigit() }) return 2 * min(w.length, 4)
        val lower = w.lowercase()
        var groups = 0
        var inVowel = false
        var letters = 0
        for (c in lower) {
            if (!c.isLetter()) continue
            letters++
            val v = c in vowels
            if (v && !inVowel) groups++
            inVowel = v
        }
        if (groups == 0) {
            // Abjads (Arabic, Hebrew) rarely write vowels: about one syllable per two or three letters.
            return max(1, ceil(letters / 2.5).toInt())
        }
        if (groups > 1 && lower.length > 3 && lower.endsWith("e") && !lower.endsWith("le") && !lower.endsWith("ee") &&
            lower[lower.length - 2] !in vowels
        ) groups--
        return max(1, groups)
    }

    private fun isSyllabic(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO, Character.UnicodeScript.YI,
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
        Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL,
        Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM,
        Character.UnicodeScript.SINHALA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
        Character.UnicodeScript.ETHIOPIC -> true
        else -> false
    }

    // endregion

    // region layout

    private class Item(val text: String, val weight: Float, val stanza: Int, val indexInStanza: Int)

    /** Line starts (ms, one per item), quiet stretches that may become instrumental rows, end of the vocals. */
    private class Layout(val starts: LongArray, val silences: List<LongRange>, val vocalEndMs: Long)

    private fun stanzasOf(lines: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || isSectionLabel(t)) {
                if (cur.isNotEmpty()) { out += cur; cur = ArrayList() }
            } else cur += t
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** No activity: vocals after a short intro, before a short outro; fixed-length stanza gaps. */
    private fun layoutEstimated(items: List<Item>, stanzaCount: Int, musicStart: Long, musicEnd: Long): Layout {
        val span = (musicEnd - musicStart).toDouble()
        val density = min(1.0, items.size / 60.0)
        val introFrac = 0.10 - 0.04 * density
        val outroFrac = 0.08 - 0.03 * density
        val vStart = musicStart + (span * introFrac).roundToLong().coerceIn(1_500L, 30_000L)
        val vEnd = musicEnd - (span * outroFrac).roundToLong().coerceIn(1_000L, 25_000L)
        val singable = max(1L, vEnd - vStart).toDouble()
        val breaks = stanzaCount - 1
        var gap = (span * 0.035).coerceIn(2_500.0, 9_000.0)
        if (breaks > 0) gap = min(gap, singable * 0.3 / breaks)
        val totalWeight = items.sumOf { it.weight.toDouble() }
        val perUnit = (singable - gap * breaks) / totalWeight

        val starts = LongArray(items.size)
        val silences = ArrayList<LongRange>()
        var t = vStart.toDouble()
        for (i in items.indices) {
            val item = items[i]
            if (i > 0 && item.stanza != items[i - 1].stanza) {
                silences += t.roundToLong() until (t + gap).roundToLong()
                t += gap
            }
            starts[i] = t.roundToLong()
            t += item.weight * perUnit
        }
        return Layout(starts, silences, vEnd)
    }

    /**
     * Activity known: lines live on the voiced timeline (quiet-voice stretches take no lyric time);
     * stanza breaks are matched to long gaps where that fits their expected position.
     */
    private fun layoutVoiced(items: List<Item>, stanzaCount: Int, segments: List<LongRange>): Layout {
        val voicedTotal = segments.sumOf { it.last - it.first }.toDouble()
        // Gaps between voiced segments, with the voiced time before each.
        val gapPos = ArrayList<Double>()
        val gapIndex = ArrayList<Int>()
        var acc = 0.0
        for (s in 0 until segments.size - 1) {
            acc += (segments[s].last - segments[s].first)
            if (segments[s + 1].first - segments[s].last >= LONG_GAP_MS) {
                gapPos += acc
                gapIndex += s
            }
        }

        // Expected voiced position of each stanza break.
        val totalWeight = items.sumOf { it.weight.toDouble() }
        val stanzaWeight = DoubleArray(stanzaCount)
        for (it in items) stanzaWeight[it.stanza] += it.weight.toDouble()
        val expected = DoubleArray(max(0, stanzaCount - 1))
        var w = 0.0
        for (b in 0 until stanzaCount - 1) {
            w += stanzaWeight[b]
            expected[b] = w / totalWeight * voicedTotal
        }
        val matched = matchBreaks(expected, gapPos, voicedTotal) // break -> gap list index or -1

        // Anchors on the voiced timeline: stanza index -> voiced start position.
        val stanzaStart = DoubleArray(stanzaCount + 1) { Double.NaN }
        stanzaStart[0] = 0.0
        stanzaStart[stanzaCount] = voicedTotal
        for (b in matched.indices) if (matched[b] >= 0) stanzaStart[b + 1] = gapPos[matched[b]]
        // Fill unanchored stanza starts proportionally between anchors.
        var a = 0
        while (a < stanzaCount) {
            var bIdx = a + 1
            while (stanzaStart[bIdx].isNaN()) bIdx++
            val from = stanzaStart[a]
            val to = stanzaStart[bIdx]
            var sumW = 0.0
            for (s in a until bIdx) sumW += stanzaWeight[s]
            var run = 0.0
            for (s in a + 1 until bIdx) {
                run += stanzaWeight[s - 1]
                stanzaStart[s] = from + (to - from) * run / sumW
            }
            a = bIdx
        }

        val starts = LongArray(items.size)
        var i = 0
        while (i < items.size) {
            val s = items[i].stanza
            var j = i
            var sumW = 0.0
            while (j < items.size && items[j].stanza == s) { sumW += items[j].weight; j++ }
            val from = stanzaStart[s]
            val to = stanzaStart[s + 1]
            var run = 0.0
            for (k in i until j) {
                starts[k] = voicedToReal(from + (to - from) * run / sumW, segments)
                run += items[k].weight
            }
            i = j
        }

        val silences = ArrayList<LongRange>()
        for (s in 0 until segments.size - 1) {
            val gStart = segments[s].last
            val gEnd = segments[s + 1].first
            if (gEnd - gStart >= LONG_GAP_MS) silences += gStart until gEnd
        }
        return Layout(starts, silences, segments.last().last)
    }

    /** Monotone matching of stanza breaks to gaps minimising the distance from where they're expected. */
    private fun matchBreaks(expected: DoubleArray, gapPos: List<Double>, total: Double): IntArray {
        val nb = expected.size
        val ng = gapPos.size
        val result = IntArray(nb) { -1 }
        if (nb == 0 || ng == 0 || total <= 0.0) return result
        // dp[b][g]: best cost for the first b breaks using the first g gaps.
        val dp = Array(nb + 1) { DoubleArray(ng + 1) }
        val choice = Array(nb + 1) { IntArray(ng + 1) } // 0 = skip break, 1 = skip gap, 2 = match
        for (b in 1..nb) { dp[b][0] = dp[b - 1][0] + UNMATCHED_BREAK_COST; choice[b][0] = 0 }
        for (g in 1..ng) { dp[0][g] = 0.0; choice[0][g] = 1 }
        for (b in 1..nb) for (g in 1..ng) {
            var best = dp[b - 1][g] + UNMATCHED_BREAK_COST
            var c = 0
            if (dp[b][g - 1] < best) { best = dp[b][g - 1]; c = 1 }
            val d = abs(expected[b - 1] - gapPos[g - 1]) / total
            if (d <= MAX_MATCH_DISTANCE && dp[b - 1][g - 1] + d < best) { best = dp[b - 1][g - 1] + d; c = 2 }
            dp[b][g] = best
            choice[b][g] = c
        }
        var b = nb
        var g = ng
        while (b > 0) {
            when (if (g == 0) 0 else choice[b][g]) {
                0 -> b--
                1 -> g--
                else -> { result[b - 1] = g - 1; b--; g-- }
            }
        }
        return result
    }

    /** Real time of a position on the voiced timeline; a position on a segment boundary maps to the later segment. */
    private fun voicedToReal(pos: Double, segments: List<LongRange>): Long {
        var acc = 0.0
        for ((k, s) in segments.withIndex()) {
            val len = (s.last - s.first).toDouble()
            if (pos < acc + len - 0.5 || k == segments.lastIndex) {
                return (s.first + (pos - acc).coerceIn(0.0, len)).roundToLong()
            }
            acc += len
        }
        return segments.last().last
    }

    // endregion

    // region activity

    /**
     * Voiced stretches (ms) from the activity curve: adaptive threshold between the quiet and the
     * busy parts, short holes filled and short blips dropped. Null when the curve has no usable
     * contrast (then the estimate is used instead).
     */
    internal fun voicedSegments(activity: FloatArray, stepMs: Long, durationMs: Long): List<LongRange>? {
        val n = activity.size
        if (n < 10) return null
        // The curve must cover most of the song; otherwise lines would be crammed into the analysed part.
        if (n * stepMs < durationMs - 15_000L) return null
        val smooth = smoothed(activity)
        val sorted = smooth.copyOf().also { it.sort() }
        val p20 = sorted[(n * 0.2).toInt().coerceIn(0, n - 1)]
        val p90 = sorted[(n * 0.9).toInt().coerceIn(0, n - 1)]
        if (p90 - p20 < 0.12f) return null
        val thr = p20 + 0.4f * (p90 - p20)
        val mask = BooleanArray(n) { smooth[it] >= thr }
        val hole = ceil(1_200.0 / stepMs).toInt()
        val minRun = ceil(1_000.0 / stepMs).toInt()
        fillHoles(mask, hole)
        dropShortRuns(mask, minRun)
        val out = ArrayList<LongRange>()
        var k = 0
        while (k < n) {
            if (!mask[k]) { k++; continue }
            var e = k
            while (e + 1 < n && mask[e + 1]) e++
            val start = (k * stepMs).coerceAtMost(durationMs)
            val end = ((e + 1) * stepMs).coerceAtMost(durationMs)
            if (end > start) out += start..end
            k = e + 1
        }
        if (out.isEmpty()) return null
        val voiced = out.sumOf { it.last - it.first }
        if (voiced < durationMs / 10) return null
        return out
    }

    private fun smoothed(a: FloatArray): FloatArray = FloatArray(a.size) { i ->
        val l = a[max(0, i - 1)]
        val r = a[min(a.size - 1, i + 1)]
        (l + 2f * a[i] + r) / 4f
    }

    private fun fillHoles(mask: BooleanArray, maxLen: Int) {
        var k = 0
        while (k < mask.size) {
            if (mask[k]) { k++; continue }
            var e = k
            while (e + 1 < mask.size && !mask[e + 1]) e++
            val inside = k > 0 && e < mask.size - 1
            if (inside && e - k + 1 <= maxLen) for (x in k..e) mask[x] = true
            k = e + 1
        }
    }

    private fun dropShortRuns(mask: BooleanArray, minLen: Int) {
        var k = 0
        while (k < mask.size) {
            if (!mask[k]) { k++; continue }
            var e = k
            while (e + 1 < mask.size && mask[e + 1]) e++
            if (e - k + 1 < minLen) for (x in k..e) mask[x] = false
            k = e + 1
        }
    }

    /** Vocal onsets: starts of voiced stretches, plus sharp rises of activity inside them. */
    private fun onsets(activity: FloatArray, stepMs: Long, segments: List<LongRange>, durationMs: Long): List<Onset> {
        val out = ArrayList<Onset>()
        for (s in segments) out += Onset(s.first, strong = true)
        val smooth = smoothed(activity)
        for (k in 2 until smooth.size) {
            val t = k * stepMs
            if (t >= durationMs) break
            if (smooth[k] - smooth[k - 2] > 0.25f && smooth[k] >= smooth[k - 1] && segments.any { t in it }) {
                if (out.none { abs(it.ms - t) < stepMs * 2 }) out += Onset(t, strong = false)
            }
        }
        out.sortBy { it.ms }
        return out
    }

    private class Onset(val ms: Long, val strong: Boolean)

    /** Moves each line start to a nearby onset, keeping the order and [MIN_LINE_MS] spacing. */
    private fun snap(starts: LongArray, onsets: List<Onset>, vocalEnd: Long) {
        if (onsets.isEmpty()) return
        val original = starts.copyOf()
        for (i in starts.indices) {
            val lo = if (i == 0) 0L else starts[i - 1] + MIN_LINE_MS
            val hi = if (i == starts.lastIndex) vocalEnd - MIN_LINE_MS else original[i + 1] - MIN_LINE_MS
            var best: Onset? = null
            var bestScore = Double.MAX_VALUE
            for (o in onsets) {
                val d = abs(o.ms - original[i])
                if (d > SNAP_WINDOW_MS || o.ms < lo || o.ms > hi) continue
                val score = d * (if (o.strong) 0.6 else 1.0)
                if (score < bestScore) { bestScore = score; best = o }
            }
            // Without a fitting onset the line keeps its proportional start (still after the previous one:
            // a snapped line never moves past original[i + 1] - MIN_LINE_MS).
            if (best != null) starts[i] = best.ms
        }
    }

    // endregion

    // region repeats and output

    private fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Repeated stanzas share the same relative line offsets (averaged over the repeats). */
    private fun keepRepeatsConsistent(stanzas: List<List<String>>, starts: LongArray) {
        val firstItem = IntArray(stanzas.size)
        var idx = 0
        for (s in stanzas.indices) { firstItem[s] = idx; idx += stanzas[s].size }
        val groups = stanzas.indices.groupBy { s -> stanzas[s].joinToString("\n") { key(it) } }
        for ((_, members) in groups) {
            val size = stanzas[members[0]].size
            if (members.size < 2 || size < 3) continue
            val avg = DoubleArray(size)
            var used = 0
            for (s in members) {
                val f = firstItem[s]
                val span = (starts[f + size - 1] - starts[f]).toDouble()
                if (span <= 0) continue
                for (j in 0 until size) avg[j] += (starts[f + j] - starts[f]) / span
                used++
            }
            if (used < 2) continue
            for (j in 0 until size) avg[j] /= used
            for (s in members) {
                val f = firstItem[s]
                val span = (starts[f + size - 1] - starts[f]).toDouble()
                if (span <= 0) continue
                for (j in 1 until size - 1) starts[f + j] = starts[f] + (avg[j] * span).roundToLong()
            }
        }
        // Items are already in order; guard against equal starts after rounding.
        for (i in 1 until starts.size) if (starts[i] <= starts[i - 1]) starts[i] = starts[i - 1] + 1
    }

    private fun build(items: List<Item>, starts: LongArray, silences: List<LongRange>, vocalEnd: Long, durationMs: Long): List<LyricLine> {
        val out = ArrayList<LyricLine>(items.size + 8)
        if (starts.isNotEmpty() && starts[0] >= LrcParser.INSTRUMENTAL_GAP_MS) out += LyricLine(0L, starts[0], "")
        for (i in items.indices) {
            val start = starts[i]
            val next = if (i + 1 < starts.size) starts[i + 1] else max(vocalEnd, start + MIN_LINE_MS).coerceAtMost(max(durationMs, start + 1))
            // A long quiet stretch inside this line's span: the line ends there and a break follows.
            val gap = silences.firstOrNull { g ->
                g.first > start + MIN_LINE_MS && g.first < next && min(g.last + 1, next) - g.first >= INSTRUMENTAL_MIN_MS
            }
            val split = BackgroundVocals.split(items[i].text)
            val breakAt = gap?.first?.takeIf { i + 1 < starts.size }
            out += LyricLine(start, breakAt ?: next, split.text, background = split.background)
            if (breakAt != null) out += LyricLine(breakAt, next, "")
        }
        return out
    }

    // endregion
}
