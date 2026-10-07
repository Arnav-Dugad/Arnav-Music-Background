package com.arnav.music.core.analysis

import com.arnav.music.core.db.FeatureSummary
import com.arnav.music.domain.model.Track

/**
 * Fills [Track.energy] from on-device analysis where a successful analysis exists
 * (keyed by track id); tracks without one keep whatever energy they already had.
 */
fun List<Track>.withFeatures(map: Map<String, FeatureSummary>): List<Track> {
    if (map.isEmpty() || isEmpty()) return this
    val out = ArrayList<Track>(size)
    for (t in this) {
        val f = map[t.id.value]
        out += if (f != null && f.energy.isFinite()) t.copy(energy = f.energy.coerceIn(0f, 1f)) else t
    }
    return out
}
