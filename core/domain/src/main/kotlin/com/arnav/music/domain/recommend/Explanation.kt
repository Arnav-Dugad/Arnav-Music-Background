package com.arnav.music.domain.recommend

import com.arnav.music.domain.model.TrackId
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

enum class ExplanationKind {
    SESSION_FOLLOWS, CO_PLAYED, CONTEXT_ARTIST, SOUND_ALIKE, SAME_ARTIST, RELATED_ARTIST, PLAYLIST, LIKED,
    REDISCOVER, NEW_FROM_ARTIST, NEW_DISCOVERY, SEARCH, SEED, TASTE_GENRE, HEAVY_ROTATION, FOR_YOU,
}

/** A short, honest reason. [anchor] is the song the reason points at, when there is one. */
data class Explanation(val kind: ExplanationKind, val text: String, val anchor: TrackId? = null)

/**
 * Builds the copy. Every function states only what the evidence passed in shows — the caller
 * decides which evidence is strongest (see [RecEngine]).
 */
object Explain {
    fun follows(title: String, anchor: TrackId) = Explanation(ExplanationKind.SESSION_FOLLOWS, "Often follows $title in your sessions", anchor)
    fun coPlayed(title: String, anchor: TrackId) = Explanation(ExplanationKind.CO_PLAYED, "Often played with $title", anchor)
    fun context(artist: String, ctx: ListeningContext) = Explanation(ExplanationKind.CONTEXT_ARTIST, "Because you play $artist on ${ctx.label}")
    fun sameArtist(artist: String, top: Boolean) = Explanation(ExplanationKind.SAME_ARTIST, if (top) "More from $artist, one of your most played" else "More from $artist")
    fun relatedArtist(other: String) = Explanation(ExplanationKind.RELATED_ARTIST, "Often played alongside $other in your sessions")
    fun playlist(name: String) = Explanation(ExplanationKind.PLAYLIST, "From your playlist “$name”")
    fun liked() = Explanation(ExplanationKind.LIKED, "From your liked songs")
    fun search(query: String) = Explanation(ExplanationKind.SEARCH, "Because you searched for “${query.take(40)}”")
    fun seed(artist: String) = Explanation(ExplanationKind.SEED, "Because you picked $artist when you started")
    fun genre(genre: String) = Explanation(ExplanationKind.TASTE_GENRE, "Close to the ${genre.lowercase()} you play most")
    fun heavyRotation() = Explanation(ExplanationKind.HEAVY_ROTATION, "In your heavy rotation this week")
    fun forYou() = Explanation(ExplanationKind.FOR_YOU, "Picked from your listening")
    fun newFromArtist(artist: String) = Explanation(ExplanationKind.NEW_FROM_ARTIST, "New to you · by $artist")
    fun newInGenre(genre: String) = Explanation(ExplanationKind.NEW_DISCOVERY, "New to you · close to the ${genre.lowercase()} you play most")
    fun newNear(artist: String?) = Explanation(ExplanationKind.NEW_DISCOVERY, if (artist != null) "New to you · close to $artist" else "New to you")

    /** "Same key and tempo as …", "Harmonically close to …" — only the aspect that was actually measured. */
    fun soundAlike(aspect: Aspect, title: String, artist: String, sameKey: Boolean, anchor: TrackId): Explanation {
        val text = when (aspect) {
            Aspect.KEY_AND_TEMPO -> if (sameKey) "Same key and tempo as $title" else "Compatible key and tempo with $title"
            Aspect.KEY -> if (sameKey) "Same key as $title" else "Harmonically close to $title"
            Aspect.TEMPO -> "Same tempo as $title"
            Aspect.GENRE -> "Same style as $title"
            Aspect.ERA -> "From the same era as $title"
            Aspect.ENERGY -> "Similar energy to $title"
            Aspect.SAME_ARTIST -> "Also by $artist"
            Aspect.NONE -> "Sounds like $title"
        }
        return Explanation(ExplanationKind.SOUND_ALIKE, text, anchor)
    }

    /** "You played this 14 times in March — not since" / "Liked in March 2025, quiet lately". */
    fun rediscover(peakPlays: Int, peakMonthStart: Long?, likedAt: Long?, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): Explanation {
        fun month(at: Long): String {
            val d = Instant.ofEpochMilli(at).atZone(zone)
            val sameYear = d.year == Instant.ofEpochMilli(now).atZone(zone).year
            val m = d.month.getDisplayName(TextStyle.FULL, locale)
            return if (sameYear) m else "$m ${d.year}"
        }
        val text = when {
            peakMonthStart != null && peakPlays >= 2 -> "You played this $peakPlays times in ${month(peakMonthStart)} — not lately"
            likedAt != null -> "Liked in ${month(likedAt)}, quiet lately"
            peakMonthStart != null -> "A favourite from ${month(peakMonthStart)}"
            else -> "Loved before, quiet lately"
        }
        return Explanation(ExplanationKind.REDISCOVER, text)
    }
}
