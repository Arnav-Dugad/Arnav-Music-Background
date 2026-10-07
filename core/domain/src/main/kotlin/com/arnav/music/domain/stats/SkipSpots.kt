package com.arnav.music.domain.stats

/** One manual skip: where in the song the listener moved on. */
data class SkipMark(val trackId: String, val positionMs: Long, val durationMs: Long?, val at: Long)

/** A song repeatedly skipped at (about) the same moment. */
data class SkipSpot(
    val trackId: String,
    /** The typical skip position (median of the cluster). */
    val positionMs: Long,
    /** Skips inside the cluster. */
    val count: Int,
    /** All recorded skips of this song. */
    val totalSkips: Int,
    /** Earliest and latest skip position in the cluster. */
    val fromMs: Long,
    val toMs: Long,
    val durationMs: Long?,
    val lastAt: Long,
) {
    /** Whether a fade-out/trim at [positionMs] would be honoured by playback (see [SkipSpots.canTrim]). */
    val canTrim: Boolean get() = SkipSpots.canTrim(positionMs, durationMs)
}

/**
 * "Songs you skip at the same second": finds songs whose skips cluster in a narrow window
 * (all within ±[SkipSpots.TOLERANCE_MS] of the cluster's centre).
 */
object SkipSpots {
    const val TOLERANCE_MS = 6_000L
    const val MIN_SKIPS = 3
    /** The cluster must hold at least this share of the song's skips (otherwise the skips are just scattered). */
    const val MIN_SHARE = 0.5
    /** Skips this close to the start or end aren't recorded (see [isMeaningful]). */
    const val EDGE_MS = 5_000L

    /** A skip worth recording: not in the first or last [EDGE_MS] of the song. */
    fun isMeaningful(positionMs: Long, durationMs: Long?): Boolean =
        positionMs >= EDGE_MS && (durationMs == null || durationMs <= 0 || positionMs <= durationMs - EDGE_MS)

    /**
     * Playback only fades out early ("smart outro") from the second half of a song, with room for a fade
     * before the end — the same window the player checks before honouring an outro mark.
     */
    fun canTrim(positionMs: Long, durationMs: Long?): Boolean =
        durationMs != null && durationMs > 0 && positionMs >= durationMs / 2 && positionMs <= durationMs - 2_000L

    fun detect(
        marks: List<SkipMark>,
        toleranceMs: Long = TOLERANCE_MS,
        minSkips: Int = MIN_SKIPS,
        minShare: Double = MIN_SHARE,
        limit: Int = 10,
    ): List<SkipSpot> = marks.groupBy { it.trackId }.mapNotNull { (id, list) ->
        cluster(id, list, toleranceMs, minSkips, minShare)
    }.sortedWith(compareByDescending<SkipSpot> { it.count }.thenByDescending { it.lastAt }).take(limit)

    private fun cluster(id: String, marks: List<SkipMark>, tolerance: Long, minSkips: Int, minShare: Double): SkipSpot? {
        if (marks.size < minSkips) return null
        val sorted = marks.sortedBy { it.positionMs }
        val span = 2 * tolerance
        var bestStart = 0; var bestEnd = -1
        var j = 0
        for (i in sorted.indices) {
            if (j < i) j = i
            while (j + 1 < sorted.size && sorted[j + 1].positionMs - sorted[i].positionMs <= span) j++
            val size = j - i + 1
            val bestSize = bestEnd - bestStart + 1
            val better = size > bestSize || (size == bestSize &&
                sorted[j].positionMs - sorted[i].positionMs < sorted[bestEnd].positionMs - sorted[bestStart].positionMs)
            if (better) { bestStart = i; bestEnd = j }
        }
        val window = sorted.subList(bestStart, bestEnd + 1)
        if (window.size < minSkips || window.size < marks.size * minShare) return null
        val positions = window.map { it.positionMs }
        val mid = positions.size / 2
        val median = if (positions.size % 2 == 1) positions[mid] else (positions[mid - 1] + positions[mid]) / 2
        val duration = marks.maxByOrNull { it.at }?.durationMs ?: marks.firstNotNullOfOrNull { it.durationMs }
        return SkipSpot(id, median, window.size, marks.size, positions.first(), positions.last(), duration, window.maxOf { it.at })
    }

    /** "2:31". */
    fun formatPosition(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
