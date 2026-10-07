package com.arnav.music.domain.chapters

import com.arnav.music.domain.lyrics.EmbeddedLyrics

/**
 * Chapters embedded in the user's own audio files. Pure byte parsing over an
 * [EmbeddedLyrics.Source] (random access, so artwork and media data are never read); never throws.
 *
 * Supported:
 * - ID3v2.3/2.4 `CHAP` frames, titled by their `TIT2` sub-frame (MP3, also ID3-prefixed FLAC),
 * - MP4/M4A/M4B QuickTime chapter tracks (`trak/tref/chap` → a text track's samples),
 * - MP4 Nero chapters (`moov/udta/chpl`),
 * - FLAC Vorbis comments `CHAPTER001=00:01:02.500` + `CHAPTER001NAME=Title`.
 */
object EmbeddedChapters {
    fun fromBytes(bytes: ByteArray, durationMs: Long? = null): List<Chapter> =
        fromSource(EmbeddedLyrics.ByteArraySource(bytes), durationMs)

    fun fromSource(src: EmbeddedLyrics.Source, durationMs: Long? = null): List<Chapter> {
        val raw = safe {
            val magic = src.read(0, 12)
            when {
                startsWith(magic, 0, "ID3") -> {
                    val chapters = id3(src)
                    if (chapters.isNotEmpty()) chapters
                    else {
                        val after = EmbeddedLyrics.id3TotalSize(magic)
                        if (after > 0 && startsWith(src.read(after, 4), 0, "fLaC")) flac(src, after) else emptyList()
                    }
                }
                startsWith(magic, 0, "fLaC") -> flac(src, 0)
                EmbeddedLyrics.isMp4(magic) -> mp4(src)
                else -> emptyList()
            }
        } ?: emptyList()
        return ChapterMath.normalize(raw, durationMs)
    }

    // region ID3v2

    private fun id3(src: EmbeddedLyrics.Source): List<Chapter> {
        val h = src.read(0, 10)
        if (h.size < 10) return emptyList()
        val ver = h[3].toInt() and 0xFF
        if (ver != 3 && ver != 4) return emptyList() // CHAP exists from ID3v2.3 on
        val flags = h[5].toInt() and 0xFF
        val tagEnd = minOf(10L + synchsafe(h, 6), src.length)
        val unsyncTag = (flags and 0x80) != 0
        val extended = (flags and 0x40) != 0

        var source = src
        var pos = 10L
        var end = tagEnd
        if (unsyncTag && ver == 3) {
            val body = src.read(10, minOf(tagEnd - 10, MAX_TAG_COPY.toLong()).toInt())
            val clear = deUnsync(body)
            source = EmbeddedLyrics.ByteArraySource(clear)
            pos = 0L
            end = clear.size.toLong()
        }
        if (extended) {
            val e = source.read(pos, 4)
            if (e.size < 4) return emptyList()
            pos += if (ver == 4) synchsafe(e, 0) else 4 + u32(e, 0)
        }

        val out = ArrayList<Chapter>()
        var guard = 0
        while (pos + 10 <= end && guard++ < MAX_FRAMES) {
            val fh = source.read(pos, 10)
            if (fh.size < 10 || fh[0].toInt() == 0) break
            val id = latin1(fh, 0, 4)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            var size = frameSize(fh, 4, ver)
            if (size < 0) break
            if (pos + 10 + size > end) size = end - pos - 10
            if (id == "CHAP" && size in 17..MAX_FRAME.toLong()) {
                val body = frameBody(source.read(pos + 10, size.toInt()), ver, fh[9].toInt() and 0xFF)
                body?.let { chap(it, ver) }?.let { out += it }
                if (out.size >= MAX_CHAPTERS) break
            }
            pos += 10 + size
        }
        return out
    }

    /** CHAP body: element id \0, start ms, end ms, start offset, end offset, then sub-frames. */
    private fun chap(b: ByteArray, ver: Int): Chapter? {
        var p = 0
        while (p < b.size && b[p].toInt() != 0) p++
        p++ // terminator
        if (p + 16 > b.size) return null
        val start = u32(b, p)
        p += 16
        var title: String? = null
        var guard = 0
        while (p + 10 <= b.size && guard++ < 64) {
            val id = latin1(b, p, 4)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = frameSize(b, p + 4, ver).toInt()
            if (size < 0 || p + 10 + size > b.size) break
            if (id == "TIT2" && size > 1) {
                val body = frameBody(b.copyOfRange(p + 10, p + 10 + size), ver, b[p + 9].toInt() and 0xFF)
                if (body != null) title = text(body)
                break
            }
            p += 10 + size
        }
        return Chapter(start, title.orEmpty())
    }

