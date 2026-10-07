package com.arnav.music.domain

import com.arnav.music.domain.lyrics.BackgroundVocals
import com.arnav.music.domain.lyrics.LrcParser
import com.arnav.music.domain.lyrics.LrcWriter
import com.arnav.music.domain.lyrics.LyricAligner
import com.arnav.music.domain.lyrics.LyricLine
import com.arnav.music.domain.lyrics.LyricRetimer
import com.arnav.music.domain.lyrics.LyricWord
import com.arnav.music.domain.lyrics.LyricWordTiming
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.lyrics.LyricsTiming
import com.arnav.music.domain.lyrics.displayLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Estimated word timing and backing vocals. */
class LyricWordsTest {

    private fun synced(raw: String, durationMs: Long? = null): List<LyricLine> {
        val parsed = LrcParser.parse(raw, durationMs)
        assertTrue("expected synced lyrics but got $parsed", parsed is Lyrics.Synced)
        return (parsed as Lyrics.Synced).lines
    }

    private fun assertWordsInside(line: LyricLine, words: List<LyricWord> = line.words) {
        var prevEnd = line.startMs
        for (w in words) {
            assertTrue("word $w starts before the previous one ended in $line", w.startMs >= prevEnd)
            assertTrue("word $w ends before it starts", w.endMs >= w.startMs)
            assertTrue("word $w runs past the line $line", w.endMs <= line.endMs)
            assertTrue("word '${w.text}' isn't in the text", w.text.isNotEmpty())
            prevEnd = w.endMs
        }
    }

    // region estimated words

    @Test fun `words are spread by syllables inside the line`() {
        val line = LyricWordTiming.estimateLine(LyricLine(10_000, 14_000, "I remember everything"))
        assertTrue(line.estimated)
        assertEquals(listOf("I", "remember", "everything"), line.words.map { it.text })
        assertWordsInside(line)
        assertEquals(10_000L, line.words[0].startMs)
        val dur = line.words.map { it.endMs - it.startMs }
        // 1, 3 and 4 syllables (+ the last word's hold).
        assertTrue("durations $dur", dur[0] < dur[1] && dur[1] < dur[2])
        // Sung within the line, leaving a little air before the next one.
        assertTrue(line.words.last().endMs < 14_000L)
    }

    @Test fun `a comma adds a breath and the last word is held longest`() {
        val line = LyricWordTiming.estimateLine(LyricLine(0, 6_000, "hold on, hold on"))
        val w = line.words
        assertEquals(listOf("hold", "on,", "hold", "on"), w.map { it.text })
        assertTrue("breath after the comma: $w", w[2].startMs > w[1].endMs)
        assertEquals(w[0].endMs, w[1].startMs)
        val len = w.map { it.endMs - it.startMs }
        assertTrue("phrase end held: $len", len[1] > len[0])
        assertTrue("last word held longest: $len", len[3] > len[1])
    }

    @Test fun `short lines are not dragged over a long span and long lines are not cut`() {
        val short = LyricWordTiming.estimateLine(LyricLine(0, 20_000, "Yeah"))
        // Never under 55% of the span…
        assertTrue(short.words.last().endMs >= 11_000L)
        val long = LyricWordTiming.estimateLine(LyricLine(0, 2_000, "everybody everywhere celebrating everything tonight"))
        // …and never past 92% of it.
        assertTrue(long.words.last().endMs <= 1_840L)
        assertWordsInside(long)
    }

    @Test fun `chinese and japanese are timed per character`() {
        val line = LyricWordTiming.estimateLine(LyricLine(0, 4_000, "我爱你，「中国」"))
        assertEquals(listOf("我", "爱", "你，", "「中", "国」"), line.words.map { it.text })
        assertWordsInside(line)
        val mixed = LyricWordTiming.estimateLine(LyricLine(0, 4_000, "君の名はyou"))
        assertEquals(listOf("君", "の", "名", "は", "you"), mixed.words.map { it.text })
    }

    @Test fun `real word timing and instrumental rows are kept`() {
        val real = LyricLine(0, 2_000, "One two", listOf(LyricWord(0, 1_000, "One"), LyricWord(1_000, 2_000, "two")))
        val gap = LyricLine(2_000, 9_000, "")
        val plain = LyricLine(9_000, 12_000, "three four")
        val out = LyricWordTiming.estimate(listOf(real, gap, plain))
        assertEquals(real, out[0])
        assertEquals(gap, out[1])
        assertTrue(out[2].estimated)
        assertEquals(2, out[2].words.size)
        // Nothing to do: the same list.
        val already = listOf(real, gap)
        assertTrue(LyricWordTiming.estimate(already) === already)
    }

