package com.arnav.music.domain

import com.arnav.music.domain.lyrics.LrcParser
import com.arnav.music.domain.lyrics.LrcWriter
import com.arnav.music.domain.lyrics.LyricAligner
import com.arnav.music.domain.lyrics.LyricLine
import com.arnav.music.domain.lyrics.LyricRetimer
import com.arnav.music.domain.lyrics.LyricWord
import com.arnav.music.domain.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricAlignerTest {

    private val verse1 = listOf("I walked along the empty road tonight", "The city lights were fading out", "Nobody waiting by the door", "Just me and all my doubt")
    private val chorus = listOf("Hold on, hold on", "We're burning like the sun", "Hold on, hold on", "Until the night is done")
    private val verse2 = listOf("The morning came without a sound", "I counted every star", "The radio was playing low", "You never were that far")

    private fun song(vararg stanzas: List<String>): List<String> = stanzas.flatMapIndexed { i, s -> if (i == 0) s else listOf("") + s }

    private fun text(lines: List<LyricLine>) = lines.filter { !it.isInstrumental }

    private fun assertOrdered(lines: List<LyricLine>) {
        for (i in 1 until lines.size) assertTrue("line $i starts before line ${i - 1}: $lines", lines[i].startMs >= lines[i - 1].startMs)
        for (l in lines) assertTrue("negative line $l", l.endMs >= l.startMs)
    }

    /** Activity 0.9 inside [ranges] (seconds), 0.05 elsewhere; one value per 500 ms. */
    private fun curve(durationS: Int, vararg ranges: Pair<Double, Double>): FloatArray =
        FloatArray(durationS * 2) { k ->
            val t = k * 0.5
            if (ranges.any { t >= it.first && t < it.second }) 0.9f else 0.05f
        }

    // region weights

    @Test fun `syllable counts per script`() {
        assertEquals(3, LyricAligner.syllables("beautiful"))
        assertEquals(1, LyricAligner.syllables("time"))
        assertEquals(3, LyricAligner.syllables("hello world"))
        assertEquals(5, LyricAligner.syllables("こんにちは"))
        assertEquals(3, LyricAligner.syllables("我爱你"))
        assertEquals(2, LyricAligner.syllables("사랑"))
        // Devanagari: vowel signs and virama don't count.
        assertTrue(LyricAligner.syllables("नमस्ते") in 3..4)
        assertEquals(2, LyricAligner.syllables("любовь"))
        assertTrue(LyricAligner.lineWeight("Until the night is done and the sun comes up again") > LyricAligner.lineWeight("Hold on"))
    }

    @Test fun `section labels are recognised`() {
        assertTrue(LyricAligner.isSectionLabel("[Chorus]"))
        assertTrue(LyricAligner.isSectionLabel("(Verse 2)"))
        assertTrue(LyricAligner.isSectionLabel("Pre-Chorus:"))
        assertFalse(LyricAligner.isSectionLabel("Hold on, hold on"))
        assertFalse(LyricAligner.isSectionLabel("Bridges falling down tonight"))
    }

    // endregion

    // region without activity

    @Test fun `distributes lines over the vocal span with intro, outro and stanza gaps`() {
        val duration = 200_000L
        val out = LyricAligner.align(song(verse1, chorus, verse2), duration)
        assertOrdered(out)
        val lines = text(out)
        assertEquals(12, lines.size)
        assertEquals(verse1 + chorus + verse2, lines.map { it.text })
        // Intro: about 10% (a bit less for many lines) → first line between 8 and 22 s, preceded by a break row.
        assertTrue("first line at ${lines.first().startMs}", lines.first().startMs in 8_000L..22_000L)
        assertTrue(out.first().isInstrumental && out.first().startMs == 0L)
        // Outro: the last line ends before the song does.
        assertTrue(lines.last().endMs in 170_000L..duration)
        // Two stanza breaks become instrumental rows (≥ 5 s) between the stanzas.
        val breaks = out.drop(1).filter { it.isInstrumental }
        assertEquals(2, breaks.size)
        breaks.forEach { assertTrue(it.endMs - it.startMs >= LyricAligner.INSTRUMENTAL_MIN_MS) }
        assertTrue(breaks[0].startMs > lines[3].startMs && breaks[0].endMs <= lines[4].startMs)
        // Longer lines take longer.
        val dur = { l: LyricLine -> l.endMs - l.startMs }
        assertTrue(dur(lines[0]) > dur(lines[4]))
    }

    @Test fun `music start and fade from analysis bound the estimate`() {
        val out = text(LyricAligner.align(song(verse1, chorus), 180_000L, introMs = 20_000L, outroMs = 150_000L))
        assertTrue(out.first().startMs > 20_000L)
        assertTrue(out.last().endMs <= 150_000L)
    }

    @Test fun `CJK lines are weighted by characters`() {
        val out = text(LyricAligner.align(listOf("我爱你我爱你我爱你我爱你", "你好", "再见再见再见再见"), 60_000L))
        val dur = out.map { it.endMs - it.startMs }
        assertTrue(dur[0] > dur[1] * 2)
        assertTrue(dur[2] > dur[1])
    }

    @Test fun `section labels split stanzas and are not shown`() {
        val out = LyricAligner.align(listOf("[Verse 1]") + verse1 + listOf("[Chorus]") + chorus, 150_000L)
        assertEquals(verse1 + chorus, text(out).map { it.text })
        assertTrue(out.drop(1).any { it.isInstrumental })
    }

    @Test fun `nothing to time`() {
        assertTrue(LyricAligner.align(emptyList(), 200_000L).isEmpty())
        assertTrue(LyricAligner.align(listOf("", "[Chorus]"), 200_000L).isEmpty())
        assertTrue(LyricAligner.align(verse1, 3_000L).isEmpty())
    }

    // endregion

    // region with activity

    @Test fun `instrumental breaks in the activity curve hold the stanza breaks`() {
        val duration = 180_000L
        val act = curve(180, 20.0 to 60.0, 70.0 to 110.0, 125.0 to 165.0)
        val out = LyricAligner.align(song(verse1, chorus, verse2), duration, act)
        assertOrdered(out)
        val lines = text(out)
        assertEquals(12, lines.size)
        assertTrue("first ${lines[0].startMs}", lines[0].startMs in 20_000L..21_500L)
        assertTrue("chorus ${lines[4].startMs}", lines[4].startMs in 70_000L..71_500L)
        assertTrue("verse 2 ${lines[8].startMs}", lines[8].startMs in 125_000L..126_500L)
        // No line starts inside an instrumental stretch.
        for (l in lines) {
            assertFalse("${l.startMs} in a break", l.startMs in 60_500L..69_500L || l.startMs in 110_500L..124_500L)
        }
        // Breaks become instrumental rows that cover the gaps.
        val breaks = out.drop(1).filter { it.isInstrumental }
        assertTrue(breaks.any { it.startMs <= 61_000L && it.endMs >= 69_500L })
        assertTrue(breaks.any { it.startMs <= 111_000L && it.endMs >= 124_500L })
        assertTrue(lines.last().endMs <= 166_000L)
    }

    @Test fun `line starts snap to vocal onsets`() {
        val onsets = listOf(10.0, 16.0, 20.0, 27.0, 31.0, 38.0)
        // Each phrase starts after a one-second dip in vocal activity.
        val act = FloatArray(120) { k ->
            val t = k * 0.5
            when {
                t < 10.0 || t >= 42.0 -> 0.0f
                onsets.drop(1).any { t >= it - 1.0 && t < it } -> 0.1f
                else -> 0.9f
            }
        }
        val lines = List(6) { "La la la la la la" }.mapIndexed { i, s -> "$s $i" }
        val out = text(LyricAligner.align(lines, 60_000L, act))
        assertEquals(6, out.size)
        out.forEachIndexed { i, l ->
            assertTrue("line $i at ${l.startMs}, onset ${onsets[i]}", abs(l.startMs - (onsets[i] * 1000).toLong()) <= 500L)
        }
    }

    @Test fun `repeated choruses keep the same relative timing`() {
        val act = curve(200, 15.0 to 55.0, 62.0 to 80.0, 81.5 to 100.0, 110.0 to 140.0, 150.0 to 175.0, 176.5 to 190.0)
        val out = text(LyricAligner.align(song(verse1, chorus, verse2, chorus), 200_000L, act))
        val c1 = out.subList(4, 8).map { it.startMs }
        val c2 = out.subList(12, 16).map { it.startMs }
        val r1 = c1.map { (it - c1[0]).toDouble() / (c1[3] - c1[0]) }
        val r2 = c2.map { (it - c2[0]).toDouble() / (c2[3] - c2[0]) }
        for (j in 0 until 4) assertEquals("offset $j", r1[j], r2[j], 0.002)
    }

    @Test fun `flat or missing activity falls back to the estimate`() {
        val flat = FloatArray(400) { 0.5f }
        assertEquals(LyricAligner.align(song(verse1, chorus), 200_000L), LyricAligner.align(song(verse1, chorus), 200_000L, flat))
        // A curve covering only half the song isn't trusted either.
        val short = curve(100, 10.0 to 90.0)
        assertEquals(LyricAligner.align(song(verse1, chorus), 200_000L), LyricAligner.align(song(verse1, chorus), 200_000L, short))
    }

    @Test fun `alignment is deterministic`() {
        val act = curve(180, 20.0 to 60.0, 70.0 to 110.0, 125.0 to 165.0)
        val a = LyricAligner.align(song(verse1, chorus, verse2), 180_000L, act)
        val b = LyricAligner.align(song(verse1, chorus, verse2), 180_000L, act.copyOf())
        assertEquals(a, b)
    }

    // endregion

    // region tap to sync

    private val four = listOf(
        LyricLine(10_000, 20_000, "one"),
        LyricLine(20_000, 30_000, "two"),
        LyricLine(30_000, 40_000, "three"),
        LyricLine(40_000, 50_000, "four"),
    )

    @Test fun `re-anchoring moves the line and stretches the following lines to the end`() {
        val out = LyricRetimer.reanchor(four, 1, 22_000, endMs = 50_000)
        assertEquals(listOf(12_000L, 22_000L, 31_333L, 40_667L), out.map { it.startMs })
        assertEquals(50_000L, out.last().endMs)
        assertEquals(22_000L, out[0].endMs)
        assertEquals(out.map { it.text }, four.map { it.text })
    }

    @Test fun `earlier anchors stay put and lines between anchors are stretched`() {
        val first = LyricRetimer.reanchor(four, 1, 22_000, endMs = 50_000)
        val second = LyricRetimer.reanchor(first, 3, 42_000, endMs = 50_000, anchors = setOf(1))
        assertEquals(12_000L, second[0].startMs)
        assertEquals(22_000L, second[1].startMs)
        assertEquals(32_000.0, second[2].startMs.toDouble(), 2.0)
        assertEquals(42_000L, second[3].startMs)
        assertEquals(50_000L, second[3].endMs)
    }

    @Test fun `a tap before the previous anchor is clamped`() {
        val out = LyricRetimer.reanchor(four, 2, 5_000, endMs = 50_000, anchors = setOf(1))
        assertEquals(20_000L + LyricRetimer.MIN_SPACING_MS, out[2].startMs)
        assertEquals(20_000L, out[1].startMs)
        for (i in 1 until out.size) assertTrue(out[i].startMs > out[i - 1].startMs)
    }

    @Test fun `moving the first line earlier than its predecessors can go compresses them toward zero`() {
        val withIntro = listOf(LyricLine(0, 3_000, "")) + four.map { it.copy(startMs = it.startMs - 7_000, endMs = it.endMs - 7_000) }
        // Lines at 3, 13, 23, 33 s; tap line 2 ("two", 13 s) at 1 s.
        val out = LyricRetimer.reanchor(withIntro, 2, 1_000, endMs = 50_000)
        assertEquals(0L, out[0].startMs)
        assertTrue(out[1].startMs in 0L..1_000L)
        assertEquals(1_000L, out[2].startMs)
        for (i in 1 until out.size) assertTrue(out[i].startMs >= out[i - 1].startMs)
    }

    @Test fun `nudges shift everything but keep an intro break at zero`() {
        val withIntro = listOf(LyricLine(0, 10_000, "")) + four
        val later = LyricRetimer.shift(withIntro, 500)
        assertEquals(listOf(0L, 10_500L, 20_500L, 30_500L, 40_500L), later.map { it.startMs })
        assertEquals(10_500L, later[0].endMs)
        val earlier = LyricRetimer.shift(listOf(LyricLine(200, 1_000, "x")), -500)
        assertEquals(0L, earlier[0].startMs)
    }

    @Test fun `word timings move with their line`() {
        val line = LyricLine(20_000, 30_000, "hello world", listOf(LyricWord(20_000, 24_000, "hello"), LyricWord(24_000, 30_000, "world")))
        val out = LyricRetimer.shift(listOf(line), 1_000)
        assertEquals(listOf(21_000L, 25_000L), out[0].words.map { it.startMs })
    }

    @Test fun `timing saves as LRC that parses back to the same lines`() {
        val lines = listOf(LyricLine(0, 12_340, "")) + four.map { it.copy(startMs = it.startMs + 2_340) } +
            LyricLine(61_000, 70_000, "hello world", listOf(LyricWord(61_000, 61_500, "hello"), LyricWord(61_500, 70_000, "world")))
        val lrc = LrcWriter.write(lines)
        assertTrue(lrc.startsWith("[00:00.00]\n[00:12.34]one"))
        assertTrue(lrc.contains("[01:01.00]<01:01.00>hello <01:01.50>world"))
        val parsed = LrcParser.parse(lrc, 80_000) as Lyrics.Synced
        val texts = parsed.lines.filter { !it.isInstrumental }
        assertEquals(listOf("one", "two", "three", "four", "hello world"), texts.map { it.text })
        assertEquals(listOf(12_340L, 22_340L, 32_340L, 42_340L, 61_000L), texts.map { it.startMs })
        assertEquals(listOf(61_000L, 61_500L), texts.last().words.map { it.startMs })
        assertEquals("100:00.00", LrcWriter.stamp(6_000_000))
    }

    // endregion
}
