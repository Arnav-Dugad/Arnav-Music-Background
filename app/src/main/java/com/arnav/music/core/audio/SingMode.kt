package com.arnav.music.core.audio

import com.arnav.music.domain.audio.VocalReducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apple Music–style "Sing": live vocal reduction for songs on this device.
 *
 * The level is shared process-wide: the UI writes it here and [SingAudioProcessor], running inside
 * ExoPlayer's audio sink in the playback service (same process), reads [target] for every buffer.
 * The processor ramps every change over ~150 ms, so the UI can set it at any time without clicks.
 * YouTube playback is not affected (its audio never reaches this app).
 */
object SingMode {
    private val _level = MutableStateFlow(0f)

    /** Current vocal reduction, 0 (off) … 1 (max). */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Read on the audio thread. */
    @Volatile
    var target: Float = 0f
        private set

    /** The level a single tap switches to. */
    const val DEFAULT_LEVEL: Float = VocalReducer.DEFAULT_LEVEL

    /** Last non-zero level picked with the slider, restored by [toggle]. */
    @Volatile
    private var lastOn: Float = DEFAULT_LEVEL

    val isOn: Boolean get() = target > 0f

    fun set(level: Float) {
        val v = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        if (v > 0f) lastOn = v
        target = v
        _level.value = v
    }

    /** Off ↔ the last level used (0.85 by default). */
    fun toggle() = set(if (isOn) 0f else lastOn)

    fun off() = set(0f)
}
