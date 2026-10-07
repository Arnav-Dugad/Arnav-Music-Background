package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlin.math.abs
import kotlin.math.exp

/** Why something was recommended. Rendered as short, honest copy ("Because you…"). */
enum class Reason {
    ARTIST_RETURNING, SIMILAR_ENERGY, GENRE_MATCH, FORGOTTEN_FAVORITE, NEW_DISCOVERY, HEAVY_ROTATION, LIKED, TIME_OF_DAY
}

data class Scored(val track: Track, val score: Float, val reason: Reason)

data class RecommendationWeights(
    val artist: Float = 1.0f,
    val genre: Float = 0.6f,
    val energy: Float = 0.5f,
    val familiarity: Float = 0.4f,
    val novelty: Float = 0.35f,
    val recencyPenalty: Float = 0.8f,
    val liked: Float = 0.5f,
)

/**
 * Deterministic hybrid ranker. Gemini never decides what plays; it can only shape the
 * constraints fed into this.
 */
class Recommender(private val weights: RecommendationWeights = RecommendationWeights()) {

    fun rank(
        candidates: List<Track>,
        profile: TasteProfile,
        now: Long,
        liked: Set<TrackId> = emptySet(),
        targetEnergy: Float? = null,
        discovery: Float = 0.3f,
        exclude: Set<TrackId> = emptySet(),
    ): List<Scored> {
        val seen = HashSet<TrackId>()
        return candidates.asSequence()
            .filter { it.id !in exclude && seen.add(it.id) }
            .map { score(it, profile, now, liked, targetEnergy ?: profile.energyPreference, discovery) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.track.id.value })
            .toList()
    }

    fun score(
        track: Track,
        profile: TasteProfile,
        now: Long,
        liked: Set<TrackId>,
        targetEnergy: Float?,
        discovery: Float,
    ): Scored {
        val artistAff = profile.artistAffinity[track.artistKey] ?: 0f
        val genreAff = track.genres.maxOfOrNull { profile.genreAffinity[it.lowercase()] ?: 0f } ?: 0f
        val familiarity = profile.trackFamiliarity[track.id] ?: 0f
        val novelty = 1f - familiarity
        val energyFit = if (targetEnergy != null && track.energy != null) 1f - abs(targetEnergy - track.energy) else 0.5f
        val lastPlayed = profile.lastPlayedAt[track.id]
        val hoursSince = lastPlayed?.let { (now - it) / 3_600_000f } ?: Float.MAX_VALUE
        // Strong penalty for something played in the last couple of hours, fading over a day.
        val recency = if (lastPlayed == null) 0f else exp(-hoursSince / 8f)
        val isLiked = track.id in liked

        val d = discovery.coerceIn(0f, 1f)
        val score = weights.artist * artistAff +
            weights.genre * genreAff +
            weights.energy * energyFit +
            weights.familiarity * familiarity * (1 - d) +
            weights.novelty * novelty * d +
            (if (isLiked) weights.liked else 0f) -
            weights.recencyPenalty * recency

        val reason = when {
            isLiked && hoursSince > 24 * 21 -> Reason.FORGOTTEN_FAVORITE
            familiarity < 0.05f && artistAff < 0.1f -> Reason.NEW_DISCOVERY
            artistAff > 0.6f -> Reason.ARTIST_RETURNING
            targetEnergy != null && track.energy != null && energyFit > 0.85f -> Reason.SIMILAR_ENERGY
            genreAff > 0.4f -> Reason.GENRE_MATCH
            familiarity > 0.7f -> Reason.HEAVY_ROTATION
            isLiked -> Reason.LIKED
            else -> Reason.TIME_OF_DAY
        }
        return Scored(track, score, reason)
    }
}
