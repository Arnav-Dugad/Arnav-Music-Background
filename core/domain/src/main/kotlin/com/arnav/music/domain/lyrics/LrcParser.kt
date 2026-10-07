package com.arnav.music.domain.lyrics

/**
 * Parses LRC (line-synced), enhanced/A2 LRC (word-synced) and plain-text lyrics.
 *
 * Supported: `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]`, several timestamps per line,
 * `[offset:±ms]`, metadata tags (ignored), `<mm:ss.xx>` word tags, BOM and any line ending.
 * Backing vocals: an enhanced-LRC `[bg: <mm:ss.xx>word …]` line belongs to the timed line before it,
 * and parenthetical lead-in/tail groups ("I'm on my way (on my way)") are split off by
 * [BackgroundVocals]. Duet voice prefixes (`v1:`, `v2:`) are dropped.
 * Text without any timestamp becomes [Lyrics.Plain].
 */
object LrcParser {
    /** Gap (between a line's estimated end and the next line) that becomes an instrumental break. */
    const val INSTRUMENTAL_GAP_MS = 6_000L
    private const val LAST_LINE_MS = 5_000L
    private const val MIN_LINE_MS = 2_500L
    private const val MS_PER_WORD = 350L

    private val timeTag = Regex("""^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val metaTag = Regex("""^\s*\[([A-Za-z#][A-Za-z0-9_\- ]{0,15}):(.*)\]\s*$""")
    private val whitespace = Regex("""\s+""")
    private val bgLine = Regex("""^\s*\[bg:(.*)]\s*$""", RegexOption.IGNORE_CASE)
    private val voicePrefix = Regex("""^\s*v\d{1,2}:\s*""", RegexOption.IGNORE_CASE)

    /** Well-known LRC header keys; only these are dropped from plain text (so "[Chorus: x]" survives). */
    private val metaKeys = setOf(
        "ar", "al", "ti", "au", "by", "length", "offset", "re", "ve", "tool", "la", "lang", "id",
        "kana", "sign", "hash", "total", "#", "artist", "album", "title", "author", "version", "encoding",
    )

    private class RawWord(val startMs: Long, var explicitEndMs: Long?, val text: String)
    private class Entry(val timeMs: Long, val text: String, val words: List<RawWord>) {
        /** Backing vocals from `[bg: …]` lines (null when none). */
        var bgText: String? = null
        var bgWords: List<RawWord> = emptyList()

        val hasContent: Boolean get() = text.isNotBlank() || !bgText.isNullOrBlank()

        fun addBackground(text: String, words: List<RawWord>) {
            bgText = bgText?.let { "$it $text" } ?: text
            bgWords = bgWords + words
        }
    }

    /** True when [raw] contains at least one LRC line timestamp. */
    fun looksSynced(raw: String): Boolean = raw.lineSequence().any { timeTag.containsMatchIn(it) }

    fun parse(raw: String, durationMs: Long? = null): Lyrics? {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return null

        var offsetMs = 0L
        val entries = ArrayList<Entry>()
        val plain = ArrayList<String>()
        // Entries made from the latest timed line (several when it has several timestamps).
        var lastEntries: List<Entry> = emptyList()

        for (line in text.split('\n')) {
            val bg = bgLine.find(line)
            if (bg != null) {
                val body = bg.groupValues[1]
                val shown = parseWords(body, 0L).first
                if (shown.isEmpty()) continue
                plain.add("($shown)")
                if (lastEntries.isNotEmpty()) {
                    val base = lastEntries[0].timeMs
                    val words = parseWords(body, base).second
                    for (e in lastEntries) e.addBackground(shown, shifted(words, e.timeMs - base))
                } else {
                    // Backing vocals before any lead line: a line of their own, when they're timed.
                    val first = wordTag.find(body) ?: continue
                    val start = toMs(first.groupValues[1], first.groupValues[2], first.groupValues[3])
                    val entry = Entry(start, "", emptyList())
                    entry.addBackground(shown, parseWords(body, start).second)
                    entries.add(entry)
                    lastEntries = listOf(entry)
                }
                continue
            }
            // Leading timestamps (possibly several).
            val stamps = ArrayList<Long>(1)
            var rest = line
            while (true) {
                val m = timeTag.find(rest) ?: break
                stamps.add(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) {
                val meta = metaTag.find(line)
                if (meta != null) {
                    val key = meta.groupValues[1].trim().lowercase()
                    if (key == "offset") {
                        offsetMs = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: offsetMs
                    }
                    if (key in metaKeys) continue
                }
                plain.add(line.trimEnd())
                continue
            }
            val (lineText, words) = parseWords(rest.replaceFirst(voicePrefix, ""), stamps[0])
            val made = ArrayList<Entry>(stamps.size)
            for (s in stamps) {
                made.add(Entry(s, lineText, shifted(words, s - stamps[0])))
            }
            entries.addAll(made)
            lastEntries = made
        }

        if (entries.isEmpty()) return plainOf(plain)

        // Positive offset shows lyrics earlier.
        val sorted = entries
            .map { e ->
                if (offsetMs == 0L) e else Entry(
                    (e.timeMs - offsetMs).coerceAtLeast(0),
                    e.text,
                    shifted(e.words, -offsetMs),
                ).also { moved -> e.bgText?.let { moved.addBackground(it, shifted(e.bgWords, -offsetMs)) } }
            }
            .sortedBy { it.timeMs }

        return Lyrics.Synced(buildLines(sorted, durationMs))
    }

    private fun buildLines(entries: List<Entry>, durationMs: Long?): List<LyricLine> {
        val out = ArrayList<LyricLine>(entries.size + 4)
        val first = entries[0]
        if (first.hasContent && first.timeMs >= INSTRUMENTAL_GAP_MS) {
            out.add(LyricLine(0L, first.timeMs, ""))
        }
        for (i in entries.indices) {
            val cur = entries[i]
            val next = entries.getOrNull(i + 1)
            var end = when {
                next != null -> next.timeMs
                durationMs != null && durationMs > cur.timeMs -> durationMs
                else -> cur.timeMs + LAST_LINE_MS
            }
            var gap: LyricLine? = null
            if (next != null && cur.hasContent && next.hasContent) {
                val estimate = estimatedEnd(cur).coerceAtMost(next.timeMs)
                if (next.timeMs - estimate >= INSTRUMENTAL_GAP_MS) {
                    end = estimate
                    gap = LyricLine(estimate, next.timeMs, "")
                }
            }
            out.add(lineOf(cur, end))
            if (gap != null) out.add(gap)
        }
        return out
    }

    /** The final line: parenthetical backing vocals split off, `[bg: …]` vocals appended. */
    private fun lineOf(e: Entry, end: Long): LyricLine {
        val split = BackgroundVocals.split(e.text, finishWords(e.words, end))
        val extra = e.bgText?.takeIf { it.isNotBlank() }
        val background = listOfNotNull(split.background, extra).joinToString(" ").ifEmpty { null }
        val bgWords = if (extra == null) split.backgroundWords else split.backgroundWords + finishWords(e.bgWords, end)
        return LyricLine(e.timeMs, end, split.text, split.words, background, bgWords)
    }

    /** [words] moved by [shift] ms (never before 0). */
    private fun shifted(words: List<RawWord>, shift: Long): List<RawWord> =
        if (shift == 0L) words else words.map { w ->
            RawWord((w.startMs + shift).coerceAtLeast(0), w.explicitEndMs?.let { (it + shift).coerceAtLeast(0) }, w.text)
        }

    private fun estimatedEnd(e: Entry): Long {
        val lastWord = e.words.lastOrNull()
        val timed = if (lastWord != null) {
            val wordEnd = lastWord.explicitEndMs ?: (lastWord.startMs + 1_500L)
            maxOf(wordEnd, lastWord.startMs + 500L, e.timeMs + 500L)
        } else {
            val wordCount = e.text.split(whitespace).count { it.isNotEmpty() }
            e.timeMs + maxOf(MIN_LINE_MS, MS_PER_WORD * wordCount)
        }
        // Timed backing vocals can run past the lead.
        val lastBg = e.bgWords.lastOrNull() ?: return timed
        return maxOf(timed, lastBg.explicitEndMs ?: (lastBg.startMs + 1_000L))
    }

    private fun finishWords(words: List<RawWord>, lineEnd: Long): List<LyricWord> {
        if (words.isEmpty()) return emptyList()
        return words.mapIndexed { i, w ->
            val end = (w.explicitEndMs ?: words.getOrNull(i + 1)?.startMs ?: lineEnd)
                .coerceAtMost(lineEnd)
                .coerceAtLeast(w.startMs)
            LyricWord(w.startMs, end, w.text)
        }
    }

    /** Splits `<mm:ss.xx>word <mm:ss.xx>word` into display text and timed words. */
    private fun parseWords(rest: String, lineStart: Long): Pair<String, List<RawWord>> {
        val tags = wordTag.findAll(rest).toList()
        if (tags.isEmpty()) return normalize(rest) to emptyList()

        val words = ArrayList<RawWord>()
        val display = StringBuilder()
        val lead = rest.substring(0, tags[0].range.first)
        display.append(lead)
        if (lead.isNotBlank()) words.add(RawWord(lineStart, null, normalize(lead)))
        for ((i, tag) in tags.withIndex()) {
            val t = toMs(tag.groupValues[1], tag.groupValues[2], tag.groupValues[3])
            // Any tag ends the word before it.
            words.lastOrNull()?.let { if (it.explicitEndMs == null) it.explicitEndMs = t }
            val segEnd = if (i + 1 < tags.size) tags[i + 1].range.first else rest.length
            val seg = rest.substring(tag.range.last + 1, segEnd)
            display.append(seg)
            if (seg.isNotBlank()) words.add(RawWord(t, null, normalize(seg)))
        }
        return normalize(display.toString()) to words
    }

    private fun normalize(s: String): String = s.replace(whitespace, " ").trim()

    private fun toMs(min: String, sec: String, frac: String): Long {
        val m = min.toLong()
        val s = sec.toLong()
        val f = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
        return m * 60_000L + s * 1_000L + f
    }

    private fun plainOf(rawLines: List<String>): Lyrics? {
        val out = ArrayList<String>(rawLines.size)
        for (l in rawLines) {
            val t = l.trim()
            if (t.isEmpty()) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
            } else out.add(t)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return if (out.isEmpty()) null else Lyrics.Plain(out)
    }

    /** Formats milliseconds as an LRC timestamp body, e.g. `01:02.345`. */
    fun formatTimestamp(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val m = v / 60_000
        val s = (v / 1_000) % 60
        val f = v % 1_000
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${f.toString().padStart(3, '0')}"
    }

}
