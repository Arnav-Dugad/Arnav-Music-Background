package com.arnav.music.domain.lyrics

/**
 * Extracts lyrics that are embedded in the user's own audio files. Pure byte parsing; never throws.
 *
 * Supported: ID3v2.2/2.3/2.4 (USLT/ULT, SYLT/SLT), FLAC VORBIS_COMMENT (LYRICS, UNSYNCEDLYRICS),
 * MP4/M4A `moov/udta/meta/ilst/©lyr/data`.
 *
 * The parsers walk the file through [Source] and only read headers plus the frames/boxes they
 * need, so large artwork or a `moov` box at the end of an MP4 never has to be loaded whole.
 */
object EmbeddedLyrics {
    /** Random-access view of a file. [read] returns fewer bytes (possibly none) near EOF. */
    interface Source {
        val length: Long
        fun read(position: Long, size: Int): ByteArray
    }

    class ByteArraySource(private val bytes: ByteArray) : Source {
        override val length: Long get() = bytes.size.toLong()
        override fun read(position: Long, size: Int): ByteArray {
            if (position < 0 || size <= 0 || position >= bytes.size) return ByteArray(0)
            val start = position.toInt()
            val end = minOf(bytes.size.toLong(), position + size).toInt()
            return bytes.copyOfRange(start, end)
        }
    }

    /** Header of one ISO-BMFF box. [size] includes the header; [headerSize] is 8 or 16. */
    data class Mp4Box(val type: String, val size: Long, val headerSize: Int)

    private const val MAX_FRAME = 4 * 1024 * 1024
    private const val MAX_TAG_COPY = 16 * 1024 * 1024
    private const val LYR = "©lyr"

    /** Sniffs the format of [head] (the start of a file, or a whole small file) and extracts lyrics. */
    fun fromBytes(head: ByteArray): String? = fromSource(ByteArraySource(head))

    fun fromSource(src: Source): String? = safe {
        val magic = src.read(0, 12)
        when {
            startsWith(magic, 0, "ID3") -> {
                id3(src) ?: run {
                    // FLAC (rarely) carries an ID3v2 prefix.
                    val after = id3TotalSize(magic)
                    if (after > 0 && startsWith(src.read(after, 4), 0, "fLaC")) flac(src, after) else null
                }
            }
            startsWith(magic, 0, "fLaC") -> flac(src, 0)
            isMp4(magic) -> mp4(src)
            else -> null
        }
    }?.let(::clean)

    fun isMp4(head: ByteArray): Boolean = head.size >= 8 && startsWith(head, 4, "ftyp")

    /** Parses a box header at [offset] of [bytes] (needs 8 bytes, 16 for 64-bit sizes). */
    fun parseMp4BoxHeader(bytes: ByteArray, offset: Int = 0): Mp4Box? {
        if (offset < 0 || bytes.size - offset < 8) return null
        val size32 = u32(bytes, offset)
        val type = latin1(bytes, offset + 4, 4)
        return when (size32) {
            1L -> if (bytes.size - offset >= 16) Mp4Box(type, u64(bytes, offset + 8), 16) else null
            0L -> Mp4Box(type, 0L, 8) // extends to end of the container
            else -> if (size32 < 8) null else Mp4Box(type, size32, 8)
        }
    }

    // region ID3v2

    /** Total size of the ID3v2 tag that starts [head] (header + body + footer), or 0. */
    fun id3TotalSize(head: ByteArray): Long {
        if (head.size < 10 || !startsWith(head, 0, "ID3")) return 0
        val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
        return 10L + synchsafe(head, 6) + footer
    }