    @Test fun `estimates are redone after the line moves`() {
        val est = LyricWordTiming.estimate(listOf(LyricLine(1_000, 5_000, "la la la")))
        val moved = LyricRetimer.shift(est, 2_000)
        assertTrue(moved[0].estimated)
        val again = LyricWordTiming.estimate(moved)
        assertEquals(3_000L, again[0].words[0].startMs)
        assertWordsInside(again[0])
    }

    @Test fun `auto-timed lines carry estimated words`() {
        val lyrics = listOf("I walked along the empty road tonight", "The city lights were fading out", "", "Hold on, hold on", "We're burning like the sun")
        val out = LyricAligner.align(lyrics, 120_000L)
        val sung = out.filter { !it.isInstrumental }
        assertEquals(4, sung.size)
        for (l in sung) {
            assertTrue(l.estimated)
            assertEquals(l.text.split(' '), l.words.map { it.text })
            assertWordsInside(l)
        }
        assertTrue(out.filter { it.isInstrumental }.all { it.words.isEmpty() })
    }

    @Test fun `estimated words are never saved as word timing`() {
        val lines = LyricWordTiming.estimate(listOf(LyricLine(1_000, 4_000, "Hello there")))
        assertEquals("[00:01.00]Hello there", LrcWriter.write(lines))
    }

    // endregion

    // region backing vocals

    @Test fun `trailing parenthetical is a backing vocal`() {
        val s = BackgroundVocals.split("I'm on my way (on my way)")
        assertEquals("I'm on my way", s.text)
        assertEquals("on my way", s.background)
    }

    @Test fun `a line entirely in parentheses is backing vocals only`() {
        val s = BackgroundVocals.split("(Oh, oh, oh)")
        assertEquals("", s.text)
        assertEquals("Oh, oh, oh", s.background)
        val full = BackgroundVocals.split("（ああ）")
        assertEquals("ああ", full.background)
    }

    @Test fun `leading and several groups are combined, middle ones stay`() {
        val s = BackgroundVocals.split("(Yeah) I said it (I said it) (oh)")
        assertEquals("I said it", s.text)
        assertEquals("Yeah I said it oh", s.background)
        val mid = BackgroundVocals.split("Love (is all) you need")
        assertEquals("Love (is all) you need", mid.text)
        assertNull(mid.background)
    }

    @Test fun `labels and odd brackets are not backing vocals`() {
        for (t in listOf("(Chorus)", "Sing it again (x2)", "Hello (", "Broken (one) two)", "Count (1, 2, 3)", "Plain line", "")) {
            val s = BackgroundVocals.split(t)
            assertNull("'$t' -> ${s.background}", s.background)
            assertEquals(t, s.text)
        }
    }

    @Test fun `nested parentheses stay inside the backing vocal`() {
        val s = BackgroundVocals.split("Go (go (go))")
        assertEquals("Go", s.text)
        assertEquals("go (go)", s.background)
    }

    @Test fun `timed words are divided between lead and backing`() {
        val words = listOf(
            LyricWord(0, 500, "On"), LyricWord(500, 1_000, "my"), LyricWord(1_000, 1_500, "way"),
            LyricWord(1_600, 2_000, "(on"), LyricWord(2_000, 2_400, "my"), LyricWord(2_400, 3_000, "way)"),
        )
        val s = BackgroundVocals.split("On my way (on my way)", words)
        assertEquals("On my way", s.text)
        assertEquals(listOf("On", "my", "way"), s.words.map { it.text })
        assertEquals("on my way", s.background)
        assertEquals(listOf(LyricWord(1_600, 2_000, "on"), LyricWord(2_000, 2_400, "my"), LyricWord(2_400, 3_000, "way")), s.backgroundWords)
    }

    @Test fun `lrc lines split their backing vocals`() {
        val lines = synced("[00:01.00]I'm on my way (on my way)\n[00:04.00](Ooh)\n[00:06.00]Next", 9_000)
        assertEquals(LyricLine(1_000, 4_000, "I'm on my way", background = "on my way"), lines[0])
        assertEquals(LyricLine(4_000, 6_000, "", background = "Ooh"), lines[1])
        assertFalse(lines[1].isInstrumental)
        assertEquals("(Ooh)", lines[1].fullText)
        assertEquals(listOf("I'm on my way (on my way)", "(Ooh)", "Next"), Lyrics.Synced(lines).displayLines())
    }