    private fun frameSize(b: ByteArray, at: Int, ver: Int): Long {
        val plain = u32(b, at)
        if (ver != 4) return plain
        val highBits = (b[at].toInt() or b[at + 1].toInt() or b[at + 2].toInt() or b[at + 3].toInt()) and 0x80
        return if (highBits != 0) plain else synchsafe(b, at)
    }

    private fun frameBody(raw: ByteArray, ver: Int, flags: Int): ByteArray? {
        var b = raw
        if (ver == 3) {
            if ((flags and 0x80) != 0 || (flags and 0x40) != 0) return null // compressed / encrypted
            if ((flags and 0x20) != 0) b = b.copyOfRange(minOf(1, b.size), b.size) // group id
        } else {
            if ((flags and 0x08) != 0 || (flags and 0x04) != 0) return null
            var skip = 0
            if ((flags and 0x40) != 0) skip += 1
            if ((flags and 0x01) != 0) skip += 4
            if (skip > 0) b = b.copyOfRange(minOf(skip, b.size), b.size)
            if ((flags and 0x02) != 0) b = deUnsync(b)
        }
        return b
    }

    /** Text frame body → first string (encodings 0 Latin-1, 1 UTF-16 BOM, 2 UTF-16BE, 3 UTF-8). */
    private fun text(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val enc = b[0].toInt() and 0xFF
        val from = 1
        val s = when (enc) {
            0 -> String(b, from, b.size - from, Charsets.ISO_8859_1)
            1 -> {
                val b0 = b.getOrNull(1)?.toInt()?.and(0xFF)
                val b1 = b.getOrNull(2)?.toInt()?.and(0xFF)
                when {
                    b0 == 0xFF && b1 == 0xFE -> String(b, 3, b.size - 3, Charsets.UTF_16LE)
                    b0 == 0xFE && b1 == 0xFF -> String(b, 3, b.size - 3, Charsets.UTF_16BE)
                    else -> String(b, from, b.size - from, Charsets.UTF_16LE)
                }
            }
            2 -> String(b, from, b.size - from, Charsets.UTF_16BE)
            else -> String(b, from, b.size - from, Charsets.UTF_8)
        }
        return s.substringBefore('\u0000').trim().ifEmpty { null }
    }

    // endregion

    // region FLAC

    private fun flac(src: EmbeddedLyrics.Source, offset: Long): List<Chapter> {
        if (!startsWith(src.read(offset, 4), 0, "fLaC")) return emptyList()
        var pos = offset + 4
        var guard = 0
        while (guard++ < 256) {
            val h = src.read(pos, 4)
            if (h.size < 4) return emptyList()
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = u24(h, 1)
            if (type == 4 && len in 8..MAX_FRAME.toLong()) return vorbisChapters(src.read(pos + 4, len.toInt()))
            if (last || type == 127) return emptyList()
            pos += 4 + len
        }
        return emptyList()
    }

    private val VORBIS_KEY = Regex("""CHAPTER(\d{1,3})(NAME)?""", RegexOption.IGNORE_CASE)
    private val VORBIS_TIME = Regex("""(\d{1,3}):(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?""")

    private fun vorbisChapters(b: ByteArray): List<Chapter> {
        if (b.size < 8) return emptyList()
        var p = 4L + le32(b, 0)
        if (p + 4 > b.size) return emptyList()
        val count = le32(b, p.toInt())
        p += 4
        val starts = HashMap<Int, Long>()
        val names = HashMap<Int, String>()
        var i = 0L
        while (i < count && i < 10_000 && p + 4 <= b.size) {
            val len = le32(b, p.toInt())
            p += 4
            if (len < 0 || p + len > b.size) break
            val entry = String(b, p.toInt(), len.toInt(), Charsets.UTF_8)
            p += len
            i++
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = VORBIS_KEY.matchEntire(entry.substring(0, eq)) ?: continue
            val n = key.groupValues[1].toInt()
            val value = entry.substring(eq + 1).trim()
            if (key.groupValues[2].isNotEmpty()) names[n] = value
            else VORBIS_TIME.matchEntire(value)?.let { m ->
                val h = m.groupValues[1].toLong()
                val min = m.groupValues[2].toLong()
                val s = m.groupValues[3].toLong()
                val frac = m.groupValues[4].padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
                starts[n] = ((h * 60 + min) * 60 + s) * 1000 + frac
            }
        }
        return starts.entries.sortedBy { it.key }.map { (n, start) -> Chapter(start, names[n].orEmpty()) }
    }

