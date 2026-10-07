package com.arnav.music.domain.audio

import org.junit.Assert.*
import org.junit.Test

class CrossfadeTest {
    @Test fun equalPowerThroughoutTheOverlap() {
        for (i in 0..100) {
            val (a, b) = Crossfade.gains(i / 100f)
            assertEquals(1f, a * a + b * b, 0.00001f)
        }
        assertEquals(1f, Crossfade.gains(-1f).first, 0f)
        assertEquals(1f, Crossfade.gains(2f).second, 0f)
    }
    @Test fun shortTracksCannotBeMostlyOverlapped() {
        assertEquals(2_000L, Crossfade.duration(12_000, 6_000, 90_000))
        assertEquals(0L, Crossfade.duration(-1, 180_000, 180_000))
    }
}