    private fun id3(src: Source): String? {
        val h = src.read(0, 10)
        if (h.size < 10) return null
        val ver = h[3].toInt() and 0xFF
        if (ver !in 2..4) return null
        val flags = h[5].toInt() and 0xFF
        val tagEnd = minOf(10L + synchsafe(h, 6), src.length)
        val unsyncTag = (flags and 0x80) != 0
        val extended = (flags and 0x40) != 0

        // Whole-tag unsynchronisation (v2.2/2.3): undo it in memory, then walk frames there.
        val (source, start, end) = if (unsyncTag && ver < 4) {
            val body = src.read(10, minOf(tagEnd - 10, MAX_TAG_COPY.toLong()).toInt())
            val clear = deUnsync(body, 0, body.size)
            Triple<Source, Long, Long>(ByteArraySource(clear), 0L, clear.size.toLong())
        } else Triple(src, 10L, tagEnd)

        var pos = start
        if (extended && ver >= 3) {
            val e = source.read(pos, 4)
            if (e.size < 4) return null
            pos += if (ver == 4) synchsafe(e, 0) else 4 + u32(e, 0)
        }

        val uslt = ArrayList<String>()
        var sylt: String? = null
        val idLen = if (ver == 2) 3 else 4
        val hdrLen = if (ver == 2) 6 else 10
        var guard = 0
        while (pos + hdrLen <= end && guard++ < 4096) {
            val fh = source.read(pos, hdrLen)
            if (fh.size < hdrLen || fh[0].toInt() == 0) break
            val id = latin1(fh, 0, idLen)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            var size = when (ver) {
                2 -> u24(fh, 3)
                3 -> u32(fh, 4)
                else -> v24FrameSize(source, fh, pos, end)
            }
            if (size < 0) break
            if (pos + hdrLen + size > end) size = end - pos - hdrLen
            val formatFlags = if (ver == 2) 0 else fh[9].toInt() and 0xFF
            val isLyrics = id == "USLT" || id == "ULT"
            val isSynced = id == "SYLT" || id == "SLT"
            if ((isLyrics || isSynced) && size in 1..MAX_FRAME.toLong()) {
                val raw = source.read(pos + hdrLen, size.toInt())
                val body = frameBody(raw, ver, formatFlags)
                if (body != null) {
                    if (isLyrics) uslt(body)?.let { uslt.add(it) }
                    else if (sylt == null) sylt = sylt(body)
                }
            }
            pos += hdrLen + size
        }
        val syncedUslt = uslt.firstOrNull { LrcParser.looksSynced(it) }
        return syncedUslt ?: sylt ?: uslt.maxByOrNull { it.length }
    }

    /** v2.4 sizes should be synchsafe, but some writers used plain integers; pick the one that lands on a frame. */
    private fun v24FrameSize(src: Source, fh: ByteArray, pos: Long, end: Long): Long {
        val plain = u32(fh, 4)
        val highBits = (fh[4].toInt() or fh[5].toInt() or fh[6].toInt() or fh[7].toInt()) and 0x80
        if (highBits != 0) return plain
        val safe = synchsafe(fh, 4)
        if (safe == plain) return safe
        if (landsOnFrame(src, pos + 10 + safe, end)) return safe
        if (landsOnFrame(src, pos + 10 + plain, end)) return plain
        return safe
    }

    private fun landsOnFrame(src: Source, at: Long, end: Long): Boolean {
        if (at == end) return true
        if (at > end) return false
        val b = src.read(at, 4)
        if (b.isEmpty()) return true
        if (b[0].toInt() == 0) return true // padding
        return b.size == 4 && latin1(b, 0, 4).all { it in 'A'..'Z' || it in '0'..'9' }
    }

    private fun frameBody(raw: ByteArray, ver: Int, flags: Int): ByteArray? {
        var b = raw
        when (ver) {
            3 -> {
                if ((flags and 0x80) != 0 || (flags and 0x40) != 0) return null // compressed / encrypted
                if ((flags and 0x20) != 0) b = b.copyOfRange(minOf(1, b.size), b.size) // group id
            }
            4 -> {
                if ((flags and 0x08) != 0 || (flags and 0x04) != 0) return null
                var skip = 0
                if ((flags and 0x40) != 0) skip += 1
                if ((flags and 0x01) != 0) skip += 4
                if (skip > 0) b = b.copyOfRange(minOf(skip, b.size), b.size)
                if ((flags and 0x02) != 0) b = deUnsync(b, 0, b.size)
            }
        }
        return b
    }

