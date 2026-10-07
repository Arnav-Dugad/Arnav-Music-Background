package com.arnav.music.domain.recommend

import com.arnav.music.domain.audio.Camelot
import com.arnav.music.domain.audio.HarmonicMix
import com.arnav.music.domain.audio.KeyNames
import com.arnav.music.domain.model.Track
import kotlin.math.abs
import kotlin.math.sqrt

/** Which part of two songs' profiles matched best — drives "Same key and tempo as …" copy. */
enum class Aspect { SAME_ARTIST, KEY_AND_TEMPO, KEY, TEMPO, GENRE, ERA, ENERGY, NONE }

/** What we know about a song's sound and metadata. Unknowns stay unknown (never guessed). */
data class ContentFeatures(
    val artistKey: String,
    val genres: Set<String>,
    val year: Int?,
    val energy: Float?,
    val bpm: Float,
    val key: Int,
    val loudnessDb: Float?,
    val channelId: String?,
) {
    val decade: Int? get() = year?.let { it / 10 * 10 }

    companion object {
        fun of(track: Track, traits: AudioTraits? = null, artistKey: String = track.artistKey): ContentFeatures = ContentFeatures(
            artistKey = artistKey,
            genres = track.genres.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet(),
            year = track.year?.takeIf { it in 1900..2100 },
            energy = traits?.energy?.takeIf { it.isFinite() } ?: track.energy,
            bpm = traits?.bpm?.takeIf { it > 0f } ?: 0f,
            key = traits?.key?.takeIf { KeyNames.isValid(it) } ?: KeyNames.UNKNOWN,
            loudnessDb = traits?.loudnessDb?.takeIf { it.isFinite() },
            channelId = track.channelId,
        )
    }
}

data class Similarity(val score: Double, val aspect: Aspect)

/**
 * Content similarity between two songs from whatever both have: artist, genres (Jaccard), era,
 * energy, tempo (half/double time counts as close), Camelot key distance and loudness. Each part is
 * weighted; parts unknown for either song are left out and the result is shrunk by how much was
 * known, so two songs we know little about never look like twins.
 */
object ContentSimilarity {
    private const val W_ARTIST = 0.22
    private const val W_GENRE = 0.24
    private const val W_ERA = 0.10
    private const val W_ENERGY = 0.14
    private const val W_TEMPO = 0.12
    private const val W_KEY = 0.12
    private const val W_LOUD = 0.03
    private const val W_CHANNEL = 0.03
    private const val W_TOTAL = W_ARTIST + W_GENRE + W_ERA + W_ENERGY + W_TEMPO + W_KEY + W_LOUD + W_CHANNEL

    fun genre(a: Set<String>, b: Set<String>): Double? {
        if (a.isEmpty() || b.isEmpty()) return null
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    fun era(a: Int?, b: Int?): Double? = if (a == null || b == null) null else (1.0 - abs(a - b) / 15.0).coerceIn(0.0, 1.0)

    fun energy(a: Float?, b: Float?): Double? = if (a == null || b == null) null else (1.0 - 2.0 * abs(a - b)).coerceIn(0.0, 1.0)

    /** 1 for the same tempo (or exactly half/double), falling to 0 around a 16 % change. */
    fun tempo(a: Float, b: Float): Double? = if (a <= 0f || b <= 0f) null else (1.0 - HarmonicMix.tempoCost(a, b) / 4.0).coerceIn(0.0, 1.0)

    /** 1 for the same key, 0.85 for a Camelot-compatible move, less the further round the wheel. */
    fun key(a: Int, b: Int): Double? {
        if (!KeyNames.isValid(a) || !KeyNames.isValid(b)) return null
        if (a == b) return 1.0
        if (Camelot.compatible(a, b)) return 0.85
        return (1.0 - HarmonicMix.keyCost(a, b) / 4.5).coerceIn(0.0, 1.0)
    }

    fun similarity(a: ContentFeatures, b: ContentFeatures): Similarity {
        var sum = 0.0
        var known = 0.0
        var bestAspect = Aspect.NONE
        var bestContribution = 0.0
        fun part(w: Double, s: Double?, aspect: Aspect) {
            if (s == null) return
            sum += w * s; known += w
            if (aspect != Aspect.NONE && w * s > bestContribution) { bestContribution = w * s; bestAspect = aspect }
        }
        part(W_ARTIST, if (a.artistKey.isNotEmpty() && a.artistKey == b.artistKey) 1.0 else 0.0, Aspect.SAME_ARTIST)
        part(W_GENRE, genre(a.genres, b.genres), Aspect.GENRE)
        part(W_ERA, era(a.year, b.year), Aspect.ERA)
        part(W_ENERGY, energy(a.energy, b.energy), Aspect.ENERGY)
        val t = tempo(a.bpm, b.bpm)
        val k = key(a.key, b.key)
        part(W_TEMPO, t, Aspect.TEMPO)
        part(W_KEY, k, Aspect.KEY)
        if (a.loudnessDb != null && b.loudnessDb != null) part(W_LOUD, (1.0 - abs(a.loudnessDb - b.loudnessDb) / 12.0).coerceIn(0.0, 1.0), Aspect.NONE)
        if (a.channelId != null && b.channelId != null) part(W_CHANNEL, if (a.channelId == b.channelId) 1.0 else 0.0, Aspect.NONE)
        if (known <= 0) return Similarity(0.0, Aspect.NONE)
        if (t != null && k != null && t >= 0.8 && k >= 0.85 && a.artistKey != b.artistKey) bestAspect = Aspect.KEY_AND_TEMPO
        val score = (sum / known) * sqrt(known / W_TOTAL)
        return Similarity(score, bestAspect)
    }
}
