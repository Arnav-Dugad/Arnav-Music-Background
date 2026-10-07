package com.arnav.music.domain

import com.arnav.music.domain.chapters.Chapter
import com.arnav.music.domain.chapters.ChapterMath
import com.arnav.music.domain.chapters.DescriptionChapters
import com.arnav.music.domain.chapters.EmbeddedChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ChaptersTest {

    // region description timestamps

    @Test fun `typical youtube description`() {
        val d = """
            Full album stream. Buy it here: https://example.com/buy?t=12:34

            Tracklist:
            0:00 Intro
            3:21 - Song Two
            7:45 – Song Three (feat. Someone)
            1:02:03 Finale

            Follow me at 3:45 lol
        """.trimIndent()
        assertEquals(
            listOf(
                Chapter(0, "Intro"),
                Chapter(201_000, "Song Two"),
                Chapter(465_000, "Song Three (feat. Someone)"),
                Chapter(3_723_000, "Finale"),
            ),
            DescriptionChapters.parse(d),
        )
    }

    @Test fun `brackets numbering ranges and trailing timestamps`() {
        val brackets = "[00:00] First\n(02:10) Second\n03:30 | Third"
        assertEquals(listOf("First", "Second", "Third"), DescriptionChapters.parse(brackets).map { it.title })

        val numbered = "1. 0:00 Alpha\n2. 4:00 Beta\n3) 8:00 Gamma"
        assertEquals(listOf(0L, 240_000L, 480_000L), DescriptionChapters.parse(numbered).map { it.startMs })
        assertEquals("Alpha", DescriptionChapters.parse(numbered)[0].title)

        val ranges = "0:00 - 3:45 Opening\n3:45 - 9:00 Middle\n9:00 – 12:00 Closing"
        assertEquals(listOf("Opening", "Middle", "Closing"), DescriptionChapters.parse(ranges).map { it.title })

        val trailing = "Opening - 0:00\nMiddle (5:00)\nClosing 10:00"
        val t = DescriptionChapters.parse(trailing)
        assertEquals(listOf(0L, 300_000L, 600_000L), t.map { it.startMs })
        assertEquals(listOf("Opening", "Middle", "Closing"), t.map { it.title })
    }

    @Test fun `rejects lists that break youtube rules`() {
        // Fewer than three entries.
        assertTrue(DescriptionChapters.parse("0:00 Intro\n2:00 Outro").isEmpty())
        // Does not start at 0:00.
        assertTrue(DescriptionChapters.parse("0:10 A\n2:00 B\n4:00 C").isEmpty())
        // Not ascending right away.
        assertTrue(DescriptionChapters.parse("0:00 A\n5:00 B\n4:00 C\n6:00 D").isEmpty())
        // Timestamps only inside prose.
        assertTrue(DescriptionChapters.parse("Best part at 0:00 really\nand at 1:00 too\nalso 2:00 here").isEmpty())
        // Impossible hh:mm:ss.
        assertTrue(DescriptionChapters.parse("0:00 A\n1:75:00 B\n2:00:00 C").isEmpty())
        assertTrue(DescriptionChapters.parse("").isEmpty())
    }

    @Test fun `first valid list wins and later restarts are ignored`() {
        val d = "0:00 Bad\n1:00 Bad two\n\nReal list\n0:00 One\n1:00 Two\n2:00 Three\n\nAgain\n0:00 X\n1:00 Y\n2:00 Z"
        // The first list stops at the second 0:00 with only two entries, so the second list wins.
        assertEquals(listOf("One", "Two", "Three"), DescriptionChapters.parse(d).map { it.title })
    }

    @Test fun `untitled entries and entries past the duration`() {
        val d = "0:00\n1:00 -\n2:00 Third\n10:00 Way past the end"
        val c = DescriptionChapters.parse(d, durationMs = 300_000)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Third"), c.map { it.title })
        // Dropping the out-of-range entry may leave too few chapters.
        assertTrue(DescriptionChapters.parse("0:00 A\n1:00 B\n9:00 C", durationMs = 120_000).isEmpty())
    }

    // endregion

    // region chapter math

    private val three = listOf(Chapter(0, "A"), Chapter(60_000, "B"), Chapter(120_000, "C"))

    @Test fun `active index and seek targets`() {
        assertEquals(-1, ChapterMath.activeIndex(emptyList(), 5))
        assertEquals(0, ChapterMath.activeIndex(three, 0))
        assertEquals(0, ChapterMath.activeIndex(three, 59_999))
        assertEquals(1, ChapterMath.activeIndex(three, 60_000))
        assertEquals(2, ChapterMath.activeIndex(three, 9_999_999))

        assertEquals(120_000L, ChapterMath.nextStart(three, 61_000))
        assertNull(ChapterMath.nextStart(three, 130_000))
        // Well into B: restart B. Just after B starts: go back to A. In A: restart A.
        assertEquals(60_000L, ChapterMath.previousStart(three, 70_000))
        assertEquals(0L, ChapterMath.previousStart(three, 61_000))
        assertEquals(0L, ChapterMath.previousStart(three, 1_000))
        assertNull(ChapterMath.previousStart(emptyList(), 1_000))
    }

    @Test fun `normalize sorts dedupes and needs two chapters`() {
        val raw = listOf(Chapter(60_000, " B "), Chapter(0, ""), Chapter(60_000, "dup"), Chapter(-5, "neg"))
        assertEquals(listOf(Chapter(0, "Chapter 1"), Chapter(60_000, "B")), ChapterMath.normalize(raw))
        assertTrue(ChapterMath.normalize(listOf(Chapter(0, "only"))).isEmpty())
        assertTrue(ChapterMath.normalize(raw, durationMs = 30_000).isEmpty())
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

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = bytes(be32((v ushr 32).toInt()), be32(v.toInt()))
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun be24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun synchsafe(v: Int) = byteArrayOf(((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun id3Frame(version: Int, id: String, body: ByteArray): ByteArray =
        bytes(id, if (version == 4) synchsafe(body.size) else be32(body.size), 0, 0, body)

    private fun id3Tag(version: Int, vararg frames: ByteArray, padding: Int = 64): ByteArray {
        val body = bytes(*frames, ByteArray(padding))
        return bytes("ID3", version, 0, 0, synchsafe(body.size), body)
    }

    private fun chap(version: Int, id: String, startMs: Int, endMs: Int, title: ByteArray?): ByteArray {
        val sub = title?.let { id3Frame(version, "TIT2", it) } ?: ByteArray(0)
        return id3Frame(version, "CHAP", bytes(id, 0, be32(startMs), be32(endMs), be32(-1), be32(-1), sub))
    }

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = bytes(*content)
        return bytes(be32(8 + body.size), type, body)
    }

    // endregion

    // region embedded: ID3 CHAP

    @Test fun `id3v23 chap frames with tit2 titles in any order`() {
        val art = id3Frame(3, "APIC", bytes(0, "image/png", 0, 3, 0, ByteArray(400)))
        val tag = id3Tag(
            3,
            id3Frame(3, "TIT2", bytes(0, "Whole mix")),
            art,
            chap(3, "ch1", 90_000, 200_000, bytes(0, "Second")),
            chap(3, "ch0", 0, 90_000, bytes(1, 0xFF, 0xFE, "First".toByteArray(Charsets.UTF_16LE), 0, 0)),
            chap(3, "ch2", 200_000, 300_000, null),
        )
        val file = bytes(tag, ByteArray(1000) { 0x55 })
        assertEquals(
            listOf(Chapter(0, "First"), Chapter(90_000, "Second"), Chapter(200_000, "Chapter 3")),
            EmbeddedChapters.fromBytes(file),
        )
    }

    @Test fun `id3v24 chap with synchsafe sizes and utf8 titles`() {
        val tag = id3Tag(
            4,
            id3Frame(4, "TXXX", bytes(3, "note", 0, "x".repeat(300))),
            chap(4, "a", 0, 1_000, bytes(3, "Öpening".toByteArray(Charsets.UTF_8))),
            chap(4, "b", 61_500, 70_000, bytes(3, "Encore".toByteArray(Charsets.UTF_8))),
        )
        assertEquals(listOf(Chapter(0, "Öpening"), Chapter(61_500, "Encore")), EmbeddedChapters.fromBytes(tag))
        // Past the known duration → dropped → too few chapters.
        assertTrue(EmbeddedChapters.fromBytes(tag, durationMs = 60_000).isEmpty())
    }

    @Test fun `no chapters and garbage never throw`() {
        assertTrue(EmbeddedChapters.fromBytes(id3Tag(3, id3Frame(3, "TIT2", bytes(0, "Song")))).isEmpty())
        assertTrue(EmbeddedChapters.fromBytes(ByteArray(0)).isEmpty())
        assertTrue(EmbeddedChapters.fromBytes(bytes("ID3", 3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F, "CHAP", 0xFF, 0xFF)).isEmpty())
        assertTrue(EmbeddedChapters.fromBytes(ByteArray(64) { it.toByte() }).isEmpty())
    }

    // endregion

    // region embedded: FLAC

    @Test fun `flac vorbis chapter comments`() {
        val comments = listOf("TITLE=Set", "CHAPTER002=00:10:00.5", "CHAPTER001=00:00:00.000", "CHAPTER001NAME=Warmup", "CHAPTER002NAME=Peak")
        val vc = bytes(le32(4), "test", le32(comments.size), *comments.map { val b = it.toByteArray(); bytes(le32(b.size), b) }.toTypedArray())
        val streamInfo = bytes(0x00, be24(34), ByteArray(34))
        val file = bytes("fLaC", streamInfo, bytes(0x84, be24(vc.size)), vc, ByteArray(100))
        assertEquals(listOf(Chapter(0, "Warmup"), Chapter(600_500, "Peak")), EmbeddedChapters.fromBytes(file))
    }

    // endregion

    // region embedded: MP4

    private val ftyp = box("ftyp", "M4A ".toByteArray(), be32(0), "isomM4A ".toByteArray())

    @Test fun `mp4 nero chpl`() {
        fun entry(start100ns: Long, title: String): ByteArray {
            val t = title.toByteArray(Charsets.UTF_8)
            return bytes(be64(start100ns), t.size, t)
        }
        val chpl = box("chpl", bytes(1, 0, 0, 0), be32(0), bytes(3), entry(0, "Intro"), entry(1_200_000_000L, "Part two"), entry(3_000_000_000L, "Ende"))
        val file = bytes(ftyp, box("mdat", ByteArray(64)), box("moov", box("mvhd", ByteArray(100)), box("udta", chpl)))
        assertEquals(
            listOf(Chapter(0, "Intro"), Chapter(120_000, "Part two"), Chapter(300_000, "Ende")),
            EmbeddedChapters.fromBytes(file),
        )
    }

    @Test fun `mp4 quicktime chapter text track`() {
        fun sample(title: String): ByteArray { val t = title.toByteArray(Charsets.UTF_8); return bytes(be16(t.size), t) }
        val samples = listOf(sample("One"), sample("Two"), sample("Three"))
        val mdat = box("mdat", *samples.toTypedArray())
        val first = ftyp.size + 8
        val o1 = first
        val o2 = o1 + samples[0].size
        val o3 = o2 + samples[1].size

        fun tkhd(id: Int) = box("tkhd", be32(0), be32(0), be32(0), be32(id), ByteArray(68))
        val audio = box(
            "trak", tkhd(1), box("tref", box("chap", be32(2))),
            box("mdia", box("mdhd", be32(0), be32(0), be32(0), be32(44_100), be32(0), ByteArray(4))),
        )
        val stbl = box(
            "stbl",
            box("stsd", be32(0), be32(0)),
            // Two chunks: [One, Two] then [Three]. Times in a 1000 Hz timescale: 0, 90 s, 200 s.
            box("stts", be32(0), be32(3), be32(1), be32(90_000), be32(1), be32(110_000), be32(1), be32(50_000)),
            box("stsz", be32(0), be32(0), be32(3), be32(samples[0].size), be32(samples[1].size), be32(samples[2].size)),
            box("stsc", be32(0), be32(2), be32(1), be32(2), be32(1), be32(2), be32(1), be32(1)),
            box("stco", be32(0), be32(2), be32(o1), be32(o3)),
        )
        val text = box(
            "trak", tkhd(2),
            box("mdia", box("mdhd", be32(0), be32(0), be32(0), be32(1_000), be32(0), ByteArray(4)), box("minf", stbl)),
        )
        val file = bytes(ftyp, mdat, box("moov", box("mvhd", ByteArray(100)), audio, text))
        assertEquals(o2, o1 + samples[0].size)
        assertEquals(
            listOf(Chapter(0, "One"), Chapter(90_000, "Two"), Chapter(200_000, "Three")),
            EmbeddedChapters.fromBytes(file),
        )
    }

    @Test fun `mp4 without chapters`() {
        val file = bytes(ftyp, box("moov", box("mvhd", ByteArray(100)), box("trak", box("tkhd", ByteArray(84)))), box("mdat", ByteArray(10)))
        assertTrue(EmbeddedChapters.fromBytes(file).isEmpty())
    }

    // endregion
}
