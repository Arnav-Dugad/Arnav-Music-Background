package com.arnav.music.domain

import com.arnav.music.domain.audio.DropDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class DropDetectorTest {
    private val step = 500L
    private val rnd = Random(7)

    /** Builds a byte envelope from (seconds, dB at start, dB at end) segments, with ±[jitterDb] noise. */
    private fun env(vararg parts: Triple<Double, Double, Double>, jitterDb: Double = 1.0): ByteArray {
        val out = ArrayList<Byte>()
        for ((s, d0, d1) in parts) {
            val count = (s * 1000 / step).roundToInt()
            for (k in 0 until count) {
                val d = d0 + (d1 - d0) * k / count + (rnd.nextDouble() * 2 - 1) * jitterDb
                out += (10.0.pow(d / 20.0) * 230).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out.toByteArray()
    }

    private fun seg(s: Double, db: Double) = Triple(s, db, db)
    private fun ramp(s: Double, from: Double, to: Double) = Triple(s, from, to)

    @Test fun quietToLoudJumpIsADrop() {
        val e = env(seg(30.0, -14.0), seg(30.0, 0.0))
        val drops = DropDetector.detect(e, step)
        assertEquals(1, drops.size)
        assertTrue("at ${drops[0].atMs}", abs(drops[0].atMs - 30_000) <= 500)
        assertTrue(drops[0].strength > 0.5f)
    }

    @Test fun dropSnapsToTheBeatGrid() {
        val e = env(seg(30.0, -14.0), seg(30.0, 0.0))
        val bpm = 128f
        val offset = 120L
        val drop = DropDetector.detect(e, step, bpm, offset).single()
        val beat = 60_000.0 / bpm
        val phase = ((drop.atMs - offset) / beat)
        assertTrue("phase $phase", abs(phase - Math.round(phase)) < 0.01)
        assertTrue("at ${drop.atMs}", abs(drop.atMs - 30_000) <= 500 + DropDetector.MAX_SNAP_MS)
    }

    @Test fun buildUpWithShortRiserStillCounts() {
        // A breakdown, then a riser swelling for 4 s, then the drop.
        val e = env(seg(20.0, 0.0), seg(10.0, -16.0), ramp(4.0, -16.0, -7.0), seg(25.0, 0.0))
        val drops = DropDetector.detect(e, step)
        assertEquals(1, drops.size)
        assertTrue("at ${drops[0].atMs}", abs(drops[0].atMs - 34_000) <= 500)
    }

    @Test fun gradualCrescendoIsNot() {
        // Several noise seeds, slow and fairly quick swells (1.2 and 2.5 dB/s).
        repeat(20) {
            val slow = DropDetector.detect(env(seg(10.0, -24.0), ramp(20.0, -24.0, 0.0), seg(30.0, 0.0)), step)
            assertTrue("$slow", slow.isEmpty())
            val quick = DropDetector.detect(env(seg(10.0, -20.0), ramp(8.0, -20.0, 0.0), seg(30.0, 0.0)), step)
            assertTrue("$quick", quick.isEmpty())
        }
    }

    @Test fun noisyFlatSignalIsNot() {
        repeat(20) {
            val drops = DropDetector.detect(env(seg(240.0, -3.0), jitterDb = 4.0), step)
            assertTrue("$drops", drops.isEmpty())
        }
    }

    @Test fun shortDipIsNotABreakdown() {
        // 2 s of near-quiet isn't a sustained breakdown.
        val e = env(seg(30.0, 0.0), seg(2.0, -20.0), seg(30.0, 0.0))
        assertTrue(DropDetector.detect(e, step).isEmpty())
    }

    @Test fun shortBurstDoesNotStayHigh() {
        val e = env(seg(30.0, -15.0), seg(1.5, 0.0), seg(30.0, -15.0), seg(20.0, 0.0))
        val drops = DropDetector.detect(e, step)
        assertEquals(1, drops.size)
        assertTrue(abs(drops[0].atMs - 61_500) <= 500)
    }

    @Test fun quietPassageJumpsInsideAQuietSongAreIgnored() {
        // A jump from very quiet to merely quiet, in a song whose loud parts are far louder.
        val e = env(seg(60.0, 0.0), seg(20.0, -40.0), seg(20.0, -25.0), seg(60.0, 0.0))
        val drops = DropDetector.detect(e, step)
        assertEquals(1, drops.size)
        assertTrue(abs(drops[0].atMs - 100_000) <= 500)
    }

    @Test fun songStructureFindsEachDropInOrder() {
        val e = env(
            seg(16.0, -12.0), seg(30.0, 0.0), seg(16.0, -15.0), seg(30.0, 0.0), seg(10.0, -10.0), seg(30.0, 0.0),
        )
        val drops = DropDetector.detect(e, step).map { it.atMs }
        assertEquals(3, drops.size)
        assertTrue("$drops", abs(drops[0] - 16_000) <= 500)
        assertTrue("$drops", abs(drops[1] - 62_000) <= 500)
        assertTrue("$drops", abs(drops[2] - 102_000) <= 500)
    }

    @Test fun capsCountAndKeepsSpacing() {
        val parts = ArrayList<Triple<Double, Double, Double>>()
        repeat(12) { parts += seg(8.0, -15.0); parts += seg(6.0, 0.0) }
        val drops = DropDetector.detect(env(*parts.toTypedArray()), step)
        assertTrue(drops.size in 1..DropDetector.MAX_DROPS)
        for (k in 1 until drops.size) assertTrue(drops[k].atMs - drops[k - 1].atMs >= DropDetector.MIN_SPACING_MS)
    }

    @Test fun linearLevelsWithAFinerStepWork() {
        // 250 ms steps: 20 s at a quarter of the level (−12 dB), then 20 s loud; drop at 20 s.
        val levels = FloatArray(160) { if (it < 80) 0.25f else 1f }
        val drop = DropDetector.detect(levels, 250L).single()
        assertTrue("at ${drop.atMs}", abs(drop.atMs - 20_000) <= 250)
    }

    @Test fun tooShortOrSilentGivesNothing() {
        assertTrue(DropDetector.detect(ByteArray(10), step).isEmpty())
        assertTrue(DropDetector.detect(ByteArray(400), step).isEmpty())
        assertTrue(DropDetector.detect(ByteArray(0), step, 120f, 0L).isEmpty())
    }
}