    // endregion

    // region MP4

    private class Box(val type: String, val start: Long, val end: Long)

    private fun mp4(src: EmbeddedLyrics.Source): List<Chapter> {
        val moov = children(src, 0, src.length).firstOrNull { it.type == "moov" } ?: return emptyList()
        val moovKids = children(src, moov.start, moov.end)
        val quickTime = quickTimeChapters(src, moovKids)
        if (quickTime.size >= 2) return quickTime
        val udta = moovKids.firstOrNull { it.type == "udta" } ?: return quickTime
        val chpl = children(src, udta.start, udta.end).firstOrNull { it.type == "chpl" } ?: return quickTime
        return nero(src, chpl)
    }

    /** Nero `chpl`: version/flags, [u32 reserved when version 1], u8 count, then (u64 start in 100 ns, u8 len, UTF-8 title). */
    private fun nero(src: EmbeddedLyrics.Source, box: Box): List<Chapter> {
        val len = box.end - box.start
        if (len < 5 || len > MAX_FRAME) return emptyList()
        val b = src.read(box.start, len.toInt())
        if (b.size < 5) return emptyList()
        val version = b[0].toInt() and 0xFF
        var p = if (version != 0) 8 else 4
        if (p >= b.size) return emptyList()
        val count = b[p].toInt() and 0xFF
        p++
        val out = ArrayList<Chapter>(count)
        repeat(count) {
            if (p + 9 > b.size) return out
            val start = u64(b, p) / 10_000L
            val titleLen = b[p + 8].toInt() and 0xFF
            p += 9
            if (p + titleLen > b.size) return out
            out += Chapter(start, String(b, p, titleLen, Charsets.UTF_8))
            p += titleLen
        }
        return out
    }

    private fun quickTimeChapters(src: EmbeddedLyrics.Source, moovKids: List<Box>): List<Chapter> {
        val traks = moovKids.filter { it.type == "trak" }.take(MAX_TRACKS)
        if (traks.size < 2) return emptyList()
        val chapterIds = HashSet<Long>()
        val byId = HashMap<Long, Box>()
        for (trak in traks) {
            val kids = children(src, trak.start, trak.end)
            kids.firstOrNull { it.type == "tkhd" }?.let { tkhd ->
                val b = src.read(tkhd.start, 24)
                if (b.size >= 24) byId[u32(b, if ((b[0].toInt() and 0xFF) == 1) 20 else 12)] = trak
            }
            val tref = kids.firstOrNull { it.type == "tref" } ?: continue
            val chap = children(src, tref.start, tref.end).firstOrNull { it.type == "chap" } ?: continue
            val b = src.read(chap.start, minOf(chap.end - chap.start, 64L).toInt())
            var i = 0
            while (i + 4 <= b.size) { chapterIds += u32(b, i); i += 4 }
        }
        for (id in chapterIds) {
            val trak = byId[id] ?: continue
            val chapters = textTrack(src, trak)
            if (chapters.size >= 2) return chapters
        }
        return emptyList()
    }

