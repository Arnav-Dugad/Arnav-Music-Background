package com.arnav.music.domain.metadata

import com.arnav.music.domain.lyrics.EmbeddedLyrics

/**
 * Reads credits from the tags of the user's own audio files. Pure byte parsing; never throws.
 *
 * - ID3v2.2/2.3/2.4: TCOM composer, TEXT lyricist, TPUB label, TIPL/IPLS involved people
 *   (producer, engineer, mix…), TMCL musicians, TPE1/TPE2 artist/album artist, TPE3 conductor,
 *   TPE4 remixer, TCOP/TPRO copyright, TSRC ISRC, TCON genre, TYER/TDRC/TDRL date, TXXX custom
 *   fields (PRODUCER, LABEL, ISRC…).
 * - FLAC Vorbis comments: COMPOSER, LYRICIST, WRITER, PRODUCER, ENGINEER, MIXER, ARRANGER,
 *   PERFORMER ("Name (instrument)"), LABEL, PUBLISHER, ORGANIZATION, COPYRIGHT, ISRC, CONDUCTOR,
 *   REMIXER, GENRE, DATE…
 * - MP4/M4A `ilst`: ©wrt, ©gen/gnre, ©day, cprt, aART and iTunes freeform
 *   `----:com.apple.iTunes:<NAME>` atoms (PRODUCER, LABEL, ISRC, LYRICIST…).
 *
 * Like [EmbeddedLyrics] it walks the file through [EmbeddedLyrics.Source], reading only headers
 * and the frames/boxes it needs.
 */
object EmbeddedCredits {

    /** Raw tags in a format-neutral form, before they become [TrackCredits]. */
    class Tags {
        /** Upper-case Vorbis-style keys (COMPOSER, LABEL, ISRC, DATE…) with their values. */
        val fields = ArrayList<Pair<String, String>>()
        /** (role, name) from ID3 TIPL/IPLS. */
        val involved = ArrayList<Pair<String, String>>()
        /** (instrument, name) from ID3 TMCL and Vorbis PERFORMER. */
        val musicians = ArrayList<Pair<String, String>>()

        fun add(key: String, value: String) {
            val v = value.replace("\u0000", "").trim()
            if (v.isNotEmpty()) fields += key.uppercase().trim() to v
        }
    }

    private const val MAX_FRAME = 1024 * 1024
    private const val MAX_TAG_COPY = 16 * 1024 * 1024
    private const val MAX_DATA = 256 * 1024

    fun fromBytes(head: ByteArray): TrackCredits = fromSource(EmbeddedLyrics.ByteArraySource(head))

    fun fromSource(src: EmbeddedLyrics.Source): TrackCredits = build(tagsFrom(src))

    /** Sniffs the format and collects raw tags (empty when unknown or unreadable). */
    fun tagsFrom(src: EmbeddedLyrics.Source): Tags {
        val tags = Tags()
        try {
            val magic = src.read(0, 12)
            when {
                startsWith(magic, 0, "ID3") -> {
                    id3(src, tags)
                    val after = EmbeddedLyrics.id3TotalSize(magic)
                    if (after > 0 && startsWith(src.read(after, 4), 0, "fLaC")) flac(src, after, tags)
                }
                startsWith(magic, 0, "fLaC") -> flac(src, 0, tags)
                EmbeddedLyrics.isMp4(magic) -> mp4(src, tags)
            }
        } catch (e: Exception) {
            // Partial results are still useful.
        } catch (e: OutOfMemoryError) {
            // Ignore.
        }
        return tags
    }

    // region tags → credits

    private val nameSplit = Regex("""\s*(?:;|\u0000|\s/\s)\s*""")

    private fun names(value: String): List<String> = value.split(nameSplit).map { it.trim() }.filter { it.isNotEmpty() }

    private val performerWithInstrument = Regex("""^(.+?)\s*\(([^()]+)\)\s*$""")

