package com.arnav.music.domain

import com.arnav.music.domain.audio.Camelot
import com.arnav.music.domain.audio.HarmonicMix
import com.arnav.music.domain.audio.KeyNames
import com.arnav.music.domain.audio.MixEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HarmonicMixTest {
    private fun e(name: String, key: Int, bpm: Float = 120f, energy: Float = 0.5f) = MixEntry(name, bpm, key, energy)

    @Test fun `walks the wheel from the current key`() {
        val c = e("C", KeyNames.major(0))
        val tracks = listOf(e("A", KeyNames.major(9)), e("G", KeyNames.major(7)), e("E", KeyNames.major(4)), e("D", KeyNames.major(2)))
        assertEquals(listOf("G", "D", "A", "E"), HarmonicMix.order(tracks, start = c))
    }

    @Test fun `unanalysed tracks keep their order at the end`() {
        val tracks = listOf(
            MixEntry("yt1", analysed = false),
            e("D", KeyNames.major(2)),
            MixEntry("local-unknown", analysed = false),
            e("G", KeyNames.major(7)),
            MixEntry("yt2", analysed = false),
            e("C", KeyNames.major(0)),
        )
        val out = HarmonicMix.order(tracks, start = e("cur", KeyNames.major(0)))
        assertEquals(listOf("C", "G", "D", "yt1", "local-unknown", "yt2"), out)
    }

    @Test fun `groups similar tempos`() {
        val k = KeyNames.minor(9)
        val tracks = listOf(128f, 90f, 127f, 91f, 126f, 92f).mapIndexed { i, b -> MixEntry("t$i:$b", b, k) }
        val out = HarmonicMix.order(tracks, start = MixEntry("cur", 128f, k))
        val bpms = out.map { it.substringAfter(':').toFloat() }
        assertTrue(out.toString(), bpms.take(3).all { it > 120f })
        assertTrue(out.toString(), bpms.drop(3).all { it < 100f })
    }

    @Test fun `half and double time count as close`() {
        assertTrue(HarmonicMix.tempoCost(70f, 140f) < HarmonicMix.tempoCost(100f, 140f))
        assertTrue(HarmonicMix.tempoCost(120f, 121f) < HarmonicMix.tempoCost(120f, 130f))
        assertEquals(0.0, HarmonicMix.tempoCost(120f, 120f), 1e-9)
    }

    @Test fun `compatible keys cost less`() {
        val c = 0
        assertTrue(HarmonicMix.keyCost(c, c) < HarmonicMix.keyCost(c, KeyNames.minor(9)))
        assertTrue(HarmonicMix.keyCost(c, KeyNames.major(7)) < HarmonicMix.keyCost(c, KeyNames.major(2)))
        assertTrue(HarmonicMix.keyCost(c, KeyNames.major(2)) < HarmonicMix.keyCost(c, KeyNames.major(6)))
    }

    @Test fun `finds a compatible path through a shuffled set`() {
        // Camelot 7..11, both letters: a compatible tour exists.
        val keys = (7..11).flatMap { n ->
            (0 until 24).filter { Camelot.number(it) == n }
        }
        repeat(5) { seed ->
            val rnd = Random(seed)
            val tracks = keys.shuffled(rnd).map { k -> MixEntry(k, 118f + rnd.nextFloat() * 6f, k, 0.5f) }
            val out = HarmonicMix.order(tracks)
            assertEquals(keys.toSet(), out.toSet())
            val good = out.zipWithNext().count { (a, b) -> Camelot.compatible(a, b) }
            assertTrue("seed $seed: ${out.map(Camelot::code)} ($good)", good >= out.size - 2)
        }
    }

    @Test fun `energy builds to a peak late in the set`() {
        val k = 0
        val tracks = (0..8).map { i -> MixEntry("e$i", 120f, k, i / 8f) }.shuffled(Random(4))
        val out = HarmonicMix.order(tracks)
        val energies = out.map { it.removePrefix("e").toInt() }
        val peakAt = energies.indexOf(8)
        assertTrue(energies.toString(), peakAt in 4..7)
        assertTrue(energies.toString(), energies.last() < 8 && energies.first() < 8)
        // Neighbours stay close: no jump over half the range.
        assertTrue(energies.toString(), energies.zipWithNext().all { (a, b) -> abs(a - b) <= 4 })
    }

    @Test fun `degenerate inputs`() {
        assertEquals(emptyList<String>(), HarmonicMix.order(emptyList<MixEntry<String>>()))
        assertEquals(listOf("a"), HarmonicMix.order(listOf(e("a", 0))))
        assertEquals(listOf("a", "b"), HarmonicMix.order(listOf(MixEntry("a", analysed = false), MixEntry("b", analysed = false))))
    }

    @Test fun `large sets fall back to greedy and keep every track`() {
        val rnd = Random(9)
        val tracks = (0 until 400).map { MixEntry(it, 80f + rnd.nextFloat() * 80f, rnd.nextInt(24), rnd.nextFloat()) }
        val out = HarmonicMix.order(tracks)
        assertEquals(400, out.toSet().size)
    }
}
