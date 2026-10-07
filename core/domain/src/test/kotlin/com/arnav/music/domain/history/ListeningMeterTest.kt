package com.arnav.music.domain.history
import org.junit.Assert.assertEquals
import org.junit.Test
class ListeningMeterTest {
    @Test fun sparseUpdatesAndStalls() {
        val m = ListeningMeter()
        m.sample(0, 0, true, false)
        assertEquals(0L, m.sample(250, 0, true, false))
        assertEquals(0L, m.sample(500, 0, true, false))
        assertEquals(1000L, m.sample(1000, 1000, true, false))
        m.sample(5000, 1000, true, false)
        assertEquals(250L, m.sample(5250, 1250, true, false))
    }
    @Test fun pausesBufferingAndSeeksDoNotCount() {
        val m = ListeningMeter()
        m.sample(0, 0, true, false)
        assertEquals(0L, m.sample(1000, 1000, true, true))
        assertEquals(0L, m.sample(2000, 1000, false, false))
        assertEquals(0L, m.sample(3000, 60000, true, false))
        assertEquals(0L, m.sample(4000, 10000, true, false))
        assertEquals(1000L, m.sample(5000, 11000, true, false))
    }
    @Test fun speedAndTrackChanges() {
        val m = ListeningMeter()
        m.sample(0, 0, true, false, 2f)
        assertEquals(1000L, m.sample(1000, 2000, true, false, 2f))
        m.reset()
        assertEquals(0L, m.sample(2000, 0, true, false))
        assertEquals(1000L, m.sample(3000, 1000, true, false))
    }
}
