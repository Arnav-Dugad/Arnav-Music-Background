package com.arnav.music.domain

import com.arnav.music.domain.lyrics.TranscriptionAudio
import com.arnav.music.domain.lyrics.TranscriptionCheck
import com.arnav.music.domain.lyrics.TranscriptionVerdict
import com.arnav.music.domain.lyrics.VocalActivityMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AiLyricsTest {

    private val goodLrc = """
        [00:12.40] I walked along the empty road tonight
        [00:17.10] The city lights were fading out
        [00:21.80] Nobody waiting by the door

        [00:31.00] Hold on, hold on
        [00:34.20] We're burning like the sun
    """.trimIndent()

    private fun accepted(v: TranscriptionVerdict): TranscriptionVerdict.Accepted {
        assertTrue("expected accepted, got $v", v is TranscriptionVerdict.Accepted)
        return v as TranscriptionVerdict.Accepted
    }

    private fun rejected(v: TranscriptionVerdict) = assertTrue("expected rejected, got $v", v is TranscriptionVerdict.Rejected)

    // region transcription check

    @Test fun `good LRC is accepted as synced`() {
        val v = accepted(TranscriptionCheck.check(goodLrc, 200_000))
        assertTrue(v.synced)
        assertEquals(5, v.lineCount)
        assertTrue(v.text.startsWith("[00:12.40]"))
        assertTrue("stanza gap kept", v.text.contains("\n\n[00:31.00]"))
    }

    @Test fun `refusals are rejected`() {
        rejected(TranscriptionCheck.check("I can't transcribe the lyrics of this song because it is protected by copyright.", 200_000))
        rejected(TranscriptionCheck.check("I'm sorry, but I can't provide the lyrics for this song.", 200_000))
        rejected(TranscriptionCheck.check("As an AI, I am unable to listen to audio files.\nPlease try another song.\nThanks", 200_000))
        rejected(TranscriptionCheck.check("Sorry, I cannot help with transcribing lyrics.\n[00:01.00] la", 200_000))
    }

    @Test fun `descriptions of the music are rejected`() {
        rejected(
            TranscriptionCheck.check(
                "This song is an upbeat pop track featuring a female vocalist singing about love and loss over synths.\n" +
                    "The instrumentation includes drums, bass and a bright piano that carries the melody throughout the track.\n" +
                    "Overall the mood is hopeful and energetic.",
                200_000,
            ),
        )
    }

    @Test fun `no vocals sentinel`() {
        assertEquals(TranscriptionVerdict.NoVocals, TranscriptionCheck.check("NO_VOCALS", 200_000))
        assertEquals(TranscriptionVerdict.NoVocals, TranscriptionCheck.check("```\nNO_VOCALS\n```", 200_000))
    }

    @Test fun `fences, preface, labels and instrumental markers are cleaned`() {
        val raw = "Here are the lyrics:\n```lrc\n[00:05.00] [Verse 1]\n[00:05.00] First line here\n[00:09.00] Second line here\n" +
            "[00:14.00] [instrumental]\n\n[00:30.00] Third line here\n[00:35.00] Fourth line\n```\nNote: some words may be unclear."
        val v = accepted(TranscriptionCheck.check(raw, 120_000))
        assertTrue(v.synced)
        assertEquals(4, v.lineCount)
        assertFalse(v.text.contains("Verse"))
        assertFalse(v.text.contains("Here are"))
        assertFalse(v.text.contains("Note"))
        assertFalse(v.text.contains("```"))
        assertTrue(v.text.contains("[00:14.00]\n"))
    }

    @Test fun `unusable timestamps fall back to plain lyrics`() {
        val same = (1..6).joinToString("\n") { "[00:00.00] Line number $it of the song" }
        val v = accepted(TranscriptionCheck.check(same, 200_000))
        assertFalse(v.synced)
        assertEquals("Line number 1 of the song", v.text.lines().first())
    }

    @Test fun `timeline far past the song end is rejected`() {
        val late = (1..8).joinToString("\n") { "[0${3 + it / 3}:${10 + it}.00] Late line $it" }
        rejected(TranscriptionCheck.check(late, 120_000))
    }

    @Test fun `looping output is rejected`() {
        rejected(TranscriptionCheck.check((0 until 30).joinToString("\n") { "[00:${10 + it}.00] la la la" }, 200_000))
    }

    @Test fun `plain lyrics are accepted unsynced, even when a line apologises`() {
        val v = accepted(TranscriptionCheck.check("I'm sorry for the things I said\nI didn't mean to make you cry\n\nCome back to me tonight", 200_000))
        assertFalse(v.synced)
        assertEquals(3, v.lineCount)
        assertEquals(listOf("I'm sorry for the things I said", "I didn't mean to make you cry", "", "Come back to me tonight"), v.text.lines())
    }

    @Test fun `too little text is rejected`() {
        rejected(TranscriptionCheck.check("[00:10.00] Oh", 200_000))
        rejected(TranscriptionCheck.check("", 200_000))
    }

    // endregion

    // region audio packaging

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(p)
            is ByteArray -> out.write(p)
        }
        while (out.size() < 64) out.write(0)
        return out.toByteArray()
    }

    @Test fun `sniffs supported containers`() {
        assertEquals("audio/mpeg", TranscriptionAudio.sniffMime(bytes("ID3", 4, 0, 0, 0, 0, 0, 0)))
        assertEquals("audio/mpeg", TranscriptionAudio.sniffMime(bytes(0xFF, 0xFB, 0x90, 0x00)))
        assertEquals("audio/aac", TranscriptionAudio.sniffMime(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals("audio/flac", TranscriptionAudio.sniffMime(bytes("fLaC")))
        assertEquals("audio/ogg", TranscriptionAudio.sniffMime(bytes("OggS")))
        assertEquals("audio/wav", TranscriptionAudio.sniffMime(bytes("RIFF", 0, 0, 0, 0, "WAVE")))
        assertEquals("audio/mp4", TranscriptionAudio.sniffMime(bytes(0, 0, 0, 0x20, "ftypM4A ")))
        // ID3 in front of FLAC.
        assertEquals("audio/flac", TranscriptionAudio.sniffMime(bytes("ID3", 4, 0, 0, 0, 0, 0, 2, "xx", "fLaC")))
        assertNull(TranscriptionAudio.sniffMime(bytes(0x30, 0x26, 0xB2, 0x75))) // WMA/ASF
        assertNull(TranscriptionAudio.sniffMime(ByteArray(4)))
    }

    @Test fun `wav encoder writes a valid header`() {
        val wav = TranscriptionAudio.wav16(floatArrayOf(0f, 1f, -1f, 0.5f), 3, 16_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.ISO_8859_1))
        assertEquals("data", String(wav, 36, 4, Charsets.ISO_8859_1))
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16)
        assertEquals(16_000, rate)
        assertEquals(6, wav[40].toInt())
        assertEquals(32767, (wav[46].toInt() and 0xFF) or (wav[47].toInt() shl 8))
        assertEquals(-32767, (wav[48].toInt() and 0xFF) or (wav[49].toInt() shl 8))
    }

    // endregion

    // region vocal activity

    @Test fun `centred syllabic voice band reads as vocals, wide or bass-heavy music doesn't`() {
        val rate = 11_025
        val meter = VocalActivityMeter(rate)
        // 10 s of "band": a hard-panned 1 kHz part (side) and a centred 70 Hz bass, steady.
        for (i in 0 until rate * 10) {
            val t = i.toDouble() / rate
            val tone = 0.3 * sin(2 * PI * 1_000 * t)
            val bass = 0.4 * sin(2 * PI * 70 * t)
            val l = tone + bass
            val r = bass
            meter.push(((l + r) / 2).toFloat(), ((l - r) / 2).toFloat())
        }
        // 10 s of the same band plus a centred, syllabic (5 Hz gated) 600 Hz voice.
        for (i in 0 until rate * 10) {
            val t = i.toDouble() / rate
            val tone = 0.3 * sin(2 * PI * 1_000 * t)
            val bass = 0.4 * sin(2 * PI * 70 * t)
            val gate = if ((t * 5).toInt() % 2 == 0) 1.0 else 0.15
            val voice = 0.5 * gate * (sin(2 * PI * 600 * t) + 0.4 * sin(2 * PI * 1_200 * t))
            val l = tone + bass + voice
            val r = bass + voice
            meter.push(((l + r) / 2).toFloat(), ((l - r) / 2).toFloat())
        }
        val curve = meter.finish()
        assertEquals(40, curve.size)
        val band = curve.copyOfRange(2, 19).average()
        val vocal = curve.copyOfRange(22, 39).average()
        assertTrue("band $band vocal $vocal", vocal > 0.7)
        assertTrue("band $band vocal $vocal", band < 0.3)
        // Storage round trip.
        val back = VocalActivityMeter.fromBytes(VocalActivityMeter.toBytes(curve))
        for (i in curve.indices) assertEquals(curve[i], back[i], 1f / 255f)
    }

    @Test fun `silence reads as no vocals`() {
        val meter = VocalActivityMeter(8_000)
        repeat(8_000 * 3) { meter.push(0f, 0f) }
        assertTrue(meter.finish().all { it == 0f })
    }

    // endregion
}
