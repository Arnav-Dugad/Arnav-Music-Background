package com.arnav.music.core.recommend

import android.content.Context
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.RecFeedbackEntity
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.recommend.AudioTraits
import com.arnav.music.domain.recommend.ColdStart
import com.arnav.music.domain.recommend.DailyMix
import com.arnav.music.domain.recommend.DiscoveryLedger
import com.arnav.music.domain.recommend.DiscoveryPolicy
import com.arnav.music.domain.recommend.Feedback
import com.arnav.music.domain.recommend.FeedbackKind
import com.arnav.music.domain.recommend.FeedbackRow
import com.arnav.music.domain.recommend.GraphCache
import com.arnav.music.domain.recommend.Impression
import com.arnav.music.domain.recommend.PlaylistSignal
import com.arnav.music.domain.recommend.RecEngine
import com.arnav.music.domain.recommend.RecInput
import com.arnav.music.domain.recommend.RecModel
import com.arnav.music.domain.recommend.Recommendation
import com.arnav.music.domain.recommend.RewardModel
import com.arnav.music.domain.recommend.SearchIntent
import com.arnav.music.domain.recommend.SessionIndex
import com.arnav.music.domain.recommend.SinglesCache
import com.arnav.music.domain.recommend.SourceBandit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.random.Random

/**
 * On-device recommender: loads every local signal (plays, likes, playlists, searches, on-device
 * audio analysis, explicit feedback, onboarding picks), keeps the domain models warm between
 * refreshes (incremental session index, cached walk graph, cached single/compilation checks),
 * adapts the source blend with a Thompson-sampling bandit, and spends at most a couple of YouTube
 * searches a day on "fresh finds" — never unless quota is NORMAL.
 *
 * All work runs off the main thread; nothing leaves the device.
 */
