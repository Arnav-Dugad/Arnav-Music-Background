package com.arnav.music.domain.lyrics

import kotlin.math.roundToInt

/**
 * Audio packaging for AI lyrics transcription: which song files can be sent as they are, and a
 * small 16-bit mono WAV encoder for everything else (decoded on the device first).
 */
object TranscriptionAudio {
    /**
     * Largest file sent inline. Gemini (via Firebase AI Logic) accepts requests up to 20 MB and inline
     * data travels base64-encoded (4/3 larger), so ~14 MB of audio is the safe ceiling.
     */
    const val MAX_INLINE_BYTES = 14 * 1024 * 1024

    /** Longest stretch sent: songs longer than this are decoded and cut (WAV, ≈ 16 kHz mono). */
    const val MAX_SECONDS = 7 * 60

    /** Sample rate asked of the decoder for WAV uploads (speech-grade; Gemini downsamples anyway). */
    const val WAV_RATE = 16_000

    /**
     * MIME type of an audio file from its first bytes, for the containers Gemini reads: MP3
     * (`audio/mpeg`), AAC in MP4/M4A (`audio/mp4`), raw ADTS AAC (`audio/aac`), FLAC, Ogg (Vorbis/Opus)
     * and WAV. Null for anything else (WMA, AIFF, Matroska…), which is then decoded to WAV instead.
     */
    fun sniffMime(head: ByteArray): String? {
        if (head.size < 12) return null
        var off = 0
        // ID3v2 tag in front (MP3, sometimes AAC/FLAC): skip it when the bytes after it are here.
        if (ascii(head, 0, "ID3") && head.size >= 10) {
            val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
            off = 10 + size + footer
            if (off + 4 > head.size) return "audio/mpeg"
        }
        return when {
            ascii(head, off, "fLaC") -> "audio/flac"
            ascii(head, off, "OggS") -> "audio/ogg"
            ascii(head, off, "RIFF") && head.size >= off + 12 && ascii(head, off + 8, "WAVE") -> "audio/wav"
            head.size >= off + 8 && ascii(head, off + 4, "ftyp") -> {
                val brand = String(head, off + 8, minOf(4, head.size - off - 8), Charsets.ISO_8859_1)
                // Video-only/HEIF brands are not songs; everything else (M4A, M4B, mp42, isom, dash…) is.
                if (brand.startsWith("hei") || brand.startsWith("avif") || brand.startsWith("qt")) null else "audio/mp4"
            }
            (head[off].toInt() and 0xFF) == 0xFF && (head[off + 1].toInt() and 0xE0) == 0xE0 -> {
                val layer = (head[off + 1].toInt() shr 1) and 0x03
                if (layer == 0) "audio/aac" else "audio/mpeg"
            }
            off > 0 -> "audio/mpeg"
            else -> null
        }
    }

    /** 16-bit PCM mono WAV of the first [length] samples (in [-1, 1]) at [sampleRate]. */
    fun wav16(samples: FloatArray, length: Int, sampleRate: Int): ByteArray {
        val n = length.coerceIn(0, samples.size)
        val dataBytes = n * 2
        val out = ByteArray(44 + dataBytes)
        fun str(at: Int, s: String) { for (i in s.indices) out[at + i] = s[i].code.toByte() }
        fun le32(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte(); out[at + 2] = (v shr 16).toByte(); out[at + 3] = (v shr 24).toByte() }
        fun le16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); le32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1)
        le32(24, sampleRate); le32(28, sampleRate * 2); le16(32, 2); le16(34, 16)
        str(36, "data"); le32(40, dataBytes)
        var p = 44
        for (i in 0 until n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            out[p] = v.toByte()
            out[p + 1] = (v shr 8).toByte()
            p += 2
        }
        return out
    }

    private fun ascii(b: ByteArray, at: Int, s: String): Boolean {
        if (at < 0 || at + s.length > b.size) return false
        for (i in s.indices) if (b[at + i] != s[i].code.toByte()) return false
        return true
    }
}
