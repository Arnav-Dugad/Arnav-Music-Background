package com.arnav.music.domain.recommend

import com.arnav.music.domain.catalog.TrackClassifier
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import java.time.ZoneId

/**
 * Where a recommendation came from. Each source is one "arm" of the [SourceBandit]: the blend of
 * sources adapts to what this listener actually finishes and skips.
 */
enum class RecSource {
    /** Neighbours of the songs in the current/last listening session. */
    SESSION,
    /** Item–item co-occurrence and transitions learned from all past sessions. */
    COOCCURRENCE,
    /** Personalised PageRank over the track–artist–genre–playlist graph. */
    GRAPH,
    /** Same or related artists. */
    ARTIST,
    /** Audio/metadata similarity (genre, era, energy, tempo, key). */
    CONTENT,
    /** What this listener plays at this time of day / day of week. */
    CONTEXT,
    /** Loved long ago, quiet lately. */
    REDISCOVER,
    /** Never played: cached search pages, unplayed local files, quota-guarded fresh finds. */
    DISCOVERY,
    /** Recent searches. */
    INTENT,
    /** Onboarding picks (cold start). */
    SEED,
}

/** On-device audio analysis of a song (local files only). Unknown values: bpm ≤ 0, key −1, NaN. */
data class AudioTraits(
    val bpm: Float = 0f,
    val key: Int = -1,
    val energy: Float = Float.NaN,
    val loudnessDb: Float = Float.NaN,
)

/** One playlist the listener keeps. User-made playlists are a stronger taste signal than imports. */
data class PlaylistSignal(
    val id: String,
    val name: String,
    val userMade: Boolean,
    /** Track ids with the time each was added. */
    val tracks: List<Pair<TrackId, Long>>,
)

/** A recent search, read as intent ("I want to hear this now"). */
data class SearchIntent(val query: String, val at: Long)

/** Stored explicit feedback, exactly as kept in `rec_feedback`. */
data class FeedbackRow(val subject: String, val kind: String, val createdAt: Long)

object FeedbackKind {
    const val NOT_INTERESTED = "track_not_interested"
    const val ARTIST_BLOCKED = "artist_blocked"
    const val MORE_LIKE_THIS = "track_more_like_this"
}

/** Explicit negative/positive feedback; negative feedback is a hard filter everywhere. */
data class Feedback(
    val notInterested: Set<TrackId> = emptySet(),
    val blockedArtists: Set<String> = emptySet(),
    val moreLikeThis: Map<TrackId, Long> = emptyMap(),
) {
    fun allows(track: Track): Boolean = allows(track.id, track.artistKey)
    fun allows(id: TrackId, artistKey: String): Boolean = id !in notInterested && artistKey !in blockedArtists

    companion object {
        val None = Feedback()

        fun from(rows: List<FeedbackRow>): Feedback {
            val ni = HashSet<TrackId>()
            val blocked = HashSet<String>()
            val more = HashMap<TrackId, Long>()
            for (r in rows) when (r.kind) {
                FeedbackKind.NOT_INTERESTED -> ni += TrackId(r.subject)
                FeedbackKind.ARTIST_BLOCKED -> blocked += r.subject
                FeedbackKind.MORE_LIKE_THIS -> more[TrackId(r.subject)] = r.createdAt
            }
            return Feedback(ni, blocked, more)
        }
    }
}

/** Onboarding answers, used while there's little listening history. */
data class ColdStart(val seedArtists: List<String> = emptyList(), val moods: Set<String> = emptySet()) {
    val artistKeys: Set<String> get() = seedArtists.map { ArtistKey.of(it) }.filter { it.isNotBlank() }.toSet()
}

/** Everything the recommender learns from. All of it is local; nothing leaves the device. */
data class RecInput(
    val events: List<PlayEvent>,
    /** Metadata for every track mentioned anywhere (events, likes, playlists, candidate pool). */
    val tracks: Map<TrackId, Track>,
    /** Liked track → when it was liked. */
    val likes: Map<TrackId, Long> = emptyMap(),
    val playlists: List<PlaylistSignal> = emptyList(),
    val searches: List<SearchIntent> = emptyList(),
    val traits: Map<TrackId, AudioTraits> = emptyMap(),
    val feedback: Feedback = Feedback.None,
    val coldStart: ColdStart = ColdStart(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** A recommended track with the honest reason it was picked. */
data class Recommendation(
    val track: Track,
    val score: Double,
    /** The arm credited for this pick (for the bandit). */
    val source: RecSource,
    val explanation: Explanation,
)

/** A named taste cluster ("Daily Mix · Arijit Singh, Pritam"). */
data class DailyMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Recommendation>,
    val topArtists: List<String>,
)

/** Memoised [ArtistKey.of] (a few regexes per call) — most songs share a handful of artist strings. */
class ArtistKeyCache {
    private val cache = HashMap<String, String>()
    fun of(raw: String): String = cache.getOrPut(raw) { ArtistKey.of(raw) }
}

/**
 * Memoised "is this a real single?" ([com.arnav.music.domain.catalog.isSingle] runs ~30 title
 * regexes). Keep one instance across refreshes; entries are keyed by id + title + length.
 */
class SinglesCache(private val maxSize: Int = 60_000) {
    private class Entry(val title: String, val album: String?, val durationMs: Long?, val compilation: Boolean, val single: Boolean)
    private val cache = HashMap<TrackId, Entry>()

    @Synchronized
    fun isSingle(t: Track): Boolean {
        val e = cache[t.id]
        if (e != null && e.title == t.title && e.album == t.album && e.durationMs == t.durationMs && e.compilation == t.compilation) return e.single
        if (cache.size >= maxSize) cache.clear()
        val v = !t.compilation && TrackClassifier.isSingle(listOfNotNull(t.title, t.album).joinToString(" "), t.durationMs)
        cache[t.id] = Entry(t.title, t.album, t.durationMs, t.compilation, v)
        return v
    }
}
