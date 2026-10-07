package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The user's "taste vector": a set of normalised affinities computed entirely on device from
 * Arnav Music listening activity. Everything is in 0..1 unless noted.
 */
data class TasteProfile(
    val artistAffinity: Map<String, Float>,
    val genreAffinity: Map<String, Float>,
    val trackFamiliarity: Map<TrackId, Float>,
    val trackPlayCounts: Map<TrackId, Int>,
    val lastPlayedAt: Map<TrackId, Long>,
    val artistLastPlayedAt: Map<String, Long>,
    /** Preferred energy (0..1) or null when we have no energy metadata to learn from. */
    val energyPreference: Float?,
    /** 24 buckets of listening minutes per hour of day. */
    val hourHistogram: List<Float>,
    /** 7 buckets, Monday first. */
    val dayHistogram: List<Float>,
    val skipRate: Float,
    val repeatTendency: Float,
    val discoveryRatio: Float,
    val totalListenMs: Long,
    val eventCount: Int,
) {
    val isCold: Boolean get() = eventCount < 5

    companion object {
        val Empty = TasteProfile(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
            List(24) { 0f }, List(7) { 0f }, 0f, 0f, 0f, 0L, 0,
        )
    }
}

object TasteProfileBuilder {
    /** Half-life for recency decay of affinity: listening 30 days ago counts half as much. */
    const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0

    fun build(
        events: List<PlayEvent>,
        tracks: Map<TrackId, Track>,
        likedIds: Set<TrackId>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TasteProfile {
        if (events.isEmpty() && likedIds.isEmpty()) return TasteProfile.Empty
        val lambda = ln(2.0) / HALF_LIFE_DAYS

        val artist = HashMap<String, Double>()
        val genre = HashMap<String, Double>()
        val trackWeight = HashMap<TrackId, Double>()
        val counts = HashMap<TrackId, Int>()
        val lastPlayed = HashMap<TrackId, Long>()
        val artistLast = HashMap<String, Long>()
        val hours = DoubleArray(24)
        val days = DoubleArray(7)
        var energySum = 0.0
        var energyWeight = 0.0
        var skips = 0
        var totalMs = 0L

        for (e in events) {
            val ageDays = max(0.0, (now - e.startedAt) / DAY_MS)
            val decay = exp(-lambda * ageDays)
            // Engagement: completed listens count fully, skips count negative-ish.
            val engagement = when {
                e.skipped && e.completionRatio < 0.3f -> -0.35
                else -> 0.25 + 0.75 * e.completionRatio
            }
            val w = decay * engagement
            artist.merge(e.artistKey, w, Double::plus)
            trackWeight.merge(e.trackId, w, Double::plus)
            if (!e.skipped || e.completionRatio > 0.5f) counts.merge(e.trackId, 1, Int::plus)
            lastPlayed.merge(e.trackId, e.startedAt) { a, b -> maxOf(a, b) }
            artistLast.merge(e.artistKey, e.startedAt) { a, b -> maxOf(a, b) }
            tracks[e.trackId]?.let { t ->
                t.genres.forEach { g -> genre.merge(g.lowercase(), w, Double::plus) }
                t.energy?.let { en ->
                    if (w > 0) { energySum += en * w; energyWeight += w }
                }
            }
            if (e.skipped) skips++
            totalMs += e.listenedMs
            val dt = Instant.ofEpochMilli(e.startedAt).atZone(zone)
            hours[dt.hour] += e.listenedMs / 60_000.0
            days[dt.dayOfWeek.value - 1] += e.listenedMs / 60_000.0
        }
        // Likes are strong, durable signals.
        for (id in likedIds) {
            val t = tracks[id] ?: continue
            artist.merge(t.artistKey, 1.5, Double::plus)
            trackWeight.merge(id, 1.5, Double::plus)
            t.genres.forEach { g -> genre.merge(g.lowercase(), 0.8, Double::plus) }
        }

        val distinctTracks = counts.size.coerceAtLeast(1)
        val repeats = counts.values.count { it >= 3 }
        val firstListens = counts.values.count { it == 1 }

        return TasteProfile(
            artistAffinity = artist.normalised(),
            genreAffinity = genre.normalised(),
            trackFamiliarity = trackWeight.mapValues { (_, v) -> (1 - exp(-v.coerceAtLeast(0.0) / 2.0)).toFloat() },
            trackPlayCounts = counts,
            lastPlayedAt = lastPlayed,
            artistLastPlayedAt = artistLast,
            energyPreference = if (energyWeight > 0) (energySum / energyWeight).toFloat() else null,
            hourHistogram = hours.map { it.toFloat() },
            dayHistogram = days.map { it.toFloat() },
            skipRate = if (events.isEmpty()) 0f else skips.toFloat() / events.size,
            repeatTendency = (repeats.toFloat() / distinctTracks).coerceIn(0f, 1f),
            discoveryRatio = (firstListens.toFloat() / distinctTracks).coerceIn(0f, 1f),
            totalListenMs = totalMs,
            eventCount = events.size,
        )
    }

    private fun <K> Map<K, Double>.normalised(): Map<K, Float> {
        val positive = filterValues { it > 0 }
        val top = positive.values.maxOrNull() ?: return emptyMap()
        return positive.mapValues { (_, v) -> (v / top).toFloat() }
    }
}