    private fun uslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = b[0].toInt() and 0xFF
        val descEnd = terminator(b, 4, enc)
        val descLe = enc == 1 && b.size >= 6 && (b[4].toInt() and 0xFF) == 0xFF && (b[5].toInt() and 0xFF) == 0xFE
        val textStart = descEnd + termLen(enc)
        if (textStart > b.size) return null
        return decode(b, textStart, b.size, enc, descLe).takeIf { it.isNotBlank() }
    }

    /** SYLT → LRC text (only millisecond timestamps can be converted). */
    private fun sylt(b: ByteArray): String? {
        if (b.size < 7) return null
        val enc = b[0].toInt() and 0xFF
        val format = b[4].toInt() and 0xFF
        if (format != 2) return null
        var pos = terminator(b, 6, enc) + termLen(enc)
        val le = enc == 1 && b.size >= 8 && (b[6].toInt() and 0xFF) == 0xFF && (b[7].toInt() and 0xFF) == 0xFE
        val items = ArrayList<Pair<Long, String>>()
        var guard = 0
        while (pos < b.size && guard++ < 20_000) {
            val tEnd = terminator(b, pos, enc)
            val text = decode(b, pos, tEnd, enc, le)
            val tsAt = tEnd + termLen(enc)
            if (tsAt + 4 > b.size) break
            items.add(u32(b, tsAt) to text)
            pos = tsAt + 4
        }
        if (items.isEmpty()) return null
        val newlineStarts = items.count { it.second.startsWith("\n") || it.second.startsWith("\r") }
        val sb = StringBuilder()
        if (newlineStarts == 0 || newlineStarts == items.size) {
            for ((t, text) in items) {
                sb.append('[').append(LrcParser.formatTimestamp(t)).append(']').append(text.trim()).append('\n')
            }
        } else {
            // Karaoke style: fragments, a leading newline starts a new line.
            var open = false
            for ((t, text) in items) {
                val startsLine = !open || text.startsWith("\n") || text.startsWith("\r")
                if (startsLine) {
                    if (open) sb.append('\n')
                    sb.append('[').append(LrcParser.formatTimestamp(t)).append(']')
                    open = true
                }
                sb.append('<').append(LrcParser.formatTimestamp(t)).append('>').append(text.trimStart('\n', '\r'))
            }
            sb.append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    // endregion

    // region FLAC

    private fun flac(src: Source, offset: Long): String? {
        if (!startsWith(src.read(offset, 4), 0, "fLaC")) return null
        var pos = offset + 4
        var guard = 0
        while (guard++ < 256) {
            val h = src.read(pos, 4)
            if (h.size < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = u24(h, 1)
            if (type == 4 && len in 8..MAX_FRAME.toLong()) {
                return vorbisLyrics(src.read(pos + 4, len.toInt()))
            }
            if (last || type == 127) return null
            pos += 4 + len
        }
        return null
    }

    private fun vorbisLyrics(b: ByteArray): String? {
        if (b.size < 8) return null
        val vendor = le32(b, 0)
        var p = 4L + vendor
        if (p + 4 > b.size) return null
        val count = le32(b, p.toInt())
        p += 4
        var lyrics: String? = null
        var unsynced: String? = null
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
            val key = entry.substring(0, eq)
            val value = entry.substring(eq + 1)
            if (value.isBlank()) continue
            if (key.equals("LYRICS", ignoreCase = true) && lyrics == null) lyrics = value
            else if (key.equals("UNSYNCEDLYRICS", ignoreCase = true) && unsynced == null) unsynced = value
        }
        return lyrics ?: unsynced
    }

    // endregion

    // region MP4

    private class Range(val start: Long, val end: Long)

    private fun mp4(src: Source): String? {
        val moov = findBox(src, Range(0, src.length), "moov") ?: return null
        val udta = findBox(src, moov, "udta")
        val meta = udta?.let { findBox(src, it, "meta") } ?: findBox(src, moov, "meta") ?: return null
        // `meta` is a full box (4 bytes version/flags) in ISO files, but not in some QuickTime files.
        val metaBody = if (latin1(src.read(meta.start + 4, 4), 0, 4) == "hdlr") meta else Range(meta.start + 4, meta.end)
        val ilst = findBox(src, metaBody, "ilst") ?: return null
        val lyr = findBox(src, ilst, LYR) ?: return null
        val data = findBox(src, lyr, "data") ?: return null
        val len = data.end - data.start
        if (len <= 8 || len > MAX_FRAME) return null
        val b = src.read(data.start, len.toInt())
        if (b.size <= 8) return null
        val type = (u32(b, 0) and 0xFFFFFF).toInt()
        return when (type) {
            2 -> String(b, 8, b.size - 8, Charsets.UTF_16BE)
            else -> String(b, 8, b.size - 8, Charsets.UTF_8)
        }.takeIf { it.isNotBlank() }
    }

    /** Finds the first child box named [type] inside [within]; returns its content range. */
    private fun findBox(src: Source, within: Range, type: String): Range? {
        var pos = within.start
        var guard = 0
        while (pos + 8 <= within.end && guard++ < 10_000) {
            val h = src.read(pos, 16)
            val box = parseMp4BoxHeader(h) ?: return null
            val size = if (box.size == 0L) within.end - pos else box.size
            if (size < box.headerSize) return null
            val end = minOf(pos + size, within.end)
            if (box.type == type) return Range(pos + box.headerSize, end)
            pos += size
        }
        return null
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

    private fun clean(s: String): String? {
        val t = s.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim()
        return t.ifBlank { null }
    }

    private fun startsWith(b: ByteArray, at: Int, ascii: String): Boolean {
        if (at < 0 || b.size < at + ascii.length) return false
        for (i in ascii.indices) if (b[at + i].toInt() and 0xFF != ascii[i].code) return false
        return true
    }

    private fun latin1(b: ByteArray, at: Int, len: Int): String {
        if (at < 0 || b.size < at + len) return ""
        return String(b, at, len, Charsets.ISO_8859_1)
    }

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

    private fun deUnsync(b: ByteArray, from: Int, to: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val v = b[i].toInt() and 0xFF
            out.write(v)
            if (v == 0xFF && i + 1 < to && b[i + 1].toInt() == 0) i += 2 else i++
        }
        return out.toByteArray()
    }

    private fun termLen(enc: Int): Int = if (enc == 1 || enc == 2) 2 else 1

    /** Index of the string terminator starting at [from] (or b.size when unterminated). */
    private fun terminator(b: ByteArray, from: Int, enc: Int): Int {
        if (from >= b.size) return b.size
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
                i += 2
            }
            return b.size
        }
        var i = from
        while (i < b.size) {
            if (b[i].toInt() == 0) return i
            i++
        }
        return b.size
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int, fallbackLe: Boolean): String {
        if (from >= to || from < 0) return ""
        val len = to - from
        return when (enc) {
            0 -> String(b, from, len, Charsets.ISO_8859_1)
            1 -> {
                val b0 = b[from].toInt() and 0xFF
                val b1 = if (len > 1) b[from + 1].toInt() and 0xFF else -1
                when {
                    b0 == 0xFF && b1 == 0xFE -> String(b, from + 2, len - 2, Charsets.UTF_16LE)
                    b0 == 0xFE && b1 == 0xFF -> String(b, from + 2, len - 2, Charsets.UTF_16BE)
                    fallbackLe -> String(b, from, len, Charsets.UTF_16LE)
                    else -> String(b, from, len, Charsets.UTF_16BE)
                }
            }
            2 -> String(b, from, len, Charsets.UTF_16BE)
            else -> {
                val bom = len >= 3 && (b[from].toInt() and 0xFF) == 0xEF && (b[from + 1].toInt() and 0xFF) == 0xBB && (b[from + 2].toInt() and 0xFF) == 0xBF
                if (bom) String(b, from + 3, len - 3, Charsets.UTF_8) else String(b, from, len, Charsets.UTF_8)
            }
        }.trimEnd('\u0000')
    }

    // endregion
}
