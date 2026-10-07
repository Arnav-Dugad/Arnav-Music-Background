package com.arnav.music.domain.recommend

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import java.time.Instant
import java.time.YearMonth

/**
 * Track and artist co-occurrence, maintained incrementally: each refresh only feeds sessions that
 * closed since the last one. The still-open session is never committed — it is the "right now"
 * context instead, and joins the index once 30 minutes of silence close it.
 */
class SessionIndex(window: Int = 4) {
    val tracks = CoOccurrence<TrackId>(window)
    val artists = CoOccurrence<String>(window)
    /** Start time of the last committed play. */
    var committedUntil = Long.MIN_VALUE
        private set
    var committedEvents = 0
        private set

    /**
     * Commits closed sessions found among [events] after [committedUntil] and returns the open
     * session (plays still within 30 minutes of [now]), if any.
     */
    fun update(events: List<PlayEvent>, now: Long): ListeningSession? {
        val fresh = events.filter { it.startedAt > committedUntil }
        if (fresh.isEmpty()) return null
        val sessions = Sessionizer.split(fresh)
        for ((i, s) in sessions.withIndex()) {
            if (i == sessions.lastIndex && Sessionizer.isOpen(s, now)) return s
            commit(s)
        }
        return null
    }

    private fun commit(s: ListeningSession) {
        val kept = s.events.filter { !Engagement.isEarlySkip(it) }
        tracks.addSession(kept.map { it.trackId })
        val artistSeq = ArrayList<String>()
        for (e in kept) if (artistSeq.lastOrNull() != e.artistKey) artistSeq += e.artistKey
        artists.addSession(artistSeq)
        committedUntil = maxOf(committedUntil, s.events.last().startedAt)
        committedEvents += s.events.size
    }
}

/**
 * Everything learned from [input] at time [now]: the taste model, session co-occurrence and the
 * listener's current/last session. Cheap to query; build once per refresh and share across surfaces.
 */
class RecModel private constructor(
    val input: RecInput,
    val now: Long,
    val taste: TasteModel,
    val index: SessionIndex,
    /** The session in progress (last play less than 30 min ago). */
    val openSession: ListeningSession?,
    /** The most recent session, open or not. */
    val lastSession: ListeningSession?,
    val artistKeys: ArtistKeyCache,
) {
    val context: ListeningContext = ListeningContext.at(now, input.zone)
    private val featureCache = HashMap<TrackId, ContentFeatures>()
    private val names = HashMap<String, String>()

    val eventsByTrack: Map<TrackId, List<PlayEvent>> by lazy { input.events.groupBy { it.trackId } }

    fun features(t: Track): ContentFeatures = featureCache.getOrPut(t.id) {
        val key = artistKeys.of(t.artist)
        names.putIfAbsent(key, t.artist)
        ContentFeatures.of(t, input.traits[t.id], key)
    }

    /** [Track.artistKey], memoised. */
    fun artistKey(t: Track): String = features(t).artistKey

    fun similarity(a: Track, b: Track): Double = ContentSimilarity.similarity(features(a), features(b)).score

    fun track(id: TrackId): Track? = input.tracks[id]

    fun artistName(key: String): String = taste.artistNames[key] ?: names[key] ?: key

    /**
     * Seeds from the current session (or the last one, at lower weight, when it ended in the past
     * 6 hours): the most recent kept plays, newest first, with geometric weights.
     */
    fun sessionSeeds(max: Int = 6): List<Pair<TrackId, Double>> {
        val s = openSession ?: lastSession?.takeIf { now - it.end <= 6 * 3_600_000L } ?: return emptyList()
        val factor = if (s === openSession) 1.0 else 0.6
        val kept = s.events.filter { !Engagement.isEarlySkip(it) }.asReversed().map { it.trackId }.distinct().take(max)
        return kept.mapIndexed { i, id -> id to factor * Math.pow(0.8, i.toDouble()) }
    }

    /** Most-played calendar month of a track (start of month, plays) for rediscovery copy. */
    fun peakMonth(id: TrackId): Pair<Long, Int>? {
        val evs = eventsByTrack[id]?.filter { !Engagement.isEarlySkip(it) } ?: return null
        if (evs.isEmpty()) return null
        val byMonth = evs.groupingBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(input.zone)) }.eachCount()
        val (ym, n) = byMonth.entries.sortedWith(compareByDescending<Map.Entry<YearMonth, Int>> { it.value }.thenByDescending { it.key }).first()
        return ym.atDay(1).atStartOfDay(input.zone).toInstant().toEpochMilli() to n
    }

    companion object {
        /**
         * Builds the model. Pass the previous refresh's [index] to update co-occurrence incrementally;
         * it must have been fed the same history (start a new one after history is cleared).
         */
        fun build(input: RecInput, now: Long, index: SessionIndex = SessionIndex()): RecModel {
            val ev = input.events
            val sorted = if ((1 until ev.size).all { ev[it - 1].startedAt <= ev[it].startedAt }) ev else ev.sortedBy { it.startedAt }
            val sortedInput = if (sorted === input.events) input else input.copy(events = sorted)
            val open = index.update(sorted, now)
            val keys = ArtistKeyCache()
            val taste = TasteModelBuilder.build(sortedInput, now, keys)
            val last = open ?: sorted.lastOrNull()?.let { lastEvent ->
                // Re-derive the last closed session from the tail of history (cheap: walk back to a gap).
                var i = sorted.lastIndex
                var start = lastEvent.startedAt
                while (i > 0 && start - (sorted[i - 1].startedAt + sorted[i - 1].listenedMs) <= Sessionizer.GAP_MS) { i--; start = sorted[i].startedAt }
                ListeningSession(sorted.subList(i, sorted.size).toList())
            }
            return RecModel(sortedInput, now, taste, index, open, last, keys)
        }
    }
}