    fun build(tags: Tags): TrackCredits {
        val b = CreditsBuilder()
        val f = tags.fields
        fun values(vararg keys: String): List<String> = f.filter { (k, _) -> k in keys }.map { it.second }

        values("ARTIST").flatMap(::names).forEach { b.person(CreditGroup.PERFORMED, "Artist", it) }
        values("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST").flatMap(::names).forEach { b.person(CreditGroup.PERFORMED, "Album artist", it) }
        for ((instrument, name) in tags.musicians) {
            if (instrument.isBlank()) b.person(CreditGroup.PERFORMED, "Performer", name)
            else b.person(instrument, name, lenient = true)
        }
        values("PERFORMER").flatMap(::names).forEach { v ->
            val m = performerWithInstrument.matchEntire(v)
            if (m != null) b.person(m.groupValues[2], m.groupValues[1], lenient = true)
            else b.person(CreditGroup.PERFORMED, "Performer", v)
        }
        values("CONDUCTOR").flatMap(::names).forEach { b.person(CreditGroup.PERFORMED, "Conductor", it) }

        values("COMPOSER").flatMap(::names).forEach { b.person(CreditGroup.WRITTEN, "Composer", it) }
        values("LYRICIST").flatMap(::names).forEach { b.person(CreditGroup.WRITTEN, "Lyricist", it) }
        values("WRITER", "SONGWRITER").flatMap(::names).forEach { b.person(CreditGroup.WRITTEN, "Writer", it) }
        values("ARRANGER").flatMap(::names).forEach { b.person(CreditGroup.WRITTEN, "Arranger", it) }

        values("PRODUCER").flatMap(::names).forEach { b.person(CreditGroup.PRODUCED, "Producer", it) }
        values("EXECUTIVEPRODUCER", "EXECUTIVE PRODUCER").flatMap(::names).forEach { b.person(CreditGroup.PRODUCED, "Executive producer", it) }
        values("REMIXER", "MIXARTIST").flatMap(::names).forEach { b.person(CreditGroup.PRODUCED, "Remixer", it) }

        for ((role, name) in tags.involved) names(name).forEach { b.person(role, it, lenient = true) }
        values("ENGINEER").flatMap(::names).forEach { b.person(CreditGroup.ENGINEERING, "Engineer", it) }
        values("MIXER", "MIXENGINEER", "MIXING ENGINEER", "DJMIXER").flatMap(::names).forEach { b.person(CreditGroup.ENGINEERING, "Mixing engineer", it) }
        values("MASTERING", "MASTERINGENGINEER", "MASTERING ENGINEER").flatMap(::names).forEach { b.person(CreditGroup.ENGINEERING, "Mastering engineer", it) }

        values("LABEL", "ORGANIZATION", "RECORDLABEL").flatMap(::names).forEach { b.fact(CreditGroup.RIGHTS, "Label", it) }
        values("PUBLISHER").flatMap(::names).forEach { b.fact(CreditGroup.RIGHTS, "Publisher", it) }
        values("COPYRIGHT").forEach { b.fact(CreditGroup.RIGHTS, "Copyright", withSymbol(it, "©")) }
        values("PRODUCEDNOTICE").forEach { b.fact(CreditGroup.RIGHTS, "Recording copyright", withSymbol(it, "℗")) }

        values("ALBUM").firstOrNull()?.let { b.fact(CreditGroup.IDENTIFIERS, "Album", it) }
        releaseDate(values("RELEASEDATE", "DATE", "YEAR", "ORIGINALDATE", "ORIGINALYEAR"))?.let { b.fact(CreditGroup.IDENTIFIERS, "Released", it) }
        values("GENRE").flatMap(::names).map(::genreName).filter { it.isNotBlank() }.distinct().take(4).forEach { b.fact(CreditGroup.IDENTIFIERS, "Genre", it) }
        values("ISRC", "TSRC").flatMap(::names).map { it.uppercase() }.distinct().forEach { b.fact(CreditGroup.IDENTIFIERS, "ISRC", it) }
        values("BARCODE", "UPC", "EAN").firstOrNull()?.let { b.fact(CreditGroup.IDENTIFIERS, "UPC", it) }
        values("CATALOGNUMBER", "CATALOG NUMBER", "CATALOG #").firstOrNull()?.let { b.fact(CreditGroup.IDENTIFIERS, "Catalog number", it) }
        return b.build()
    }

    private fun withSymbol(v: String, symbol: String): String {
        val t = v.trim()
        val lower = t.lowercase()
        if (t.startsWith("©") || t.startsWith("℗") || lower.startsWith("(c)") || lower.startsWith("(p)") || lower.startsWith("copyright")) return t
        return "$symbol $t"
    }

