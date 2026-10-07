package com.arnav.music.domain

import com.arnav.music.domain.audio.Camelot
import com.arnav.music.domain.audio.KeyDetector
import com.arnav.music.domain.audio.KeyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class KeyDetectorTest {
    private val sr = 11_025

    private fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Adds a harmonic-rich tone (5 partials, 1/h amplitudes, soft attack/release) to [out]. */
    private fun addTone(out: FloatArray, midi: Int, startS: Double, durS: Double, amp: Double) {
        val f = freq(midi)
        val a = (startS * sr).toInt()
        val len = (durS * sr).toInt()
        val ramp = (0.02 * sr).toInt()
        for (i in 0 until len) {
            val idx = a + i
            if (idx >= out.size) break
            val t = i.toDouble() / sr
            val env = minOf(1.0, i.toDouble() / ramp, (len - i).toDouble() / ramp)
            var v = 0.0
            for (h in 1..5) if (f * h < sr / 2.0) v += sin(2 * PI * f * h * t) / h
            out[idx] += (amp * env * v).toFloat()
        }
    }

    /**
     * Chords (each a list of MIDI notes, root first; the root is doubled an octave down as a bass)
     * held [chordS] seconds, cycled for [seconds], with a stepwise [melody] on top.
     */
    private fun piece(chords: List<List<Int>>, melody: List<Int>, seconds: Double = 24.0, chordS: Double = 2.0): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        var t = 0.0
        var c = 0
        while (t < seconds) {
            val chord = chords[c % chords.size]
            for (n in chord) addTone(out, n, t, chordS, 0.08)
            addTone(out, chord[0] - 12, t, chordS, 0.1)
            t += chordS; c++
        }
        val noteS = 0.5
        t = 0.0
        var m = 0
        while (t < seconds) {
            addTone(out, melody[m % melody.size], t, noteS, 0.07)
            t += noteS; m++
        }
        val rnd = Random(7)
        for (i in out.indices) out[i] += (rnd.nextFloat() - 0.5f) * 0.004f
        return out
    }

    /** I–IV–V–I in the major key on [tonic] (MIDI), with the major scale as melody. */
    private fun majorPiece(tonic: Int): FloatArray {
        val i = listOf(tonic, tonic + 4, tonic + 7)
        val iv = listOf(tonic + 5, tonic + 9, tonic + 12)
        val v = listOf(tonic + 7, tonic + 11, tonic + 14)
        val scale = listOf(0, 2, 4, 5, 7, 9, 11, 12, 11, 9, 7, 5, 4, 2).map { tonic + 12 + it }
        return piece(listOf(i, iv, v, i), scale)
    }

    /** i–iv–V–i in the minor key on [tonic] (MIDI), harmonic-minor melody. */
    private fun minorPiece(tonic: Int): FloatArray {
        val i = listOf(tonic, tonic + 3, tonic + 7)
        val iv = listOf(tonic + 5, tonic + 8, tonic + 12)
        val v = listOf(tonic + 7, tonic + 11, tonic + 14)
        val scale = listOf(0, 2, 3, 5, 7, 8, 11, 12, 11, 8, 7, 5, 3, 2).map { tonic + 12 + it }
        return piece(listOf(i, iv, v, i), scale)
    }

    @Test fun `C major progression is C major`() {
        val k = KeyDetector.detect(majorPiece(48), sr)
        assertEquals("got ${KeyNames.name(k.key)} (${k.confidence})", KeyNames.major(0), k.key)
    }

    @Test fun `A minor progression is A minor`() {
        val k = KeyDetector.detect(minorPiece(57), sr)
        assertEquals("got ${KeyNames.name(k.key)} (${k.confidence})", KeyNames.minor(9), k.key)
    }

    @Test fun `transposed keys are found`() {
        val eb = KeyDetector.detect(majorPiece(51), sr)
        assertEquals("got ${KeyNames.name(eb.key)}", KeyNames.major(3), eb.key)
        val fs = KeyDetector.detect(minorPiece(54), sr)
        assertEquals("got ${KeyNames.name(fs.key)}", KeyNames.minor(6), fs.key)
    }

    @Test fun `sustained C major triad alone reads as C major`() {
        val out = FloatArray(12 * sr)
        for (n in listOf(48, 52, 55, 60, 64, 67)) addTone(out, n, 0.0, 12.0, 0.08)
        val k = KeyDetector.detect(out, sr)
        assertEquals("got ${KeyNames.name(k.key)}", KeyNames.major(0), k.key)
    }

    @Test fun `chroma peaks on the played pitch classes`() {
        val out = FloatArray(8 * sr)
        for (n in listOf(57, 60, 64)) addTone(out, n, 0.0, 8.0, 0.1) // A C E
        val chroma = KeyDetector.chroma(out, sr)
        val top = chroma.indices.sortedByDescending { chroma[it] }.take(3).toSet()
        assertEquals(setOf(9, 0, 4), top)
        assertEquals(1f, chroma.sum(), 1e-3f)
    }

    @Test fun `noise and silence have no key`() {
        val rnd = Random(3)
        val noise = FloatArray(20 * sr) { (rnd.nextFloat() - 0.5f) * 0.5f }
        val k = KeyDetector.detect(noise, sr)
        assertEquals("noise got ${KeyNames.name(k.key)} (${k.confidence})", -1, k.key)
        assertEquals(-1, KeyDetector.detect(FloatArray(10 * sr), sr).key)
        assertEquals(-1, KeyDetector.detect(FloatArray(0), sr).key)
    }

    @Test fun `works at 9600 Hz too`() {
        val rate = 9_600
        val out = FloatArray(16 * rate)
        val chords = listOf(listOf(57, 60, 64), listOf(62, 65, 69), listOf(64, 68, 71), listOf(57, 60, 64))
        for ((ci, chord) in (0 until 8).map { it to chords[it % 4] }) {
            for (n in chord + (chord[0] - 12)) {
                val f = freq(n)
                val a = ci * 2 * rate
                for (i in 0 until 2 * rate) {
                    val t = i.toDouble() / rate
                    var v = 0.0
                    for (h in 1..4) if (f * h < rate / 2.0) v += sin(2 * PI * f * h * t) / h
                    out[a + i] += (0.08 * v).toFloat()
                }
            }
        }
        assertEquals(KeyNames.minor(9), KeyDetector.detect(out, rate).key)
    }

    @Test fun `key names`() {
        assertEquals("A minor", KeyNames.name(KeyNames.minor(9)))
        assertEquals("C major", KeyNames.name(0))
        assertEquals("E♭ major", KeyNames.name(3))
        assertEquals("F♯ minor", KeyNames.name(18))
        assertEquals("", KeyNames.name(-1))
        assertEquals(KeyNames.major(0), KeyNames.relative(KeyNames.minor(9)))
        assertEquals(KeyNames.minor(9), KeyNames.relative(0))
    }

    @Test fun `camelot codes`() {
        assertEquals("8B", Camelot.code(0)) // C major
        assertEquals("8A", Camelot.code(KeyNames.minor(9))) // A minor
        assertEquals("9B", Camelot.code(KeyNames.major(7))) // G major
        assertEquals("9A", Camelot.code(KeyNames.minor(4))) // E minor
        assertEquals("7B", Camelot.code(KeyNames.major(5))) // F major
        assertEquals("1B", Camelot.code(KeyNames.major(11))) // B major
        assertEquals("12B", Camelot.code(KeyNames.major(4))) // E major
        assertEquals("3B", Camelot.code(KeyNames.major(1))) // D♭ major
        assertEquals("5A", Camelot.code(KeyNames.minor(0))) // C minor
        assertEquals("", Camelot.code(-1))
        // Every code appears exactly once.
        assertEquals(24, (0 until 24).map { Camelot.code(it) }.toSet().size)
    }

    @Test fun `camelot compatibility`() {
        val c = 0; val am = KeyNames.minor(9); val g = 7; val f = 5; val d = 2; val em = KeyNames.minor(4)
        assertTrue(Camelot.compatible(c, c))
        assertTrue(Camelot.compatible(c, am)) // relative
        assertTrue(Camelot.compatible(c, g)) // 8B → 9B
        assertTrue(Camelot.compatible(c, f)) // 8B → 7B
        assertTrue(Camelot.compatible(am, em)) // 8A → 9A
        assertTrue(Camelot.compatible(KeyNames.major(11), KeyNames.major(4))) // 1B ↔ 12B wraps
        assertFalse(Camelot.compatible(c, d)) // 8B → 10B
        assertFalse(Camelot.compatible(c, em)) // 8B → 9A
        assertFalse(Camelot.compatible(c, -1))
    }
}
