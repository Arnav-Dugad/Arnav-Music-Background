package com.arnav.music.domain

import com.arnav.music.domain.audio.AudioDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class AudioDspTest {
    private val sr = 11_025

    /** Decaying sine bursts on every beat, starting at [firstBeatMs], over faint noise. */
    private fun clickTrack(bpm: Double, firstBeatMs: Double, seconds: Double = 20.0): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val rnd = Random(42)
        for (i in 0 until n) out[i] = (rnd.nextFloat() - 0.5f) * 0.002f
        val periodS = 60.0 / bpm
        var beat = firstBeatMs / 1000.0
        val burst = (0.08 * sr).toInt()
        while (beat < seconds) {
            val start = (beat * sr).toInt()
            for (j in 0 until burst) {
                val idx = start + j
                if (idx >= n) break
                val t = j.toDouble() / sr
                out[idx] += (0.8 * exp(-t / 0.02) * sin(2 * PI * 880.0 * t)).toFloat()
            }
            beat += periodS
        }
        return out
    }

    /** Circular distance between two offsets modulo the beat period. */
    private fun phaseError(actualMs: Long, expectedMs: Double, periodMs: Double): Double {
        val d = ((actualMs - expectedMs) % periodMs + periodMs) % periodMs
        return minOf(d, periodMs - d)
    }

    @Test fun `click track at 120 bpm`() {
        val a = AudioDsp.analyze(clickTrack(120.0, 230.0), sr)
        assertEquals(120f, a.tempo.bpm, 2f)
        assertTrue("phase error", phaseError(a.tempo.beatOffsetMs, 230.0, 500.0) <= 40.0)
        assertTrue(a.tempo.confidence >= AudioDsp.MIN_TEMPO_CONFIDENCE)
    }

    @Test fun `click track at 90 bpm`() {
        val a = AudioDsp.analyze(clickTrack(90.0, 410.0), sr)
        assertEquals(90f, a.tempo.bpm, 2f)
        assertTrue("phase error", phaseError(a.tempo.beatOffsetMs, 410.0, 60_000.0 / 90.0) <= 40.0)
    }

    @Test fun `silence has no beat and very low loudness`() {
        val a = AudioDsp.analyze(FloatArray(sr * 10), sr)
        assertEquals(0f, a.tempo.bpm, 0f)
        assertTrue(a.loudnessLufs <= -60f)
        assertTrue(a.energy < 0.1f)
        assertTrue(a.envelope.all { it.toInt() == 0 })
    }

    @Test fun `noise has no confident beat`() {
        val rnd = Random(7)
        val noise = FloatArray(sr * 15) { (rnd.nextFloat() - 0.5f) * 0.5f }
        val t = AudioDsp.estimateTempo(AudioDsp.onsetEnvelope(noise, sr))
        assertEquals(0f, t.bpm, 0f)
    }

    @Test fun `full scale 1 kHz sine is about minus 3 LUFS`() {
        // Mean square 0.5 → −0.691 − 3.01 dB, plus ~+0.7 dB of K-weighting gain at 1 kHz ≈ −3.0.
        val x = FloatArray(sr * 5) { sin(2 * PI * 1000.0 * it / sr).toFloat() }
        assertEquals(-3f, AudioDsp.integratedLoudness(x, sr), 1.5f)
        // 20 dB quieter reads ~20 LU lower.
        val quiet = FloatArray(x.size) { x[it] * 0.1f }
        assertEquals(AudioDsp.integratedLoudness(x, sr) - 20f, AudioDsp.integratedLoudness(quiet, sr), 0.5f)
    }

    @Test fun `envelope has one byte per half second`() {
        val n = (20.3 * sr).toInt()
        val x = FloatArray(n) { sin(2 * PI * 440.0 * it / sr).toFloat() * (it.toFloat() / n) }
        val env = AudioDsp.energyEnvelope(x, sr, stepMs = 500L)
        assertEquals(ceil(n / (sr * 0.5)).toInt(), env.size)
        assertEquals(41, env.size)
        // Rising ramp: last bytes saturate near 255, first is near 0.
        assertTrue((env.last().toInt() and 0xFF) >= 240)
        assertTrue((env.first().toInt() and 0xFF) <= 20)
        // Partial buffers are honoured.
        assertEquals(20, AudioDsp.energyEnvelope(x, sr, length = sr * 10, stepMs = 500L).size)
    }

    @Test fun `energy score stays in range and follows loudness`() {
        val loud = AudioDsp.energyScore(-6f, 4f, 128f)
        val quiet = AudioDsp.energyScore(-22f, 0.5f, 70f)
        assertTrue(loud in 0f..1f && quiet in 0f..1f)
        assertTrue(loud > quiet + 0.4f)
        assertTrue(abs(AudioDsp.energyScore(10f, 100f, 300f) - 1f) < 1e-6f)
    }
}
