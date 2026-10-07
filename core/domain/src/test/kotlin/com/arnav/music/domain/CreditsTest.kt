package com.arnav.music.domain

import com.arnav.music.domain.metadata.CreditEntry
import com.arnav.music.domain.metadata.CreditGroup
import com.arnav.music.domain.metadata.CreditRoles
import com.arnav.music.domain.metadata.DescriptionCredits
import com.arnav.music.domain.metadata.EmbeddedCredits
import com.arnav.music.domain.metadata.TrackCredits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CreditsTest {

    // region byte builders

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

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun be24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun synchsafe(v: Int) = byteArrayOf(((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun id3Frame(version: Int, id: String, body: ByteArray): ByteArray = when (version) {
        2 -> bytes(id, be24(body.size), body)
        4 -> bytes(id, synchsafe(body.size), 0, 0, body)
        else -> bytes(id, be32(body.size), 0, 0, body)
    }

    private fun id3Tag(version: Int, vararg frames: ByteArray, padding: Int = 64): ByteArray {
        val body = bytes(*frames, ByteArray(padding))
        return bytes("ID3", version, 0, 0, synchsafe(body.size), body)
    }

    /** UTF-8 text frame body (v2.4) with null-separated values. */
    private fun text8(vararg values: String) = bytes(3, utf8(values.joinToString("\u0000")))
    private fun latin(vararg values: String) = bytes(0, values.joinToString("\u0000"))

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = bytes(*content)
        return bytes(be32(8 + body.size), type, body)
    }

    private fun dataBox(text: String) = box("data", be32(1), be32(0), utf8(text))
    private fun freeform(name: String, value: String) = box(
        "----",
        box("mean", be32(0), utf8("com.apple.iTunes")),
        box("name", be32(0), utf8(name)),
        dataBox(value),
    )

    private fun vorbisFlac(vararg comments: String): ByteArray {
        fun entry(s: String): ByteArray { val b = utf8(s); return bytes(le32(b.size), b) }
        val vendor = utf8("reference libFLAC")
        val block = bytes(le32(vendor.size), vendor, le32(comments.size), *comments.map(::entry).toTypedArray())
        return bytes(
            "fLaC",
            0x00, be24(34), ByteArray(34),
            0x06, be24(20), ByteArray(20), // PICTURE, skipped
            0x84, be24(block.size), block,
            ByteArray(16) { 0x11 },
        )
    }

    private fun TrackCredits.names(group: CreditGroup): List<String> = entries.filter { it.group == group }.map { it.name }
    private fun TrackCredits.role(name: String): String? = entries.firstOrNull { it.name == name }?.role
    private fun TrackCredits.fact(role: String): String? = entries.firstOrNull { !it.person && it.role == role }?.name

    // endregion

    // region roles

    @Test fun `roles map to groups`() {
        fun one(role: String, lenient: Boolean = false) = CreditRoles.classify(role, lenient).map { it.group to it.role }
        assertEquals(listOf(CreditGroup.WRITTEN to "Composer"), one("Composer"))
        assertEquals(listOf(CreditGroup.WRITTEN to "Composer, Lyricist"), one("Composer, Lyricist"))
        assertEquals(listOf(CreditGroup.WRITTEN to "Music director"), one("Music Director"))
        assertEquals(listOf(CreditGroup.WRITTEN to "Lyricist"), one("Lyrics"))
        assertEquals(listOf(CreditGroup.ENGINEERING to "Mixing engineer"), one("Studio  Personnel, Mixing  Engineer"))
        assertEquals(listOf(CreditGroup.ENGINEERING to "Mixing engineer, Mastering engineer"), one("Mixed & Mastered"))
        assertEquals(listOf(CreditGroup.PRODUCED to "Producer", CreditGroup.ENGINEERING to "Mixing engineer"), one("Producer, Mixer"))
        assertEquals(listOf(CreditGroup.PERFORMED to "Vocals"), one("Associated Performer, Vocals"))
        assertEquals(listOf(CreditGroup.PERFORMED to "Background vocal"), one("Background Vocal"))
        assertEquals(listOf(CreditGroup.PERFORMED to "Guitar"), one("guitar"))
        assertEquals(listOf(CreditGroup.RIGHTS to "Label"), one("Music Label"))
        assertEquals(emptyList<Any>(), one("Instagram"))
        assertEquals(emptyList<Any>(), one("Director"))
        assertEquals(emptyList<Any>(), one("Lyric Video"))
        assertEquals(emptyList<Any>(), one("Cinematography"))
        // Unknown roles only count on Topic pages / in tags.
        assertEquals(emptyList<Any>(), one("Special guest list"))
        assertEquals(listOf(CreditGroup.PERFORMED to "Theremin"), one("Theremin", lenient = true))
        assertEquals(emptyList<Any>(), one("Theremin"))
    }

    // endregion

    // region ID3

    @Test fun `id3v24 text frames, tipl and tmcl`() {
        val tag = id3Tag(
            4,
            id3Frame(4, "TIT2", text8("Song")),
            id3Frame(4, "TPE1", text8("Main Act")),
            id3Frame(4, "TPE2", text8("Album Act")),
            id3Frame(4, "TCOM", text8("Ann Writer", "Bob Writer")),
            id3Frame(4, "TEXT", text8("Lyn Lyricist")),
            id3Frame(4, "TPUB", text8("Big Label")),
            id3Frame(4, "TIPL", text8("producer", "Pat Producer", "engineer", "Ed Engineer", "mix", "Max Mixer", "DJ-mix", "", "arranger", "Arr Anger")),
            id3Frame(4, "TMCL", text8("guitar", "Gil Guitar", "drums", "Dee Drums")),
            id3Frame(4, "TPE3", text8("Connie Conductor")),
            id3Frame(4, "TPE4", text8("Remy Remixer")),
            id3Frame(4, "TCOP", text8("2019 Big Label Ltd")),
            id3Frame(4, "TSRC", text8("usrc17607839")),
            id3Frame(4, "TCON", text8("(17)")),
            id3Frame(4, "TDRC", text8("2019-05-03")),
            id3Frame(4, "TXXX", text8("PRODUCER", "Co Pro")),
            id3Frame(4, "APIC", bytes(0, "image/png", 0, 3, 0, ByteArray(500))),
        )
        val c = EmbeddedCredits.fromBytes(bytes(tag, ByteArray(300) { 0x55 }))
        assertEquals(listOf("Main Act", "Album Act", "Gil Guitar", "Dee Drums", "Connie Conductor"), c.names(CreditGroup.PERFORMED))
        assertEquals("Guitar", c.role("Gil Guitar"))
        assertEquals("Album artist", c.role("Album Act"))
        assertEquals(listOf("Ann Writer", "Bob Writer", "Lyn Lyricist", "Arr Anger"), c.names(CreditGroup.WRITTEN))
        assertEquals("Lyricist", c.role("Lyn Lyricist"))
        assertEquals(listOf("Co Pro", "Remy Remixer", "Pat Producer"), c.names(CreditGroup.PRODUCED))
        assertEquals("Remixer", c.role("Remy Remixer"))
        assertEquals(listOf("Ed Engineer", "Max Mixer"), c.names(CreditGroup.ENGINEERING))
        assertEquals("Mixing engineer", c.role("Max Mixer"))
        assertEquals("Big Label", c.fact("Label"))
        assertEquals("© 2019 Big Label Ltd", c.fact("Copyright"))
        assertEquals("USRC17607839", c.fact("ISRC"))
        assertEquals("Rock", c.fact("Genre"))
        assertEquals("2019-05-03", c.fact("Released"))
        assertEquals(
            listOf(CreditGroup.PERFORMED, CreditGroup.WRITTEN, CreditGroup.PRODUCED, CreditGroup.ENGINEERING, CreditGroup.RIGHTS, CreditGroup.IDENTIFIERS),
            c.grouped().map { it.first },
        )
    }

    @Test fun `id3v23 latin1 and utf16 frames with ipls and tyer`() {
        val utf16 = bytes(1, 0xFF, 0xFE, "Zoë Composer".toByteArray(Charsets.UTF_16LE), 0, 0)
        val ipls = bytes(0, "Producer", 0, "Pat Producer", 0, "Mastering Engineer", 0, "Molly Master", 0)
        val tag = id3Tag(
            3,
            id3Frame(3, "TCOM", utf16),
            id3Frame(3, "IPLS", ipls),
            id3Frame(3, "TYER", latin("1999")),
            id3Frame(3, "TCON", latin("(13)Pop")),
            id3Frame(3, "TXXX", bytes(0, "LABEL", 0, "Indie Records")),
            id3Frame(3, "TXXX", bytes(0, "ISRC", 0, "GBAYE9900001")),
        )
        val c = EmbeddedCredits.fromBytes(tag)
        assertEquals(listOf("Zoë Composer"), c.names(CreditGroup.WRITTEN))
        assertEquals(listOf("Pat Producer"), c.names(CreditGroup.PRODUCED))
        assertEquals("Mastering engineer", c.role("Molly Master"))
        assertEquals("1999", c.fact("Released"))
        assertEquals("Pop", c.fact("Genre"))
        assertEquals("Indie Records", c.fact("Label"))
        assertEquals("GBAYE9900001", c.fact("ISRC"))
    }

    @Test fun `id3v22 three letter frames`() {
        val tag = id3Tag(2, id3Frame(2, "TCM", latin("Old Composer")), id3Frame(2, "TP3", latin("Old Conductor")), id3Frame(2, "TYE", latin("1984")))
        val c = EmbeddedCredits.fromBytes(tag)
        assertEquals(listOf("Old Composer"), c.names(CreditGroup.WRITTEN))
        assertEquals("Conductor", c.role("Old Conductor"))
        assertEquals("1984", c.fact("Released"))
    }

    @Test fun `file without credit tags is empty`() {
        assertTrue(EmbeddedCredits.fromBytes(id3Tag(4, id3Frame(4, "TIT2", text8("Only a title")))).isEmpty)
        assertTrue(EmbeddedCredits.fromBytes(ByteArray(64) { 7 }).isEmpty)
        assertTrue(EmbeddedCredits.fromBytes(ByteArray(0)).isEmpty)
    }

    // endregion

    // region FLAC

    @Test fun `flac vorbis comments`() {
        val file = vorbisFlac(
            "TITLE=Song", "ARTIST=Main Act", "COMPOSER=Ann Writer", "LYRICIST=Lyn Lyricist", "WRITER=Wren Writer",
            "PRODUCER=Pat Producer; Second Producer", "ENGINEER=Ed Engineer", "MIXER=Max Mixer", "ARRANGER=Arr Anger",
            "PERFORMER=Gil Guitar (guitar)", "PERFORMER=Plain Performer", "LABEL=Big Label", "PUBLISHER=Pub House",
            "ORGANIZATION=Org Label", "COPYRIGHT=℗ 2020 Big Label", "ISRC=USRC17607839", "CONDUCTOR=Connie Conductor",
            "REMIXER=Remy Remixer", "GENRE=Electronic", "DATE=2020-02-14", "LYRICS=not a credit",
        )
        val c = EmbeddedCredits.fromBytes(file)
        assertEquals(listOf("Main Act", "Gil Guitar", "Plain Performer", "Connie Conductor"), c.names(CreditGroup.PERFORMED))
        assertEquals("Guitar", c.role("Gil Guitar"))
        assertEquals(listOf("Ann Writer", "Lyn Lyricist", "Wren Writer", "Arr Anger"), c.names(CreditGroup.WRITTEN))
        assertEquals(listOf("Pat Producer", "Second Producer", "Remy Remixer"), c.names(CreditGroup.PRODUCED))
        assertEquals(listOf("Ed Engineer", "Max Mixer"), c.names(CreditGroup.ENGINEERING))
        assertEquals(listOf("Big Label", "Org Label", "Pub House", "℗ 2020 Big Label"), c.names(CreditGroup.RIGHTS))
        assertEquals("2020-02-14", c.fact("Released"))
        assertEquals("Electronic", c.fact("Genre"))
        assertFalse(c.entries.any { it.name == "not a credit" })
    }

    @Test fun `flac behind an id3 prefix`() {
        val prefix = id3Tag(3, id3Frame(3, "TIT2", latin("x")))
        val c = EmbeddedCredits.fromBytes(bytes(prefix, vorbisFlac("COMPOSER=Ann Writer")))
        assertEquals(listOf("Ann Writer"), c.names(CreditGroup.WRITTEN))
    }

    // endregion

    // region MP4

    @Test fun `mp4 ilst atoms and itunes freeform`() {
        val ilst = box(
            "ilst",
            box("©nam", dataBox("Song")),
            box("©wrt", dataBox("Ann Writer")),
            box("©gen", dataBox("Jazz")),
            box("©day", dataBox("2018-11-30T08:00:00Z")),
            box("cprt", dataBox("© 2018 Blue Note")),
            box("aART", dataBox("Album Act")),
            box("covr", box("data", be32(13), be32(0), ByteArray(2_000))),
            freeform("PRODUCER", "Pat Producer"),
            freeform("LABEL", "Blue Note"),
            freeform("ISRC", "USBN11800001"),
            freeform("LYRICIST", "Lyn Lyricist"),
            freeform("ENGINEER", "Ed Engineer"),
        )
        val meta = box("meta", be32(0), box("hdlr", ByteArray(25)), ilst)
        val file = bytes(
            box("ftyp", "M4A ".toByteArray(), be32(0)),
            box("mdat", ByteArray(3_000)),
            box("moov", box("mvhd", ByteArray(100)), box("udta", meta)),
        )
        val c = EmbeddedCredits.fromBytes(file)
        assertEquals(listOf("Album Act"), c.names(CreditGroup.PERFORMED))
        assertEquals(listOf("Ann Writer", "Lyn Lyricist"), c.names(CreditGroup.WRITTEN))
        assertEquals(listOf("Pat Producer"), c.names(CreditGroup.PRODUCED))
        assertEquals(listOf("Ed Engineer"), c.names(CreditGroup.ENGINEERING))
        assertEquals("Blue Note", c.fact("Label"))
        assertEquals("© 2018 Blue Note", c.fact("Copyright"))
        assertEquals("USBN11800001", c.fact("ISRC"))
        assertEquals("Jazz", c.fact("Genre"))
        assertEquals("2018-11-30", c.fact("Released"))
    }

    @Test fun `mp4 numeric gnre`() {
        val ilst = box("ilst", box("gnre", box("data", be32(0), be32(0), byteArrayOf(0, 18))))
        val meta = box("meta", be32(0), box("hdlr", ByteArray(25)), ilst)
        val file = bytes(box("ftyp", "M4A ".toByteArray(), be32(0)), box("moov", box("udta", meta)))
        assertEquals("Rock", EmbeddedCredits.fromBytes(file).fact("Genre"))
    }

    // endregion

    // region descriptions

    private val topic = """
        Provided to YouTube by Universal Music Group

        Get Lucky · Daft Punk · Pharrell Williams · Nile Rodgers

        Random Access Memories

        ℗ 2013 Daft Life Limited under exclusive license to Columbia Records

        Released on: 2013-05-17

        Producer: Thomas Bangalter
        Producer: Guy-Manuel de Homem-Christo
        Studio  Personnel, Mixing  Engineer: Mick Guzauski
        Associated  Performer, Guitar: Nile Rodgers
        Composer  Lyricist: Pharrell Williams
        Composer, Lyricist: Thomas Bangalter
        Music  Publisher: Imagem Music
        Auto-generated by YouTube.
    """.trimIndent()

    @Test fun `topic description`() {
        assertTrue(DescriptionCredits.isTopic(topic))
        val c = DescriptionCredits.parse(topic)
        assertEquals(listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"), c.names(CreditGroup.PERFORMED))
        assertEquals("Artist", c.role("Daft Punk"))
        assertEquals("Featured artist, Guitar", c.role("Nile Rodgers"))
        assertEquals(listOf("Pharrell Williams", "Thomas Bangalter"), c.names(CreditGroup.WRITTEN))
        assertEquals("Composer, Lyricist", c.entries.first { it.group == CreditGroup.WRITTEN && it.name == "Thomas Bangalter" }.role)
        assertEquals(listOf("Thomas Bangalter", "Guy-Manuel de Homem-Christo"), c.names(CreditGroup.PRODUCED))
        assertEquals(listOf("Mick Guzauski"), c.names(CreditGroup.ENGINEERING))
        assertEquals(
            listOf("Universal Music Group", "Imagem Music", "℗ 2013 Daft Life Limited under exclusive license to Columbia Records"),
            c.names(CreditGroup.RIGHTS),
        )
        assertEquals("Random Access Memories", c.fact("Album"))
        assertEquals("2013-05-17", c.fact("Released"))
        assertFalse(c.entries.any { it.name.contains("Auto-generated") })
    }

    @Test fun `topic title with a colon is not a credit`() {
        val d = "Provided to YouTube by XL\n\nInterlude: Rain · Some Band\n\nAlbum: Deluxe\n\nReleased on: 2001\n\nAuto-generated by YouTube."
        val c = DescriptionCredits.parse(d)
        assertEquals(listOf("Some Band"), c.names(CreditGroup.PERFORMED))
        assertEquals("2001", c.fact("Released"))
    }

    @Test fun `free form description keeps only music roles`() {
        val d = """
            Watch the official video of Kesariya!
            Subscribe: https://youtube.com/x
            Instagram: @someone

            Song: Kesariya
            Singer: Arijit Singh
            Music: Pritam
            Lyrics: Amitabh Bhattacharya
            Mixed & Mastered by: Eric Pillai
            Music Label: Sony Music India
            Director: Ayan Mukerji
            Cast: Ranbir Kapoor, Alia Bhatt
            0:00 Intro
            1:30 Chorus
            © 2022 Sony Music Entertainment India Pvt. Ltd.
        """.trimIndent()
        assertFalse(DescriptionCredits.isTopic(d))
        val c = DescriptionCredits.parse(d)
        assertEquals(listOf("Arijit Singh"), c.names(CreditGroup.PERFORMED))
        assertEquals("Vocals", c.role("Arijit Singh"))
        assertEquals(listOf("Pritam", "Amitabh Bhattacharya"), c.names(CreditGroup.WRITTEN))
        assertEquals("Composer", c.role("Pritam"))
        assertEquals(listOf("Eric Pillai"), c.names(CreditGroup.ENGINEERING))
        assertEquals("Mixing engineer, Mastering engineer", c.role("Eric Pillai"))
        assertEquals(listOf("Sony Music India", "© 2022 Sony Music Entertainment India Pvt. Ltd."), c.names(CreditGroup.RIGHTS))
        assertFalse(c.entries.any { it.name.contains("Ranbir") || it.name.contains("Ayan") || it.name.contains("@") })
    }

    @Test fun `free form names are split and by lines understood`() {
        val c = DescriptionCredits.parse("Singers: A One, B Two & C Three\nMusic by D Four\nProduced by: E Five and F Six")
        assertEquals(listOf("A One", "B Two", "C Three"), c.names(CreditGroup.PERFORMED))
        assertEquals(listOf("D Four"), c.names(CreditGroup.WRITTEN))
        assertEquals(listOf("E Five", "F Six"), c.names(CreditGroup.PRODUCED))
    }

    @Test fun `empty or credit-less descriptions`() {
        assertTrue(DescriptionCredits.parse("").isEmpty)
        assertTrue(DescriptionCredits.parse("Thanks for watching!\nLike and subscribe.\nhttps://x.y").isEmpty)
    }

    @Test fun `merge keeps first and adds new`() {
        val a = TrackCredits(listOf(CreditEntry(CreditGroup.WRITTEN, "Composer", "Ann", true)))
        val b = TrackCredits(listOf(CreditEntry(CreditGroup.WRITTEN, "Lyricist", "ann", true), CreditEntry(CreditGroup.RIGHTS, "Label", "L", false)))
        val m = a.merge(b)
        assertEquals(2, m.entries.size)
        assertEquals("Composer, Lyricist", m.entries[0].role)
    }

    // endregion
}
