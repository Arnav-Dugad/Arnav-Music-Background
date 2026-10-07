package com.arnav.music.domain.lyrics

import kotlin.math.roundToLong

/**
 * "Adjust timing" (tap to sync): re-anchors one line to the moment the listener tapped it and warps
 * the neighbouring lines so the timeline stays in order.
 *
 * Lines already tapped are [anchors] and never move again. For a tap on line `i` at `t`:
 * - lines after `i` up to the next anchor (or the end of the lyrics) are stretched linearly from
 *   `[old start of i, next anchor]` to `[t, next anchor]`;
 * - lines before `i` back to the previous anchor are stretched from `[previous anchor, old start]`
 *   to `[previous anchor, t]`; with no anchor before, they simply move with line `i` (compressed
 *   towards 0 if they would start before the song).
 * Word timings move with their line. Instrumental rows are moved like any line.
 */
object LyricRetimer {
    /** Neighbouring anchors are kept at least this far apart. */
    const val MIN_SPACING_MS = 100L

    fun reanchor(
        lines: List<LyricLine>,
        index: Int,
        newStartMs: Long,
        endMs: Long,
        anchors: Set<Int> = emptySet(),
    ): List<LyricLine> {
        if (index !in lines.indices) return lines
        val old = lines[index].startMs
        val end = maxOf(endMs, lines.last().endMs, lines.last().startMs + MIN_SPACING_MS)
        val prev = anchors.filter { it < index }.maxOrNull()
        val next = anchors.filter { it > index && it in lines.indices }.minOrNull()
        val prevStart = prev?.let { lines[it].startMs }
        val nextStart = next?.let { lines[it].startMs } ?: end
        val lo = (prevStart ?: 0L) + (if (prev != null) MIN_SPACING_MS else 0L)
        val hi = nextStart - MIN_SPACING_MS
        if (lo > hi) return lines
        val t = newStartMs.coerceIn(lo, hi)
        if (t == old) return lines

        val firstStart = lines[0].startMs
        val translateBefore = prev == null && firstStart + (t - old) >= 0
        fun map(x: Long): Long = when {
            x >= old -> {
                if (x >= nextStart) x
                else if (nextStart == old) t
                else t + ((x - old).toDouble() * (nextStart - t) / (nextStart - old)).roundToLong()
            }
            prevStart != null -> {
                if (x <= prevStart) x
                else prevStart + ((x - prevStart).toDouble() * (t - prevStart) / (old - prevStart)).roundToLong()
            }
            translateBefore -> x + (t - old)
            old <= 0L -> x
            else -> (x.toDouble() * t / old).roundToLong().coerceAtLeast(0L)
        }
        return lines.mapIndexed { i, l -> remap(l, if (i == 0 && keepsZero(l)) 0L else map(l.startMs), ::map) }
    }

    /** Moves every line by [deltaMs] (the −0.5 s / +0.5 s nudges). An intro break keeps starting at 0. */
    fun shift(lines: List<LyricLine>, deltaMs: Long): List<LyricLine> {
        if (deltaMs == 0L || lines.isEmpty()) return lines
        val map = { x: Long -> (x + deltaMs).coerceAtLeast(0L) }
        return lines.mapIndexed { i, l -> remap(l, if (i == 0 && keepsZero(l)) 0L else map(l.startMs), map) }
    }

    private fun keepsZero(l: LyricLine) = l.isInstrumental && l.startMs == 0L

    private fun remap(l: LyricLine, start: Long, map: (Long) -> Long): LyricLine {
        val endMapped = map(l.endMs).coerceAtLeast(start)
        fun moved(words: List<LyricWord>) = if (words.isEmpty()) words else words.map { w ->
            val ws = map(w.startMs).coerceAtLeast(start)
            LyricWord(ws, map(w.endMs).coerceAtLeast(ws), w.text)
        }
        return l.copy(startMs = start, endMs = endMapped, words = moved(l.words), backgroundWords = moved(l.backgroundWords))
    }
}

/**
 * Writes timed lines back as LRC, `[mm:ss.xx]` per line (`<mm:ss.xx>` per word when word-synced).
 * Estimated word timing ([LyricLine.estimated]) is not written. Backing vocals with their own word
 * timing follow their line as a `[bg: …]` line; untimed ones go back in parentheses.
 */
object LrcWriter {
    fun write(lines: List<LyricLine>): String = buildString {
        for (l in lines) {
            append('[').append(stamp(l.startMs)).append(']')
            if (!l.isInstrumental) {
                val real = !l.estimated
                append((if (real) wordTagged(l.text, l.words) else null) ?: l.text)
                val bg = l.background?.takeIf { it.isNotBlank() }
                if (bg != null) {
                    val tagged = wordTagged(bg, l.backgroundWords)
                    if (tagged != null) append("\n[bg:").append(tagged).append(']')
                    else append(if (l.text.isBlank()) "(" else " (").append(bg).append(')')
                }
            }
            append('\n')
        }
    }.trimEnd()

    /** [text] with a `<mm:ss.xx>` tag before each timed word (spacing kept); null when a word isn't found. */
    private fun wordTagged(text: String, words: List<LyricWord>): String? {
        if (words.isEmpty()) return null
        val sb = StringBuilder()
        var cursor = 0
        for (w in words) {
            val at = text.indexOf(w.text, cursor)
            if (w.text.isEmpty() || at < 0) return null
            sb.append(text, cursor, at)
            sb.append('<').append(stamp(w.startMs)).append('>').append(w.text)
            cursor = at + w.text.length
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /** `mm:ss.xx` (centiseconds), minutes not capped at 99. */
    fun stamp(ms: Long): String {
        val v = ms.coerceAtLeast(0L)
        val cs = (v + 5) / 10
        val m = cs / 6_000
        val s = (cs / 100) % 60
        val c = cs % 100
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${c.toString().padStart(2, '0')}"
    }
}
