package com.arnav.music.domain.audio

import kotlin.math.cos
import kotlin.math.sin

/** Equal-power gains: independent of frame rate and of the tracks' metadata. */
object Crossfade {
    fun gains(fraction: Float): Pair<Float, Float> {
        val phase = fraction.coerceIn(0f, 1f) * Math.PI / 2
        return cos(phase).toFloat() to sin(phase).toFloat()
    }
    fun duration(requestedMs: Int, outgoingMs: Long, incomingMs: Long): Long =
        minOf(requestedMs.coerceIn(0, 12_000).toLong(), outgoingMs / 3, incomingMs / 3).coerceAtLeast(0)
}
