package com.arnav.music.domain

import com.arnav.music.domain.lyrics.EmbeddedLyrics
import com.arnav.music.domain.lyrics.LrcParser
import com.arnav.music.domain.lyrics.LyricLine
import com.arnav.music.domain.lyrics.LyricWord
import com.arnav.music.domain.lyrics.Lyrics
import com.arnav.music.domain.lyrics.LyricsTiming
import com.arnav.music.domain.lyrics.displayLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LyricsTest {

    private fun synced(raw: String, durationMs: Long? = null): List<LyricLine> {
        val parsed = LrcParser.parse(raw, durationMs)
        assertTrue("expected synced lyrics but got $parsed", parsed is Lyrics.Synced)
        return (parsed as Lyrics.Synced).lines
    }

    // region LRC

    @Test fun `standard lrc with metadata and all timestamp styles`() {
        val raw = "[ar:Artist]\n[ti:Title]\n[length:00:09]\n[00:01.00]Hello world\n[00:03.50]Second line here\n[00:05.123]Third\n[00:07:50]Fourth"
        val lines = synced(raw, durationMs = 9_000)
        assertEquals(
            listOf(
                LyricLine(1_000, 3_500, "Hello world"),
                LyricLine(3_500, 5_123, "Second line here"),
                LyricLine(5_123, 7_500, "Third"),
                LyricLine(7_500, 9_000, "Fourth"),
            ),
            lines,
        )
    }

    @Test fun `whole seconds and crlf with bom`() {
        val lines = synced("﻿[00:02]  Spaced   out  \r\n[00:04]Next\r\n")
        assertEquals(listOf(LyricLine(2_000, 4_000, "Spaced out"), LyricLine(4_000, 9_000, "Next")), lines)
    }

    @Test fun `several timestamps on one line are expanded and sorted`() {
        val lines = synced("[00:01.00][00:05.00]Chorus\n[00:03.00]Verse")
        assertEquals(
            listOf(
                LyricLine(1_000, 3_000, "Chorus"),
                LyricLine(3_000, 5_000, "Verse"),
                LyricLine(5_000, 10_000, "Chorus"),
            ),
            lines,
        )
    }

    @Test fun `offset shifts every timestamp`() {
        assertEquals(listOf(1_500L, 3_500L), synced("[offset:+500]\n[00:02.00]A\n[00:04.00]B").map { it.startMs })
        assertEquals(listOf(2_500L, 4_500L), synced("[offset:-500]\n[00:02.00]A\n[00:04.00]B").map { it.startMs })
        assertEquals(listOf(0L, 1_000L), synced("[offset:3000]\n[00:02.00]A\n[00:04.00]B").map { it.startMs })
    }

    @Test fun `enhanced word tags become timed words`() {
        val lines = synced("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world<00:02.80>\n[00:04.00]Next")
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("Hello big world", first.text)
        assertEquals(1_000L, first.startMs)
        assertEquals(4_000L, first.endMs)
        assertEquals(
            listOf(LyricWord(1_000, 1_500, "Hello"), LyricWord(1_500, 2_000, "big"), LyricWord(2_000, 2_800, "world")),
            first.words,
        )
        assertEquals(0.5f, LyricsTiming.wordProgress(first.words[1], 1_750), 0.0001f)
        assertTrue(lines[1].words.isEmpty())
    }

    @Test fun `word without trailing tag ends with its line`() {
        val lines = synced("[00:01.00]<00:01.00>One <00:02.00>two\n[00:03.00]Next")
        assertEquals(listOf(LyricWord(1_000, 2_000, "One"), LyricWord(2_000, 3_000, "two")), lines[0].words)
    }

    @Test fun `long gaps become instrumental lines`() {
        val lines = synced("[00:01.00]One two three\n[00:20.00]After the break")
        assertEquals(
            listOf(
                LyricLine(1_000, 3_500, "One two three"),
                LyricLine(3_500, 20_000, ""),
                LyricLine(20_000, 25_000, "After the break"),
            ),
            lines,
        )
        assertTrue(lines[1].isInstrumental)
    }

    @Test fun `instrumental gap threshold uses words per line`() {
        // 10 words -> estimated 3.5 s. Next at 10.0 s leaves 6.5 s: break inserted.
        val withGap = synced("[00:00.00]a b c d e f g h i j\n[00:10.00]k")
        assertEquals(listOf(0L, 3_500L, 10_000L), withGap.map { it.startMs })
        assertEquals("", withGap[1].text)
        // Next at 9.4 s leaves 5.9 s: no break.
        val noGap = synced("[00:00.00]a b c d e f g h i j\n[00:09.40]k")
        assertEquals(listOf(0L, 9_400L), noGap.map { it.startMs })
        assertEquals(9_400L, noGap[0].endMs)
    }

    @Test fun `intro before the first line is instrumental`() {
        val lines = synced("[00:08.00]Late start")
        assertEquals(listOf(LyricLine(0, 8_000, ""), LyricLine(8_000, 13_000, "Late start")), lines)
        // Under 6 s: no intro line.
        assertEquals(1, synced("[00:05.99]Early").size)
    }

    @Test fun `explicit empty lines are kept and not doubled`() {
        val lines = synced("[00:01.00]Sing\n[00:03.00]\n[00:30.00]Again")
        assertEquals(listOf("Sing", "", "Again"), lines.map { it.text })
        assertEquals(listOf(1_000L, 3_000L, 30_000L), lines.map { it.startMs })
    }

    @Test fun `text without timestamps is plain`() {
        val parsed = LrcParser.parse("﻿Line one\r\n\r\n\r\n\r\nLine two\r\n[Chorus: Someone]\r\n  \r\n")
        assertEquals(Lyrics.Plain(listOf("Line one", "", "Line two", "[Chorus: Someone]")), parsed)
        assertEquals(Lyrics.Plain(listOf("Hello")), LrcParser.parse("[ar:Someone]\n[ti:Song]\nHello"))
    }

    @Test fun `blank or metadata only input is null`() {
        assertNull(LrcParser.parse(""))
        assertNull(LrcParser.parse("  \n \r\n"))
        assertNull(LrcParser.parse("[ar:Someone]\n[ti:Song]"))
    }

    @Test fun `display lines turn breaks into stanza gaps`() {
        val lyrics = LrcParser.parse("[00:08.00]A\n[00:09.00]B\n[00:30.00]C")!!
        assertEquals(listOf("A", "B", "", "C"), lyrics.displayLines())
    }

    // endregion

    // region timing

    @Test fun `active index edges`() {
        val lines = listOf(LyricLine(1_000, 3_000, "a"), LyricLine(3_000, 5_000, "b"), LyricLine(5_000, 9_000, "c"))
        assertEquals(-1, LyricsTiming.activeIndex(emptyList(), 1_000))
        assertEquals(-1, LyricsTiming.activeIndex(lines, 0))
        assertEquals(-1, LyricsTiming.activeIndex(lines, 999))
        assertEquals(0, LyricsTiming.activeIndex(lines, 1_000))
        assertEquals(0, LyricsTiming.activeIndex(lines, 2_999))
        assertEquals(1, LyricsTiming.activeIndex(lines, 3_000))
        assertEquals(2, LyricsTiming.activeIndex(lines, 5_000))
        assertEquals(2, LyricsTiming.activeIndex(lines, 99_999))
        assertEquals(0, LyricsTiming.activeIndex(lines.take(1), 50_000))
    }

    @Test fun `line progress is clamped`() {
        val line = LyricLine(1_000, 3_000, "a")
        assertEquals(0f, LyricsTiming.lineProgress(line, 500), 0f)
        assertEquals(0f, LyricsTiming.lineProgress(line, 1_000), 0f)
        assertEquals(0.5f, LyricsTiming.lineProgress(line, 2_000), 0.0001f)
        assertEquals(1f, LyricsTiming.lineProgress(line, 3_000), 0f)
        assertEquals(1f, LyricsTiming.lineProgress(line, 4_000), 0f)
    }

    // endregion

    // region embedded: byte builders

    private fun bytes(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is ByteArray -> out.write(p)
            is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(p)
            else -> error("unsupported $p")
        }
        return out.toByteArray()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun be24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun synchsafe(v: Int) = byteArrayOf(((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun id3Frame(version: Int, id: String, body: ByteArray): ByteArray =
        bytes(id, if (version == 4) synchsafe(body.size) else be32(body.size), 0, 0, body)

    private fun id3Tag(version: Int, vararg frames: ByteArray, padding: Int = 64): ByteArray {
        val body = bytes(*frames, ByteArray(padding))
        return bytes("ID3", version, 0, 0, synchsafe(body.size), body)
    }

    /** A filler text frame bigger than 127 bytes so synchsafe and plain sizes differ. */
    private fun filler(version: Int) = id3Frame(version, "TXXX", bytes(0, "note", 0, "x".repeat(295)))

    // endregion

    // region embedded: ID3

    @Test fun `id3v24 uslt utf8`() {
        val uslt = bytes(3, "eng", "desc", 0, "Héllo\nWörld".toByteArray(Charsets.UTF_8))
        val tag = id3Tag(4, id3Frame(4, "TIT2", bytes(3, "Title")), filler(4), id3Frame(4, "USLT", uslt))
        val file = bytes(tag, ByteArray(200) { 0x55 })
        assertEquals("Héllo\nWörld", EmbeddedLyrics.fromBytes(file))
    }

    @Test fun `id3v23 uslt utf16 with bom`() {
        val text = "Line A\r\nLine B"
        val uslt = bytes(
            1, "eng",
            0xFF, 0xFE, "d".toByteArray(Charsets.UTF_16LE), 0, 0,
            0xFF, 0xFE, text.toByteArray(Charsets.UTF_16LE),
        )
        val tag = id3Tag(3, filler(3), id3Frame(3, "USLT", uslt))
        assertEquals("Line A\nLine B", EmbeddedLyrics.fromBytes(tag))
    }

    @Test fun `id3v23 uslt utf16 big endian without descriptor`() {
        val uslt = bytes(2, "eng", 0, 0, "Big end".toByteArray(Charsets.UTF_16BE))
        assertEquals("Big end", EmbeddedLyrics.fromBytes(id3Tag(3, id3Frame(3, "USLT", uslt))))
    }

    @Test fun `id3 sylt with millisecond stamps becomes lrc`() {
        val sylt = bytes(
            3, "eng", 2, 1, 0,
            "First", 0, be32(1_000),
            "Second", 0, be32(2_500),
        )
        val out = EmbeddedLyrics.fromBytes(id3Tag(3, id3Frame(3, "SYLT", sylt)))
        assertEquals("[00:01.000]First\n[00:02.500]Second", out)
        val lines = synced(out!!)
        assertEquals(listOf(LyricLine(1_000, 2_500, "First"), LyricLine(2_500, 7_500, "Second")), lines)
    }

    @Test fun `synced uslt wins over plain uslt`() {
        val plain = bytes(3, "eng", 0, "Just words")
        val lrc = bytes(3, "eng", "lrc", 0, "[00:01.00]Timed")
        val out = EmbeddedLyrics.fromBytes(id3Tag(4, id3Frame(4, "USLT", plain), id3Frame(4, "USLT", lrc)))
        assertEquals("[00:01.00]Timed", out)
    }

    @Test fun `id3 without lyrics is null`() {
        assertNull(EmbeddedLyrics.fromBytes(id3Tag(4, id3Frame(4, "TIT2", bytes(3, "Title")))))
    }

    // endregion

    // region embedded: FLAC

    @Test fun `flac vorbis comment lyrics`() {
        fun entry(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return bytes(le32(b.size), b) }
        val vendor = "reference".toByteArray(Charsets.UTF_8)
        val comment = bytes(le32(vendor.size), vendor, le32(3), entry("TITLE=Song"), entry("unsyncedlyrics=Fallback"), entry("lyrics=[00:01.00]Hi"))
        val file = bytes(
            "fLaC",
            0x00, be24(34), ByteArray(34),
            0x06, be24(10), ByteArray(10), // PICTURE block, skipped
            0x84, be24(comment.size), comment,
            ByteArray(32) { 0x11 },
        )
        assertEquals("[00:01.00]Hi", EmbeddedLyrics.fromBytes(file))
    }

    @Test fun `flac unsynced lyrics key`() {
        fun entry(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return bytes(le32(b.size), b) }
        val comment = bytes(le32(0), le32(1), entry("UNSYNCEDLYRICS=Only plain"))
        val file = bytes("fLaC", 0x00, be24(34), ByteArray(34), 0x84, be24(comment.size), comment)
        assertEquals("Only plain", EmbeddedLyrics.fromBytes(file))
    }

    // endregion

    // region embedded: MP4

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = bytes(*content)
        return bytes(be32(8 + body.size), type, body)
    }

    private fun lyrMeta(text: String): ByteArray {
        val data = box("data", be32(1), be32(0), text.toByteArray(Charsets.UTF_8))
        val ilst = box("ilst", box("©nam", box("data", be32(1), be32(0), "Title".toByteArray())), box("©lyr", data))
        val meta = box("meta", be32(0), box("hdlr", ByteArray(25)), ilst)
        return box("udta", meta)
    }

    @Test fun `mp4 lyr atom with moov first`() {
        val file = bytes(
            box("ftyp", "M4A ".toByteArray(), be32(0), "isomM4A ".toByteArray()),
            box("moov", box("mvhd", ByteArray(100)), lyrMeta("Line one\rLine two")),
            box("mdat", ByteArray(500)),
        )
        assertEquals("Line one\nLine two", EmbeddedLyrics.fromBytes(file))
    }

    @Test fun `mp4 lyr atom with moov at the end`() {
        val file = bytes(
            box("ftyp", "M4A ".toByteArray(), be32(0)),
            box("free", ByteArray(16)),
            box("mdat", ByteArray(4_000)),
            box("moov", box("mvhd", ByteArray(100)), box("trak", ByteArray(300)), lyrMeta("[00:01.00]End")),
        )
        assertEquals("[00:01.00]End", EmbeddedLyrics.fromBytes(file))
        val header = EmbeddedLyrics.parseMp4BoxHeader(file, 0)
        assertEquals("ftyp", header?.type)
        assertEquals(8, header?.headerSize)
    }

    @Test fun `mp4 without lyrics is null`() {
        val file = bytes(box("ftyp", "M4A ".toByteArray(), be32(0)), box("moov", box("mvhd", ByteArray(100))))
        assertNull(EmbeddedLyrics.fromBytes(file))
    }

    // endregion

    @Test fun `malformed input never throws`() {
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(0)))
        assertNull(EmbeddedLyrics.fromBytes("ID3".toByteArray()))
        assertNull(EmbeddedLyrics.fromBytes(bytes("ID3", 4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F, "USLT", 0x7F, 0x7F, 0x7F, 0x7F, 0, 0, 3)))
        assertNull(EmbeddedLyrics.fromBytes(bytes("fLaC", 0x84, 0xFF, 0xFF, 0xFF, 1, 2, 3)))
        assertNull(EmbeddedLyrics.fromBytes(bytes(be32(0x7FFFFFFF), "ftyp", "M4A ")))
        assertNull(EmbeddedLyrics.fromBytes(bytes(be32(16), "ftyp", "M4A ", be32(0), be32(1), "moov", be32(-1), be32(-1))))
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(64) { 0x41 }))
    }
}