    private val isoDate = Regex("""^(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?""")

    /** The most precise date among [candidates] ("2019-05-03" beats "2019"), as yyyy[-MM[-dd]]. */
    fun releaseDate(candidates: List<String>): String? =
        candidates.mapNotNull { isoDate.find(it.trim())?.value }
            .filter { (it.take(4).toIntOrNull() ?: 0) in 1000..2999 }
            .maxByOrNull { it.length }

    private val genreRef = Regex("""^\((\d{1,3})\)(.*)$""")

    /** ID3v1 genre references: "(17)", "17", "(17)Rock" → "Rock". */
    fun genreName(raw: String): String {
        val t = raw.trim()
        genreRef.matchEntire(t)?.let { m ->
            val rest = m.groupValues[2].trim()
            return rest.ifEmpty { ID3V1_GENRES.getOrNull(m.groupValues[1].toInt()) ?: "" }
        }
        if (t.all { it.isDigit() } && t.isNotEmpty()) return ID3V1_GENRES.getOrNull(t.toInt()) ?: ""
        return when (t) {
            "RX" -> "Remix"
            "CR" -> "Cover"
            else -> t
        }
    }

    // endregion

    // region ID3v2

    private val id3Keys = mapOf(
        "TCOM" to "COMPOSER", "TCM" to "COMPOSER",
        "TEXT" to "LYRICIST", "TXT" to "LYRICIST",
        "TPUB" to "LABEL", "TPB" to "LABEL",
        "TPE1" to "ARTIST", "TP1" to "ARTIST",
        "TPE2" to "ALBUMARTIST", "TP2" to "ALBUMARTIST",
        "TPE3" to "CONDUCTOR", "TP3" to "CONDUCTOR",
        "TPE4" to "REMIXER", "TP4" to "REMIXER",
        "TCOP" to "COPYRIGHT", "TCR" to "COPYRIGHT",
        "TPRO" to "PRODUCEDNOTICE",
        "TSRC" to "ISRC", "TRC" to "ISRC",
        "TCON" to "GENRE", "TCO" to "GENRE",
        "TYER" to "YEAR", "TYE" to "YEAR",
        "TDRC" to "DATE", "TDRL" to "RELEASEDATE", "TDOR" to "ORIGINALDATE", "TORY" to "ORIGINALYEAR", "TOR" to "ORIGINALYEAR",
        "TALB" to "ALBUM", "TAL" to "ALBUM",
    )
    private val pairFrames = setOf("TIPL", "IPLS", "IPL", "TMCL")
    private val customFrames = setOf("TXXX", "TXX")

