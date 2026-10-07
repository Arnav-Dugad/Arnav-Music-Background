package com.arnav.music.domain.history

/** Credits real elapsed time only when media position advances; seeks and stalls are excluded. */
class ListeningMeter {
    private var lastTime: Long? = null
    private var lastPosition: Long? = null
    private var pending = 0L
    fun reset() { lastTime = null; lastPosition = null; pending = 0 }
    fun sample(timeMs: Long, positionMs: Long, playing: Boolean, buffering: Boolean, speed: Float = 1f): Long {
        val previousTime = lastTime
        val previousPosition = lastPosition
        lastTime = timeMs
        if (previousTime == null || previousPosition == null) { lastPosition = positionMs; return 0 }
        val elapsed = (timeMs - previousTime).coerceIn(0, 2000)
        if (!playing || buffering) { pending = 0; lastPosition = positionMs; return 0 }
        pending = (pending + elapsed).coerceAtMost(2000)
        val delta = positionMs - previousPosition
        if (delta == 0L) return 0
        lastPosition = positionMs
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0 } ?: 1f
        val heard = (delta / safeSpeed).toLong()
        val credited = if (heard > 0 && heard <= pending + 1000) minOf(pending, heard) else 0L
        pending = 0
        return credited
    }
}
