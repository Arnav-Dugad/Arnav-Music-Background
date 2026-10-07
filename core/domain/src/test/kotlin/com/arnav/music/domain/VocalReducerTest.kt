package com.arnav.music.domain

import com.arnav.music.domain.audio.VocalReducer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class VocalReducerTest {
    private val sr = 44_100

    private fun tone(hz: Double, seconds: Double, amp: Double = 0.5): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }
    }

    /** RMS in dB over the last [tailSeconds] (after filters and ramps have settled). */
    private fun rmsDb(x: FloatArray, tailSeconds: Double = 0.5): Double {
        val start = x.size - (tailSeconds * sr).toInt()
        var sum = 0.0
        for (i in start until x.size) sum += x[i].toDouble() * x[i]
        return 20 * log10(sqrt(sum / (x.size - start)) + 1e-12)
    }

    private fun run(left: FloatArray, right: FloatArray, level: Float): Pair<FloatArray, FloatArray> {
        val l = left.copyOf()
        val r = right.copyOf()
        val v = VocalReducer(sr)
        v.target = level
        // Feed in small blocks like an audio sink would.
        val block = 1024
        var at = 0
        while (at < l.size) {
            val n = minOf(block, l.size - at)
            val bl = l.copyOfRange(at, at + n)
            val br = r.copyOfRange(at, at + n)
            v.process(bl, br, n)
            bl.copyInto(l, at)
            br.copyInto(r, at)
            at += n
        }
        return l to r
    }

    @Test
    fun centrePannedVocalRangeToneIsAttenuatedAtLeast12dB() {
        for (hz in listOf(300.0, 1_000.0, 2_500.0)) {
            val x = tone(hz, 2.0)
            val (l, r) = run(x, x.copyOf(), 1f)
            val drop = rmsDb(x) - rmsDb(l)
            assertTrue("centre $hz Hz only dropped $drop dB (left)", drop >= 12.0)
            assertTrue("centre $hz Hz only dropped ${rmsDb(x) - rmsDb(r)} dB (right)", rmsDb(x) - rmsDb(r) >= 12.0)
        }
    }

    @Test
    fun hardPannedToneIsPreservedWithin1dB() {
        for (hz in listOf(150.0, 440.0, 1_000.0, 3_000.0, 5_000.0)) {
            val x = tone(hz, 2.0)
            val silent = FloatArray(x.size)
            val (l, r) = run(x, silent, 1f)
            val diff = abs(rmsDb(x) - rmsDb(l))
            assertTrue("hard-left $hz Hz changed by $diff dB", diff <= 1.0)
            assertTrue("hard-left $hz Hz leaked into the right channel: ${rmsDb(r)} dB", rmsDb(r) < rmsDb(x) - 20)
            // And mirrored.
            val (l2, r2) = run(silent, x, 1f)
            assertTrue("hard-right $hz Hz changed", abs(rmsDb(x) - rmsDb(r2)) <= 1.0)
            assertTrue(rmsDb(l2) < rmsDb(x) - 20)
        }
    }

    @Test
    fun centreBassBelow120HzIsPreservedWithin2dB() {
        for (hz in listOf(50.0, 80.0, 100.0, 118.0)) {
            val x = tone(hz, 2.5)
            val (l, r) = run(x, x.copyOf(), 1f)
            assertTrue("bass $hz Hz changed by ${rmsDb(x) - rmsDb(l)} dB", abs(rmsDb(x) - rmsDb(l)) <= 2.0)
            assertTrue(abs(rmsDb(x) - rmsDb(r)) <= 2.0)
        }
    }

    @Test
    fun vocalOverWideGuitarsReducesVocalMoreThanGuitars() {
        val vocal = tone(700.0, 2.0, 0.3)
        val guitar = tone(1_900.0, 2.0, 0.3)
        val left = FloatArray(vocal.size) { vocal[it] + guitar[it] }
        val right = vocal.copyOf()
        val (l, _) = run(left, right, 1f)
        // Overall the left channel loses energy, but far less than a centre-only signal would.
        val drop = rmsDb(left) - rmsDb(l)
        assertTrue("drop $drop", drop > 1.0 && drop < 12.0)
    }

    @Test
    fun levelZeroIsBitExactPassThrough() {
        val x = tone(440.0, 0.5)
        val pcm = ShortArray(x.size * 2) { (x[it / 2] * 32767).toInt().toShort() }
        val copy = pcm.copyOf()
        val v = VocalReducer(sr)
        v.processInterleaved16(pcm, x.size)
        assertArrayEquals(copy, pcm)
        assertTrue(v.idle)
    }

    @Test
    fun levelChangesRampWithoutJumps() {
        val x = tone(1_000.0, 1.5, 0.4)
        val pcm = ShortArray(x.size * 2) { (x[it / 2] * 32767).toInt().toShort() }
        val v = VocalReducer(sr)
        v.target = 1f
        val half = x.size / 2
        val first = pcm.copyOfRange(0, half * 2)
        v.processInterleaved16(first, half)
        // Within ~150 ms the level has reached the target, not instantly.
        assertEquals(1f, v.level, 1e-6f)
        v.target = 0f
        val a = pcm.copyOfRange(half * 2, half * 2 + 512)
        v.processInterleaved16(a, 256)
        assertTrue("level fell too fast: ${v.level}", v.level > 0.9f)
        val b = pcm.copyOfRange(half * 2 + 512, pcm.size)
        v.processInterleaved16(b, b.size / 2)
        assertTrue(v.idle)
        // Max sample-to-sample step stays small (no clicks) through the transitions.
        val out = first + a + b
        var maxStep = 0
        for (i in 2 until out.size step 2) maxStep = maxOf(maxStep, abs(out[i] - out[i - 2]))
        assertTrue("click: $maxStep", maxStep < 32767 * 0.25)
    }

    @Test
    fun softClipNeverExceedsFullScale() {
        assertEquals(0.5, VocalReducer.softClip(0.5), 1e-12)
        assertTrue(VocalReducer.softClip(3.0) <= 1.0)
        assertTrue(VocalReducer.softClip(-3.0) >= -1.0)
        assertTrue(VocalReducer.softClip(0.95) in 0.85..0.95)
    }
}