    private fun id3(src: EmbeddedLyrics.Source, tags: Tags) {
        val h = src.read(0, 10)
        if (h.size < 10) return
        val ver = h[3].toInt() and 0xFF
        if (ver !in 2..4) return
        val flags = h[5].toInt() and 0xFF
        val tagEnd = minOf(10L + synchsafe(h, 6), src.length)
        val unsyncTag = (flags and 0x80) != 0
        val extended = (flags and 0x40) != 0

        val source: EmbeddedLyrics.Source
        val start: Long
        val end: Long
        if (unsyncTag && ver < 4) {
            val body = src.read(10, minOf(tagEnd - 10, MAX_TAG_COPY.toLong()).toInt())
            val clear = deUnsync(body, 0, body.size)
            source = EmbeddedLyrics.ByteArraySource(clear); start = 0L; end = clear.size.toLong()
        } else {
            source = src; start = 10L; end = tagEnd
        }

        var pos = start
        if (extended && ver >= 3) {
            val e = source.read(pos, 4)
            if (e.size < 4) return
            pos += if (ver == 4) synchsafe(e, 0) else 4 + u32(e, 0)
        }
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
            val wanted = id in id3Keys || id in pairFrames || id in customFrames
            if (wanted && size in 2..MAX_FRAME.toLong()) {
                val body = frameBody(source.read(pos + hdrLen, size.toInt()), ver, formatFlags)
                if (body != null && body.size >= 2) {
                    val texts = textValues(body)
                    when {
                        id in customFrames -> if (texts.size >= 2) texts.drop(1).forEach { tags.add(texts[0], it) }
                        id == "TMCL" -> pairs(texts).forEach { tags.musicians += it }
                        id in pairFrames -> pairs(texts).forEach { tags.involved += it }
                        else -> {
                            val key = id3Keys.getValue(id)
                            texts.forEach { tags.add(key, it) }
                        }
                    }
                }
            }
            pos += hdrLen + size
        }
    }

    private fun pairs(texts: List<String>): List<Pair<String, String>> =
        texts.chunked(2).filter { it.size == 2 && it[1].isNotBlank() }.map { it[0].trim() to it[1].trim() }

    /** Text frame: encoding byte + one or more terminated strings. */
    private fun textValues(b: ByteArray): List<String> {
        val enc = b[0].toInt() and 0xFF
        val out = ArrayList<String>()
        var pos = 1
        var le = false
        if (enc == 1 && b.size >= 3 && (b[1].toInt() and 0xFF) == 0xFF && (b[2].toInt() and 0xFF) == 0xFE) le = true
        var guard = 0
        while (pos < b.size && guard++ < 256) {
            val t = terminator(b, pos, enc)
            out += decode(b, pos, t, enc, le)
            pos = t + termLen(enc)
        }
        // Trailing empty strings come from padding terminators; empty values in pairs must stay.
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    private fun v24FrameSize(src: EmbeddedLyrics.Source, fh: ByteArray, pos: Long, end: Long): Long {
        val plain = u32(fh, 4)
        val highBits = (fh[4].toInt() or fh[5].toInt() or fh[6].toInt() or fh[7].toInt()) and 0x80
        if (highBits != 0) return plain
        val safe = synchsafe(fh, 4)
        if (safe == plain) return safe
        if (landsOnFrame(src, pos + 10 + safe, end)) return safe
        if (landsOnFrame(src, pos + 10 + plain, end)) return plain
        return safe
    }

    private fun landsOnFrame(src: EmbeddedLyrics.Source, at: Long, end: Long): Boolean {
        if (at == end) return true
        if (at > end) return false
        val b = src.read(at, 4)
        if (b.isEmpty()) return true
        if (b[0].toInt() == 0) return true
        return b.size == 4 && latin1(b, 0, 4).all { it in 'A'..'Z' || it in '0'..'9' }
    }

    private fun frameBody(raw: ByteArray, ver: Int, flags: Int): ByteArray? {
        var b = raw
        when (ver) {
            3 -> {
                if ((flags and 0x80) != 0 || (flags and 0x40) != 0) return null
                if ((flags and 0x20) != 0) b = b.copyOfRange(minOf(1, b.size), b.size)
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

    // endregion

    // region FLAC

    private fun flac(src: EmbeddedLyrics.Source, offset: Long, tags: Tags) {
        if (!startsWith(src.read(offset, 4), 0, "fLaC")) return
        var pos = offset + 4
        var guard = 0
        while (guard++ < 256) {
            val h = src.read(pos, 4)
            if (h.size < 4) return
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = u24(h, 1)
            if (type == 4 && len in 8..MAX_TAG_COPY.toLong()) {
                vorbis(src.read(pos + 4, len.toInt()), tags)
                return
            }
            if (last || type == 127) return
            pos += 4 + len
        }
    }

    /** Parses a Vorbis comment block (also used by Ogg files). */
    fun vorbis(b: ByteArray, tags: Tags) {
        if (b.size < 8) return
        val vendor = le32(b, 0)
        var p = 4L + vendor
        if (p + 4 > b.size) return
        val count = le32(b, p.toInt())
        p += 4
        var i = 0L
        while (i < count && i < 10_000 && p + 4 <= b.size) {
            val len = le32(b, p.toInt())
            p += 4
            if (len < 0 || p + len > b.size) break
            if (len <= MAX_DATA) {
                val entry = String(b, p.toInt(), len.toInt(), Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).uppercase()
                    if (key != "LYRICS" && key != "UNSYNCEDLYRICS" && key != "METADATA_BLOCK_PICTURE" && key != "COVERART") {
                        tags.add(key, entry.substring(eq + 1))
                    }
                }
            }
            p += len
            i++
        }
    }

    // endregion

    // region MP4

    private class Range(val start: Long, val end: Long)

    private val mp4Keys = mapOf(
        "©wrt" to "COMPOSER", "©gen" to "GENRE", "©day" to "DATE", "cprt" to "COPYRIGHT", "aART" to "ALBUMARTIST",
        "©alb" to "ALBUM", "©ART" to "ARTIST", "©con" to "CONDUCTOR",
    )

    private fun mp4(src: EmbeddedLyrics.Source, tags: Tags) {
        val moov = findBox(src, Range(0, src.length), "moov") ?: return
        val udta = findBox(src, moov, "udta")
        val meta = udta?.let { findBox(src, it, "meta") } ?: findBox(src, moov, "meta") ?: return
        val metaBody = if (latin1(src.read(meta.start + 4, 4), 0, 4) == "hdlr") meta else Range(meta.start + 4, meta.end)
        val ilst = findBox(src, metaBody, "ilst") ?: return
        for ((type, range) in children(src, ilst)) {
            when {
                type == "----" -> freeform(src, range, tags)
                type == "gnre" -> dataValues(src, range).firstOrNull()?.let { (kind, bytes) ->
                    if (kind == 0 && bytes.size >= 2) {
                        val index = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                        ID3V1_GENRES.getOrNull(index - 1)?.let { tags.add("GENRE", it) }
                    } else tags.add("GENRE", text(kind, bytes))
                }
                type in mp4Keys -> dataValues(src, range).forEach { (kind, bytes) -> tags.add(mp4Keys.getValue(type), text(kind, bytes)) }
            }
        }
    }

    private fun freeform(src: EmbeddedLyrics.Source, range: Range, tags: Tags) {
        var name: String? = null
        val values = ArrayList<String>()
        for ((type, r) in children(src, range)) {
            val len = r.end - r.start
            if (len < 4 || len > MAX_DATA) continue
            when (type) {
                "name" -> { val b = src.read(r.start, len.toInt()); if (b.size > 4) name = String(b, 4, b.size - 4, Charsets.UTF_8) }
                "data" -> { val b = src.read(r.start, len.toInt()); if (b.size > 8) values += text((u32(b, 0) and 0xFFFFFF).toInt(), b.copyOfRange(8, b.size)) }
            }
        }
        val key = name?.trim()?.uppercase() ?: return
        if (key == "LYRICS") return
        values.forEach { tags.add(key, it) }
    }

    /** (type indicator, value bytes) of each `data` child of an ilst item. */
    private fun dataValues(src: EmbeddedLyrics.Source, item: Range): List<Pair<Int, ByteArray>> =
        children(src, item).filter { it.first == "data" }.mapNotNull { (_, r) ->
            val len = r.end - r.start
            if (len <= 8 || len > MAX_DATA) null
            else {
                val b = src.read(r.start, len.toInt())
                if (b.size <= 8) null else (u32(b, 0) and 0xFFFFFF).toInt() to b.copyOfRange(8, b.size)
            }
        }

    private fun text(kind: Int, bytes: ByteArray): String = when (kind) {
        2 -> String(bytes, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }.trim('\u0000').trim()

    private fun children(src: EmbeddedLyrics.Source, within: Range): List<Pair<String, Range>> {
        val out = ArrayList<Pair<String, Range>>()
        var pos = within.start
        var guard = 0
        while (pos + 8 <= within.end && guard++ < 2_000) {
            val box = EmbeddedLyrics.parseMp4BoxHeader(src.read(pos, 16)) ?: break
            val size = if (box.size == 0L) within.end - pos else box.size
            if (size < box.headerSize) break
            out += box.type to Range(pos + box.headerSize, minOf(pos + size, within.end))
            pos += size
        }
        return out
    }

    private fun findBox(src: EmbeddedLyrics.Source, within: Range, type: String): Range? {
        var pos = within.start
        var guard = 0
        while (pos + 8 <= within.end && guard++ < 10_000) {
            val box = EmbeddedLyrics.parseMp4BoxHeader(src.read(pos, 16)) ?: return null
            val size = if (box.size == 0L) within.end - pos else box.size
            if (size < box.headerSize) return null
            if (box.type == type) return Range(pos + box.headerSize, minOf(pos + size, within.end))
            pos += size
        }
        return null
    }

    // endregion

    // region bytes

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

    /** The 80 original ID3v1 genres (Winamp extensions are rarely seen as numbers). */
    val ID3V1_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )
}