class RecommendationService(
    context: Context,
    private val db: ArnavDatabase,
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("recommender", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val compute = Mutex()
    private val singles = SinglesCache()
    private var graphs = GraphCache()
    private var index = SessionIndex()
    /** Identity of the history [index] was fed: first event time, local day. */
    private var indexKey: Pair<Long?, String>? = null
    private var engine: Pair<Long, RecEngine>? = null
    @Volatile private var pool: Pair<Int, List<Track>>? = null
    private var mixes: Pair<String, List<DailyMix>>? = null
    @Volatile private var stale = true
    @Volatile private var discovering = false

    private val _feedback = MutableStateFlow(0)
    /** Bumps whenever explicit feedback changes, so screens can refresh. */
    val feedbackVersion: StateFlow<Int> = _feedback.asStateFlow()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Marks the model stale (new plays, likes, playlists…); the next request rebuilds it. */
    fun invalidate() { stale = true }

    // ------------------------------------------------------------------ surfaces

    suspend fun forYouNow(limit: Int = 20): List<Recommendation> = onEngine { it.forYouNow(limit).also { r -> log(r, "home_now") } }

    suspend fun freshFinds(limit: Int = 20): List<Recommendation> = onEngine { e ->
        maybeDiscover(e.model)
        e.freshFinds(limit).also { log(it, "home_fresh") }
    }

    suspend fun rediscover(limit: Int = 20): List<Recommendation> = onEngine { it.rediscover(limit).also { r -> log(r, "home_rediscover") } }

    /** Daily mixes, recomputed at most every few hours (or when new sessions or feedback arrive). */
    suspend fun dailyMixes(): List<DailyMix> = onEngine { e ->
        val key = "${clock.now() / (4 * HOUR)}|${e.model.index.committedEvents}|${_feedback.value}"
        mixes?.takeIf { it.first == key }?.second ?: e.dailyMixes().also { mixes = key to it }
    }

    suspend fun radio(seed: Track, limit: Int = 25): List<Recommendation> = onEngine { it.radio(seed, limit).also { r -> log(r, "radio") } }

    suspend fun artistRadio(artistKey: String, limit: Int = 25): List<Recommendation> =
        onEngine { it.artistRadio(artistKey, limit).also { r -> log(r, "radio") } }

    /**
     * What endless radio should append after [recent]. [localOnly] while the app is in the
     * background: YouTube may only play in the visible in-app player, so only songs on this device.
     */
    suspend fun continuation(recent: List<Track>, exclude: Set<TrackId>, localOnly: Boolean, limit: Int = 10): List<Recommendation> =
        onEngine(force = true) { e ->
            val wide = e.continuation(recent, if (localOnly) limit * 4 else limit, exclude)
            (if (localOnly) wide.filter { it.track.source == SourceType.LOCAL }.take(limit) else wide).also { log(it, SURFACE_ENDLESS) }
        }

    /** Runs [block] on the current engine, off the main thread, one at a time (the engine memoises internally). */
    private suspend fun <T> onEngine(force: Boolean = false, block: (RecEngine) -> T): T {
        val e = engine(force)
        return compute.withLock { withContext(Dispatchers.Default) { block(e) } }
    }

    // ------------------------------------------------------------------ feedback

    suspend fun notInterested(track: Track) = saveFeedback(track.id.value, FeedbackKind.NOT_INTERESTED)
    suspend fun blockArtist(artistKey: String) = saveFeedback(artistKey, FeedbackKind.ARTIST_BLOCKED)
    suspend fun moreLikeThis(track: Track) = saveFeedback(track.id.value, FeedbackKind.MORE_LIKE_THIS)

    /** Removes feedback about a track id or artist key ("Undo"). */
    suspend fun clearFeedback(subject: String) {
        db.recFeedback().delete(subject)
        changed()
    }

    /** Forgets what the recommender learned: explicit feedback, the source bandit and pending impressions. */
    suspend fun resetLearning() {
        for (row in db.recFeedback().all()) db.recFeedback().delete(row.subject)
        prefs.edit().putString(KEY_BANDIT, null).putString(KEY_IMPRESSIONS, null).apply()
        changed()
    }

    /** Current explicit feedback, for filtering lists built outside the recommender (trending, sessions). */
    suspend fun feedback(): Feedback = Feedback.from(db.recFeedback().all().map { FeedbackRow(it.subject, it.kind, it.createdAt) })

    private suspend fun saveFeedback(subject: String, kind: String) {
        db.recFeedback().upsert(RecFeedbackEntity(subject, kind, clock.now()))
        changed()
    }

    private fun changed() {
        stale = true
        mixes = null
        _feedback.value = _feedback.value + 1
    }

    // ------------------------------------------------------------------ model

    private suspend fun engine(force: Boolean = false): RecEngine = lock.withLock {
        val now = clock.now()
        engine?.let { (at, e) -> if (!stale && !force && now - at < MAX_AGE_MS) return@withLock e }
        val input = withContext(Dispatchers.IO) { load(now) }
        val built = withContext(Dispatchers.Default) {
            val first = input.events.firstOrNull()?.startedAt
            val key = first to DiscoveryPolicy.dayKey(now, zone)
            // History cleared, trimmed or a new day (the 1-year window slid): rebuild the index from scratch.
            if (key != indexKey || input.events.size < index.committedEvents) {
                index = SessionIndex(); graphs = GraphCache(); indexKey = key
            }
            val model = RecModel.build(input, now, index)
            val bandit = settleBandit(input.events, now)
            // One Thompson draw per 3-hour window keeps shelves stable while still exploring.
            val multipliers = bandit.multipliers(Random(now / (3 * HOUR)))
            val prefer = if (settings.settings.value.preferVideos) MediaVariant.VIDEO else MediaVariant.SONG
            RecEngine(model, input.tracks.values, multipliers, prefer, singles, graphs)
        }
        engine = now to built
        stale = false
        built
    }

    private suspend fun load(now: Long): RecInput {
        val events = library.events(now - 365L * DAY)
        // Known catalogue: every YouTube track seen (search pages, imports, plays) plus songs on this phone.
        val trackCount = db.tracks().count()
        val local = library.localTracksSnapshot()
        val poolKey = trackCount * 31 + local.size
        val catalogue = pool?.takeIf { it.first == poolKey }?.second
            ?: (db.tracks().all().map { it.toDomain() } + local).distinctBy { it.id }.also { pool = poolKey to it }
        val tracks = HashMap<TrackId, Track>(catalogue.size * 2)
        for (t in catalogue) tracks[t.id] = t
        val missing = events.map { it.trackId }.distinct().filter { it !in tracks }
        if (missing.isNotEmpty()) for (t in library.tracks(missing)) tracks[t.id] = t

        val likes = db.likes().all().filter { !it.deleted }.associate { TrackId(it.trackId) to it.likedAt }
        val imported = db.importHistory().observe().first().filter { !it.undone }
            .flatMap { it.playlistIds.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val playlistRows = db.playlists().all().filter { !it.deleted }.associateBy { it.id }
        val playlists = db.playlists().activeEntries().groupBy { it.playlistId }.mapNotNull { (id, rows) ->
            val p = playlistRows[id] ?: return@mapNotNull null
            val userMade = id !in imported && !id.startsWith("ytimp_")
            PlaylistSignal(id, p.name, userMade, rows.sortedBy { it.position }.map { TrackId(it.trackId) to it.addedAt })
        }
        val searches = db.search().recent(20).first().map { SearchIntent(it.display, it.searchedAt) }
        val feedback = feedback()
        val s = settings.settings.value
        return RecInput(
            events = events, tracks = tracks, likes = likes, playlists = playlists, searches = searches,
            traits = traits(), feedback = feedback, coldStart = ColdStart(s.seedArtists, s.selectedMoods), zone = zone,
        )
    }

    /** bpm, key, energy and loudness of analysed on-device songs (one light query, no envelope blobs). */
    private fun traits(): Map<TrackId, AudioTraits> = runCatching<Map<TrackId, AudioTraits>> {
        val out = HashMap<TrackId, AudioTraits>()
        db.openHelper.readableDatabase.query("SELECT trackId, bpm, musicalKey, energy, loudnessDb FROM audio_features WHERE ok = 1").use { c ->
            while (c.moveToNext()) {
                out[TrackId(c.getString(0))] = AudioTraits(c.getFloat(1), c.getInt(2), c.getFloat(3), c.getFloat(4))
            }
        }
        out
    }.onFailure { Log.w("Recommender: audio features unavailable", it) }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------ bandit

    private fun settleBandit(events: List<PlayEvent>, now: Long): SourceBandit {
        val bandit = SourceBandit.decode(prefs.getString(KEY_BANDIT, null))
        val impressions = readImpressions()
        if (impressions.isEmpty()) return bandit
        val settled = RewardModel.settle(impressions, events, now, penaliseUnplayed = setOf(SURFACE_ENDLESS))
        if (settled.outcomes.isEmpty() && settled.pending.size == impressions.size) return bandit
        val updated = bandit.updateAll(settled.outcomes)
        prefs.edit()
            .putString(KEY_BANDIT, updated.encode())
            .putString(KEY_IMPRESSIONS, settled.pending.joinToString("\n") { it.encode() })
            .apply()
        return updated
    }

    @Synchronized
    private fun log(recs: List<Recommendation>, surface: String) {
        if (recs.isEmpty()) return
        val now = clock.now()
        val existing = readImpressions()
        val seen = existing.map { it.trackId }.toHashSet()
        // Credit only the first time a song is served; keep the log bounded.
        val fresh = recs.filter { it.track.id !in seen }.map { Impression(it.track.id, it.source, now, surface) }
        if (fresh.isEmpty()) return
        val all = (existing + fresh).takeLast(MAX_IMPRESSIONS)
        prefs.edit().putString(KEY_IMPRESSIONS, all.joinToString("\n") { it.encode() }).apply()
    }

    private fun readImpressions(): List<Impression> =
        prefs.getString(KEY_IMPRESSIONS, null)?.lineSequence()?.mapNotNull { Impression.decode(it) }?.toList().orEmpty()

    // ------------------------------------------------------------------ fresh finds (quota-guarded)

    /**
     * Cache-first discovery: cached search pages are free and always used; a real search (100 units)
     * runs in the background at most [DiscoveryPolicy.MAX_PER_DAY] times a day, spaced out, only
     * while quota is NORMAL and online. Results land in Room and join the pool on the next refresh.
     */
    private fun maybeDiscover(model: RecModel) {
        if (discovering) return
        val queries = DiscoveryPolicy.queries(model)
        if (queries.isEmpty()) return
        discovering = true
        scope.launch(Dispatchers.IO) {
            try {
                var found = false
                for (q in queries) {
                    if (youtube.cached(q, SearchFilter.TRACKS) != null) continue
                    val ledger = DiscoveryLedger.decode(prefs.getString(KEY_DISCOVERY, null))
                    if (!DiscoveryPolicy.mayQuery(ledger, youtube.quotaState(), network.currentlyOnline(), clock.now(), zone)) break
                    val result = youtube.search(q, SearchFilter.TRACKS).getOrNull()
                    if (result != null && !result.fromCache) {
                        prefs.edit().putString(KEY_DISCOVERY, DiscoveryPolicy.record(ledger, clock.now(), zone).encode()).apply()
                        found = result.tracks.isNotEmpty()
                    }
                    break // one search per refresh at most
                }
                if (found) { pool = null; stale = true }
            } catch (e: Exception) {
                Log.w("Recommender: discovery failed", e)
            } finally {
                discovering = false
            }
        }
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val HOUR = 3_600_000L
        private const val MAX_AGE_MS = 10 * 60_000L
        private const val MAX_IMPRESSIONS = 400
        private const val KEY_BANDIT = "bandit_v1"
        private const val KEY_IMPRESSIONS = "impressions_v1"
        private const val KEY_DISCOVERY = "discovery_ledger_v1"
        const val SURFACE_ENDLESS = "radio_endless"
    }
}