    private fun textTrack(src: EmbeddedLyrics.Source, trak: Box): List<Chapter> {
        val mdia = child(src, trak, "mdia") ?: return emptyList()
        val mdhd = child(src, mdia, "mdhd") ?: return emptyList()
        val hd = src.read(mdhd.start, 24)
        if (hd.size < 24) return emptyList()
        val timescale = u32(hd, if ((hd[0].toInt() and 0xFF) == 1) 20 else 12)
        if (timescale <= 0) return emptyList()
        val stbl = child(src, mdia, "minf")?.let { child(src, it, "stbl") } ?: return emptyList()
        val kids = children(src, stbl.start, stbl.end)
        fun table(type: String): ByteArray? = kids.firstOrNull { it.type == type }?.let { box ->
            val len = box.end - box.start
            if (len < 8 || len > MAX_FRAME) null else src.read(box.start, len.toInt())
        }

        // Sample start times (stts).
        val stts = table("stts") ?: return emptyList()
        val starts = ArrayList<Long>()
        var t = 0L
        run {
            val n = u32(stts, 4)
            var p = 8
            var e = 0L
            while (e < n && p + 8 <= stts.size && starts.size < MAX_CHAPTERS) {
                val count = u32(stts, p)
                val delta = u32(stts, p + 4)
                var k = 0L
                while (k < count && starts.size < MAX_CHAPTERS) { starts += t; t += delta; k++ }
                p += 8; e++
            }
        }
        val sampleCount = starts.size
        if (sampleCount < 2) return emptyList()

        // Sample sizes (stsz).
        val stsz = table("stsz") ?: return emptyList()
        if (stsz.size < 12) return emptyList()
        val fixed = u32(stsz, 4)
        val sizes = LongArray(sampleCount) { i ->
            if (fixed != 0L) fixed else if (12 + i * 4 + 4 <= stsz.size) u32(stsz, 12 + i * 4) else 0L
        }

        // Chunk offsets (stco / co64) and samples per chunk (stsc).
        val offsets: List<Long> = table("stco")?.let { b ->
            val n = minOf(u32(b, 4), ((b.size - 8) / 4).toLong()).toInt()
            List(n) { u32(b, 8 + it * 4) }
        } ?: table("co64")?.let { b ->
            val n = minOf(u32(b, 4), ((b.size - 8) / 8).toLong()).toInt()
            List(n) { u64(b, 8 + it * 8) }
        } ?: return emptyList()
        val stsc = table("stsc") ?: return emptyList()
        val runs = minOf(u32(stsc, 4), ((stsc.size - 8) / 12).toLong()).toInt()
        val sampleOffsets = LongArray(sampleCount) { -1L }
        var sample = 0
        for (r in 0 until runs) {
            val first = u32(stsc, 8 + r * 12).toInt()
            val perChunk = u32(stsc, 12 + r * 12)
            val nextFirst = if (r + 1 < runs) u32(stsc, 8 + (r + 1) * 12).toInt() else offsets.size + 1
            for (chunk in first until nextFirst) {
                var off = offsets.getOrNull(chunk - 1) ?: break
                var k = 0L
                while (k < perChunk && sample < sampleCount) {
                    sampleOffsets[sample] = off
                    off += sizes[sample]
                    sample++; k++
                }
                if (sample >= sampleCount) break
            }
            if (sample >= sampleCount) break
        }

        val out = ArrayList<Chapter>(sampleCount)
        for (i in 0 until sampleCount) {
            val off = sampleOffsets[i]
            val title = if (off >= 0 && sizes[i] >= 2) sampleText(src, off, sizes[i]) else ""
            out += Chapter(starts[i] * 1000L / timescale, title)
        }
        return out
    }

    /** A QuickTime text sample: u16 length, then UTF-8 (or BOM-marked UTF-16) text, then optional atoms. */
    private fun sampleText(src: EmbeddedLyrics.Source, offset: Long, size: Long): String {
        val head = src.read(offset, 2)
        if (head.size < 2) return ""
        val len = minOf(u16(head, 0).toLong(), size - 2, 1024L).toInt()
        if (len <= 0) return ""
        val b = src.read(offset + 2, len)
        if (b.size >= 2) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        }
        return String(b, Charsets.UTF_8).substringBefore('\u0000')
    }

    private fun child(src: EmbeddedLyrics.Source, parent: Box, type: String): Box? =
        children(src, parent.start, parent.end).firstOrNull { it.type == type }

    /** Child boxes of the container whose content spans [start, end). */
    private fun children(src: EmbeddedLyrics.Source, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        var guard = 0
        while (pos + 8 <= end && guard++ < 2_000) {
            val box = EmbeddedLyrics.parseMp4BoxHeader(src.read(pos, 16)) ?: break
            val size = if (box.size == 0L) end - pos else box.size
            if (size < box.headerSize) break
            out += Box(box.type, pos + box.headerSize, minOf(pos + size, end))
            pos += size
        }
        return out
    }

    // endregion

    // region bytes

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun startsWith(b: ByteArray, at: Int, ascii: String): Boolean {
        if (at < 0 || b.size < at + ascii.length) return false
        for (i in ascii.indices) if (b[at + i].toInt() and 0xFF != ascii[i].code) return false
        return true
    }

    private fun latin1(b: ByteArray, at: Int, len: Int): String =
        if (at < 0 || b.size < at + len) "" else String(b, at, len, Charsets.ISO_8859_1)

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u24(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 16) or ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at + 2].toLong() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun synchsafe(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0x7F) shl 21) or ((b[at + 1].toLong() and 0x7F) shl 14) or
            ((b[at + 2].toLong() and 0x7F) shl 7) or (b[at + 3].toLong() and 0x7F)

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val v = b[i].toInt() and 0xFF
            out.write(v)
            i += if (v == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) 2 else 1
        }
        return out.toByteArray()
    }

    // endregion

    private const val MAX_FRAME = 4 * 1024 * 1024
    private const val MAX_TAG_COPY = 16 * 1024 * 1024
    private const val MAX_FRAMES = 4_096
    private const val MAX_CHAPTERS = 1_000
    private const val MAX_TRACKS = 32
}
