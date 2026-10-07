package com.arnav.music.domain

import com.arnav.music.domain.audio.SectionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TrackSectionsTest {
    private val sr = 11_025
    private val rnd = Random(11)

    /** Builds a signal from (seconds, amplitude-at-start, amplitude-at-end) noise segments. */
    private fun signal(vararg parts: Triple<Double, Double, Double>): FloatArray {
        val total = parts.sumOf { (it.first * sr).toInt() }
        val out = FloatArray(total)
        var at = 0
        for ((s, a0, a1) in parts) {
            val len = (s * sr).toInt()
            for (i in 0 until len) {
                val amp = a0 + (a1 - a0) * i / len
                out[at + i] = ((rnd.nextFloat() * 2 - 1) * amp).toFloat()
            }
            at += len
        }
        return out
    }

    private fun seg(s: Double, a: Double) = Triple(s, a, a)

    @Test fun `silence then quiet intro then music then fade`() {
        // 3 s silence, 2 s at −30 dB, 30 s music, 5 s linear fade, 2 s silence.
        val x = signal(seg(3.0, 0.0), seg(2.0, 0.01), seg(30.0, 0.3), Triple(5.0, 0.3, 0.0), seg(2.0, 0.0))
        val s = SectionDetector.detect(x, sr)
        assertEquals(5_000.0, s.introMs.toDouble(), 100.0)
        // −10 dB on a linear amplitude fade is reached at 68 % of it: 35 s + 3.4 s.
        assertEquals(38_420.0, s.outroMs.toDouble(), 350.0)
    }

    @Test fun `song that starts and ends loud has no sections`() {
        val s = SectionDetector.detect(signal(seg(30.0, 0.3)), sr)
        assertEquals(0L, s.introMs)
        assertEquals(0L, s.outroMs)
    }

    @Test fun `leading silence only`() {
        val s = SectionDetector.detect(signal(seg(1.2, 0.0), seg(30.0, 0.3)), sr)
        assertEquals(1_200.0, s.introMs.toDouble(), 60.0)
        assertEquals(0L, s.outroMs)
    }

    @Test fun `a short tail is not an outro`() {
        val s = SectionDetector.detect(signal(seg(30.0, 0.3), seg(1.0, 0.0)), sr)
        assertEquals(0L, s.outroMs)
    }

    @Test fun `trailing silence is an outro`() {
        val s = SectionDetector.detect(signal(seg(30.0, 0.3), seg(4.0, 0.0)), sr)
        assertEquals(30_000.0, s.outroMs.toDouble(), 250.0)
    }

    @Test fun `a long quiet opening is kept, only silence is skipped`() {
        // 1 s silence, 12 s at −20 dB, 20 s music: the quiet part is over 20 % of the track.
        val x = signal(seg(1.0, 0.0), seg(12.0, 0.03), seg(20.0, 0.3))
        val s = SectionDetector.detect(x, sr)
        assertEquals(1_000.0, s.introMs.toDouble(), 60.0)
    }

    @Test fun `intro is capped at a fifth of the track`() {
        // 9 s of digital silence before 20 s of music: skip at most 20 % (5.8 s).
        val s = SectionDetector.detect(signal(seg(9.0, 0.0), seg(20.0, 0.3)), sr)
        assertTrue("intro ${s.introMs}", s.introMs in 5_700L..5_850L)
    }

    @Test fun `a lone click doesn't start the music`() {
        val x = signal(seg(2.0, 0.0), seg(0.05, 0.5), seg(2.0, 0.0), seg(30.0, 0.3))
        val s = SectionDetector.detect(x, sr)
        assertEquals(4_050.0, s.introMs.toDouble(), 100.0)
    }

    @Test fun `silence and tiny inputs are safe`() {
        assertEquals(0L, SectionDetector.detect(FloatArray(20 * sr), sr).introMs)
        assertEquals(0L, SectionDetector.detect(FloatArray(0), sr).outroMs)
        assertEquals(0L, SectionDetector.detect(FloatArray(100), sr).introMs)
    }
}
