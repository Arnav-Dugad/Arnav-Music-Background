package com.arnav.music.core.analysis

import com.arnav.music.domain.audio.Camelot
import com.arnav.music.domain.audio.KeyNames
import kotlin.math.abs
import kotlin.math.roundToInt

/** Constants shared by the analyzer, the database rows it writes and the visuals that read them. */
object AudioFeatures {
    /** One envelope byte per this many milliseconds of audio. */
    const val ENVELOPE_STEP_MS = 500L

    /**
     * Bump when the analysis changes; older rows are then re-analyzed. 2: key, intro, outro.
     * 3: vocal-activity curve (stored as a file by [VocalActivityStore], not in the row).
     */
    const val VERSION = 3

    /** Short human label for an energy score, e.g. "High energy". */
    fun energyLabel(energy: Float): String = when {
        energy >= 0.66f -> "High energy"
        energy >= 0.38f -> "Medium energy"
        else -> "Low energy"
    }

    /** "8A · A minor"; empty when the key is unknown. */
    fun keyLabel(key: Int): String =
        if (!KeyNames.isValid(key)) "" else "${Camelot.code(key)} · ${KeyNames.name(key)}"

    /** "124 BPM · 8A · A minor · −9 LUFS · High energy"; parts that weren't measured are left out. */
    fun describe(bpm: Float, loudnessDb: Float, energy: Float, key: Int = KeyNames.UNKNOWN): String = buildList {
        if (bpm > 0f) add("${bpm.roundToInt()} BPM")
        if (KeyNames.isValid(key)) {
            add(Camelot.code(key))
            add(KeyNames.name(key))
        }
        if (loudnessDb > -69f) {
            val l = loudnessDb.roundToInt()
            add((if (l < 0) "−" else "") + "${abs(l)} LUFS")
        }
        add(energyLabel(energy))
    }.joinToString(" · ")
}
