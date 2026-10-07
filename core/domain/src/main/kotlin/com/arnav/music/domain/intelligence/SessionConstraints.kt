package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.AestheticDescriptor
import com.arnav.music.domain.model.Mood
import kotlinx.serialization.Serializable

@Serializable
enum class EnergyCurve { FLAT, RISING, FALLING, WAVE, PEAK }

/**
 * Structured interpretation of a natural-language request. Produced by Gemini when available,
 * or by [LocalIntentEngine] offline. Deterministic code turns this into a real queue.
 */
@Serializable
data class SessionConstraints(
    val title: String = "Your session",
    val durationMinutes: Int = 45,
    val energyTarget: Float = 0.55f,
    val energyCurve: EnergyCurve = EnergyCurve.FLAT,
    /** 0 = only new music, 1 = only known music. */
    val familiarity: Float = 0.6f,
    val discoveryRatio: Float = 0.3f,
    val artistDiversity: Float = 0.7f,
    val moods: List<String> = emptyList(),
    val avoidMoods: List<String> = emptyList(),
    val context: String? = null,
    val seedArtists: List<String> = emptyList(),
    /** Search phrases used to find real, playable candidates. Never treated as tracks. */
    val searchQueries: List<String> = emptyList(),
    val rediscover: Boolean = false,
    val aesthetic: AestheticDescriptor = AestheticDescriptor(),
) {
    fun sanitized(): SessionConstraints = copy(
        title = title.take(60).ifBlank { "Your session" },
        durationMinutes = durationMinutes.coerceIn(5, 240),
        energyTarget = energyTarget.coerceIn(0f, 1f),
        familiarity = familiarity.coerceIn(0f, 1f),
        discoveryRatio = discoveryRatio.coerceIn(0f, 1f),
        artistDiversity = artistDiversity.coerceIn(0f, 1f),
        moods = moods.take(6).map { it.lowercase().take(24) },
        avoidMoods = avoidMoods.take(6).map { it.lowercase().take(24) },
        context = context?.take(40),
        seedArtists = seedArtists.take(5).map { it.take(60) },
        searchQueries = searchQueries.filter { it.isNotBlank() }.take(4).map { it.take(80) },
        aesthetic = aesthetic.sanitized(),
    )

    val moodSet: Set<Mood> get() = moods.mapNotNull { m -> Mood.entries.firstOrNull { it.name.equals(m, true) || it.label.equals(m, true) } }.toSet()
}