    @Test fun `enhanced lrc bg lines attach to the line before`() {
        val raw = """
            [01:55.000]v1:<01:55.000>Yeah, <01:55.500>you <01:56.000>got <01:56.300>that
            [bg:<01:56.442>You <01:56.823>stay <01:57.096>flexing <01:57.531>on <01:57.739>me <01:57.845>]
            [01:57.845]v2:<01:57.845>Yummy
        """.trimIndent()
        val lines = synced(raw, 200_000).filter { !it.isInstrumental }
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("Yeah, you got that", first.text)
        assertEquals(4, first.words.size)
        assertEquals("You stay flexing on me", first.background)
        assertEquals(listOf("You", "stay", "flexing", "on", "me"), first.backgroundWords.map { it.text })
        assertEquals(116_442L, first.backgroundWords[0].startMs)
        assertEquals(117_845L, first.backgroundWords.last().endMs)
        assertEquals("Yummy", lines[1].text)
        assertFalse(first.estimated)
    }

    @Test fun `bg line with several timestamps and an untimed bg line`() {
        val lines = synced("[00:01.00][00:11.00]Chorus line\n[bg:echo]\n[00:05.00]Verse\n[00:15.00]End", 20_000)
        val chorus = lines.filter { it.text == "Chorus line" }
        assertEquals(2, chorus.size)
        assertTrue(chorus.all { it.background == "echo" && it.backgroundWords.isEmpty() })
    }

    @Test fun `bg lines without any timestamps stay readable as plain lyrics`() {
        val parsed = LrcParser.parse("Lead line\n[bg:backing]")
        assertEquals(Lyrics.Plain(listOf("Lead line", "(backing)")), parsed)
    }

    @Test fun `backing vocals survive saving the timing`() {
        val raw = "[00:01.00]<00:01.00>One <00:01.50>two\n[bg:<00:02.00>three <00:02.50>four]\n[00:04.00]Hey (hey)\n[00:06.00](Ooh)\n[00:08.00]End"
        val lines = synced(raw, 10_000)
        val written = LrcWriter.write(lines)
        assertEquals(
            "[00:01.00]<00:01.00>One <00:01.50>two\n[bg:<00:02.00>three <00:02.50>four]\n[00:04.00]Hey (hey)\n[00:06.00](Ooh)\n[00:08.00]End",
            written,
        )
        assertEquals(lines, synced(written, 10_000))
        val moved = LyricRetimer.shift(lines, 1_000)
        assertEquals(3_000L, moved[0].backgroundWords[0].startMs)
        assertEquals("three four", moved[0].background)
    }

    @Test fun `backing vocals without timing trail the lead`() {
        val line = LyricLine(0, 4_000, "Lead", listOf(LyricWord(0, 2_000, "Lead")), background = "echo")
        val span = LyricsTiming.backgroundSpan(line)
        assertEquals(LyricsTiming.BACKGROUND_DELAY_MS, span.first)
        assertEquals(2_000L + LyricsTiming.BACKGROUND_DELAY_MS, span.last)
        assertEquals(0f, LyricsTiming.backgroundProgress(line, 100), 0.0001f)
        assertEquals(1f, LyricsTiming.backgroundProgress(line, 3_000), 0.0001f)
    }

    @Test fun `estimating leaves backing vocals and their timing alone`() {
        val est = LyricWordTiming.estimateLine(LyricLine(0, 5_000, "I'm on my way", background = "on my way"))
        assertTrue(est.backgroundWords.isEmpty())
        // The untimed backing vocal follows the estimated lead, a little later.
        val span = LyricsTiming.backgroundSpan(est)
        assertEquals(LyricsTiming.BACKGROUND_DELAY_MS, span.first)
        assertEquals(est.words.last().endMs + LyricsTiming.BACKGROUND_DELAY_MS, span.last)
        // A line of backing vocals only is not given lead words, and keeps real backing timing.
        val bgOnly = LyricLine(0, 3_000, "", background = "Ooh", backgroundWords = listOf(LyricWord(500, 2_000, "Ooh")))
        assertEquals(listOf(bgOnly), LyricWordTiming.estimate(listOf(bgOnly)))
        assertEquals(0L, LyricsTiming.backgroundSpan(LyricLine(0, 2_000, "", background = "Ooh")).first)
        // Real backing timing on a line with estimated lead words survives saving.
        val mixed = LyricWordTiming.estimate(listOf(LyricLine(1_000, 4_000, "Lead", background = "echo", backgroundWords = listOf(LyricWord(2_000, 3_000, "echo")))))
        assertEquals("[00:01.00]Lead\n[bg:<00:02.00>echo]", LrcWriter.write(mixed))
        assertEquals(mixed, LyricWordTiming.estimate(mixed))
    }

    @Test fun `auto-timed plain lyrics split backing vocals`() {
        val out = LyricAligner.align(listOf("I'm on my way (on my way)", "(Chorus)", "Still going (going)", "(Ooh)"), 60_000L)
        val sung = out.filter { !it.isInstrumental }
        assertEquals(listOf("I'm on my way", "Still going", ""), sung.map { it.text })
        assertEquals(listOf("on my way", "going", "Ooh"), sung.map { it.background })
    }

    // endregion
}
