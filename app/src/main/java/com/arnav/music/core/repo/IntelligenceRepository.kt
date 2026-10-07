package com.arnav.music.core.repo

import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.ai.AiOutcome
import com.arnav.music.core.ai.AiUnavailableReason
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.recommend.RecommendationService
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.ai.AiJson
import com.arnav.music.domain.catalog.isSingle
import com.arnav.music.domain.ai.PromptLibrary
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.intelligence.BuiltSession
import com.arnav.music.domain.intelligence.ConstellationBuilder
import com.arnav.music.domain.intelligence.ConstellationGraph
import com.arnav.music.domain.intelligence.InsightsEngine
import com.arnav.music.domain.intelligence.LocalIntentEngine
import com.arnav.music.domain.intelligence.Reason
import com.arnav.music.domain.intelligence.Recap
import com.arnav.music.domain.intelligence.RecapPeriod
import com.arnav.music.domain.intelligence.Recommender
import com.arnav.music.domain.intelligence.SessionBuilder
import com.arnav.music.domain.intelligence.SessionConstraints
import com.arnav.music.domain.intelligence.SmartPlaylist
import com.arnav.music.domain.intelligence.SmartPlaylistEngine
import com.arnav.music.domain.intelligence.TasteDna
import com.arnav.music.domain.intelligence.TasteProfile
import com.arnav.music.domain.intelligence.TasteProfileBuilder
import com.arnav.music.domain.intelligence.TimeMachineInsight
import com.arnav.music.domain.model.Moment
import com.arnav.music.domain.model.Moments
import com.arnav.music.domain.model.Mood
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.recommend.DailyMix
import com.arnav.music.domain.recommend.Feedback
import com.arnav.music.domain.recommend.ListeningContext
import com.arnav.music.domain.recommend.Recommendation
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** A home section. The composer decides which few to show, and in what order. */
sealed interface HomeSection {
    val key: String
    data class ContinueListening(val tracks: List<Track>) : HomeSection { override val key = "continue" }
    data class MomentsRow(val moments: List<Moment>, val featured: Moment) : HomeSection { override val key = "moments" }
    data class MadeForYou(val mixes: List<SmartMix>) : HomeSection { override val key = "made" }
    /** [captions]: one honest line per track ("Often follows Kesariya in your sessions"), shown under the artist. */
    data class TrackShelf(
        override val key: String,
        val title: String,
        val subtitle: String?,
        val tracks: List<Track>,
        val reasons: Map<TrackId, Reason> = emptyMap(),
        val captions: Map<TrackId, String> = emptyMap(),
    ) : HomeSection
    /** "Daily Mix · Arijit Singh, Pritam" — taste clusters, each a familiar core with a few close new songs. */
    data class DailyMixes(val mixes: List<DailyMix>) : HomeSection { override val key = "daily" }
    data class TimeMachine(val insight: TimeMachineInsight, val tracks: List<Track>) : HomeSection { override val key = "tm_${insight.kind}" }
    data class StartHere(val moods: List<Mood>) : HomeSection { override val key = "start" }
}

data class SmartMix(val kind: SmartPlaylist, val tracks: List<Track>) {
    val artwork: List<String> get() = tracks.mapNotNull { it.artworkUrl }.distinct().take(4)
}

data class SessionResult(
    val session: BuiltSession,
    val usedAi: Boolean,
    val aiUnavailable: AiUnavailableReason?,
    val explanation: String?,
    val searchedRemotely: Int,
)

class IntelligenceRepository(
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
    private val ai: AiGateway,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
) {
    private val recommender = Recommender()
    private val builder = SessionBuilder(recommender)
    private val profileLock = Mutex()
    private var cachedProfile: Pair<Long, TasteProfile>? = null
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** The on-device recommender (signals → models → candidates → bandit-weighted scoring → re-ranking). */
    val recommendations: RecommendationService by lazy {
        val koin = GlobalContext.get()
        RecommendationService(koin.get<Context>(), koin.get<ArnavDatabase>(), library, youtube, settings, network, clock, koin.get<CoroutineScope>())
    }

    /** Bumps when explicit recommender feedback changes. */
    val feedbackVersion: StateFlow<Int> get() = recommendations.feedbackVersion

    suspend fun profile(force: Boolean = false): TasteProfile = profileLock.withLock {
        val now = clock.now()
        cachedProfile?.let { (at, p) -> if (!force && now - at < 60_000) return@withLock p }
        val events = library.events(now - 365L * DAY)
        val ids = (events.map { it.trackId } + library.likedIds.value).distinct()
        val tracks = library.tracks(ids).associateBy { it.id }
        val p = TasteProfileBuilder.build(events, tracks, library.likedIds.value, now, zone)
        cachedProfile = now to p
        p
    }

    suspend fun smartMix(kind: SmartPlaylist): SmartMix {
        val now = clock.now()
        val ids = SmartPlaylistEngine.compute(kind, library.events(now - 2 * 365L * DAY), library.likedIds.value, now, zone)
        return SmartMix(kind, library.tracks(ids))
    }

    suspend fun smartMixes(): List<SmartMix> {
        val now = clock.now()
        val events = library.events(now - 2 * 365L * DAY)
        val liked = library.likedIds.value
        return SmartPlaylist.entries.map { kind -> kind to SmartPlaylistEngine.compute(kind, events, liked, now, zone) }
            .filter { it.second.size >= 3 }
            .map { (kind, ids) -> SmartMix(kind, library.tracks(ids)) }
            .filter { it.tracks.size >= 3 }
    }

    /** Composes a personal, prioritised home (5–8 sections, never a wall of rows). */
    suspend fun composeHome(): List<HomeSection> = coroutineScope {
        val now = clock.now()
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        val profileD = async { profile() }
        val mixesD = async { smartMixes() }
        val recentD = async { library.events(now - 60L * DAY) }
        val profile = profileD.await()
        val mixes = mixesD.await()
        val recentEvents = recentD.await()
        val out = ArrayList<HomeSection>()

        val recentIds = recentEvents.sortedByDescending { it.startedAt }.map { it.trackId }.distinct().take(12)
        val recent = library.tracks(recentIds)
        if (recent.isNotEmpty()) out += HomeSection.ContinueListening(recent)

        val featured = featuredMoment(hour, settings.settings.value.selectedMoods)
        out += HomeSection.MomentsRow(listOf(featured) + Moments.all.filter { it.id != featured.id }, featured)

        if (mixes.isNotEmpty()) out += HomeSection.MadeForYou(mixes.take(6))

        if (profile.isCold) {
            val moods = settings.settings.value.selectedMoods.mapNotNull { m -> Mood.entries.firstOrNull { it.name == m } }
            out += HomeSection.StartHere(moods.ifEmpty { listOf(Mood.UPBEAT, Mood.CHILL, Mood.FOCUS, Mood.ENERGETIC) })
        }

        // Recommender shelves: each item carries an honest one-line reason.
        val showWhy = settings.settings.value.explanations
        fun captions(recs: List<Recommendation>): Map<TrackId, String> =
            if (showWhy) recs.associate { it.track.id to it.explanation.text } else emptyMap()
        val ctx = ListeningContext.at(now, zone)
        runCatching { recommendations.forYouNow(16) }.getOrNull()?.takeIf { it.size >= 4 }?.let { recs ->
            out += HomeSection.TrackShelf("foryou", "For you right now", "Picked for ${ctx.label} and what you're playing", recs.map { it.track }, captions = captions(recs))
        }
        runCatching { recommendations.dailyMixes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { out += HomeSection.DailyMixes(it) }
        runCatching { recommendations.freshFinds(16) }.getOrNull()?.takeIf { it.size >= 4 }?.let { recs ->
            out += HomeSection.TrackShelf("fresh", "Fresh finds", "New to you, close to your taste", recs.map { it.track }, captions = captions(recs))
        }
        runCatching { recommendations.rediscover(16) }.getOrNull()?.takeIf { it.size >= 4 }?.let { recs ->
            out += HomeSection.TrackShelf("rediscover", "Rediscover", "Loved before, quiet lately", recs.map { it.track }, captions = captions(recs))
        }

        InsightsEngine.timeMachine(library.events(now - 400L * DAY), now, zone).firstOrNull()?.let { tm ->
            val tracks = library.tracks(tm.trackIds)
            if (tracks.size >= 2) out += HomeSection.TimeMachine(tm, tracks)
        }

        val feedback = runCatching { recommendations.feedback() }.getOrDefault(Feedback.None)
        val local = library.localTracks.value.filter { feedback.allows(it) }
        if (local.isNotEmpty()) {
            val ranked = recommender.rank(local, profile, now, library.likedIds.value, discovery = 0.2f).take(14)
            out += HomeSection.TrackShelf("local", "From your device", "Plays in the background, offline", ranked.map { it.track })
        }

        if (network.currentlyOnline() || youtube.quotaState() != QuotaState.NORMAL) {
            youtube.trending().getOrNull()?.takeIf { it.isNotEmpty() }?.let { trending ->
                val ranked = recommender.rank(trending.filter { it.isSingle() && feedback.allows(it) }, profile, now, library.likedIds.value, discovery = 0.5f)
                out += HomeSection.TrackShelf("trending", "Trending in music", "From YouTube's popular music chart", ranked.map { it.track }.take(20))
            }
        }

        // Late night → moments first; morning → continue first. Light, honest prioritisation.
        val order = if (hour >= 21 || hour < 4) listOf("moments", "continue", "foryou", "daily", "made", "rediscover", "fresh", "local", "start", "trending")
        else listOf("continue", "foryou", "daily", "moments", "made", "start", "fresh", "rediscover", "local", "trending")
        out.sortedBy { s -> order.indexOf(s.key).let { if (it < 0) 50 else it } }.take(10)
    }

    fun featuredMoment(hour: Int, moods: Set<String>): Moment {
        val byTime = when (hour) {
            in 5..9 -> "golden_hour"
            in 10..16 -> "deep_focus"
            in 17..19 -> "golden_hour"
            in 20..22 -> "night_drive"
            else -> "late_night"
        }
        val moodPick = Moments.all.firstOrNull { m -> m.moods.any { it.name in moods } && (hour in 9..18) }
        return moodPick ?: Moments.byId(byTime) ?: Moments.all.first()
    }

    /** Arnav AI session: AI (optional) interprets → real search resolves → deterministic builder orders. */
    suspend fun buildSession(request: String, onPhase: (Int) -> Unit = {}): SessionResult {
        onPhase(0)
        val now = clock.now()
        val profile = profile()
        val s = settings.settings.value
        val names = artistDisplayNames(profile)
        var aiReason: AiUnavailableReason? = null
        var explanation: String? = null
        var constraints: SessionConstraints = LocalIntentEngine.interpret(request, names.take(3))
        var usedAi = false

        if (s.aiEnabled) {
            val prompt = PromptLibrary.sessionPrompt(
                request,
                if (s.aiPersonalization) names else emptyList(),
                if (s.aiPersonalization) profile.genreAffinity.entries.sortedByDescending { it.value }.take(5).map { it.key } else emptyList(),
                Instant.ofEpochMilli(now).atZone(zone).hour,
            )
            when (val r = ai.generate(prompt, PromptLibrary.SESSION_VERSION, json = true)) {
                is AiOutcome.Ok -> AiJson.parseSession(r.text).onSuccess { parsed ->
                    constraints = parsed.toConstraints()
                    explanation = parsed.explanation?.take(140)
                    usedAi = true
                }.onFailure { aiReason = AiUnavailableReason.MALFORMED }
                is AiOutcome.Unavailable -> aiReason = r.reason
            }
        } else aiReason = AiUnavailableReason.DISABLED_BY_USER

        if (aiReason != null && aiReason != AiUnavailableReason.DISABLED_BY_USER) ai.noteFallback()
        val studio = s.studio
        if (studio.sessionControls) constraints = constraints.copy(
            durationMinutes = studio.sessionMinutes, energyTarget = studio.sessionEnergy,
            familiarity = studio.sessionFamiliarity, discoveryRatio = 1f - studio.sessionFamiliarity,
            artistDiversity = studio.sessionDiversity, energyCurve = studio.sessionCurve,
        ).sanitized()
        onPhase(1)
        val (candidates, searched) = resolveCandidates(constraints, profile)
        onPhase(2)
        val session = builder.build(constraints, candidates, profile, library.likedIds.value, now)
        library.remember(session.tracks)
        return SessionResult(session, usedAi, aiReason, explanation, searched)
    }

    suspend fun momentQueue(moment: Moment): BuiltSession {
        val profile = profile()
        val c = SessionConstraints(
            title = moment.title, durationMinutes = 60, energyTarget = moment.aesthetic.energy,
            moods = moment.moods.map { it.name.lowercase() }, searchQueries = moment.seedQueries.take(2),
            familiarity = 0.5f, discoveryRatio = 0.4f,
        )
        val (candidates, _) = resolveCandidates(c, profile)
        val s = builder.build(c, candidates, profile, library.likedIds.value, clock.now())
        library.remember(s.tracks)
        return s
    }

    private suspend fun resolveCandidates(c: SessionConstraints, profile: TasteProfile): Pair<List<Track>, Int> {
        val now = clock.now()
        val known = library.allKnownTracks().distinctBy { it.id }
        val moods = c.moodSet
        val avoid = c.avoidMoods.mapNotNull { a -> Mood.entries.firstOrNull { it.name.equals(a, true) } }.toSet()
        val maxEnergy = if (Mood.AGGRESSIVE in avoid) 0.85f else 1f

        val fromKnown = when {
            c.rediscover -> known.filter { t -> (profile.lastPlayedAt[t.id] ?: now).let { now - it > 30 * DAY } && (profile.trackPlayCounts[t.id] ?: 0) >= 1 }
            moods.isEmpty() -> known
            else -> known.filter { t -> t.energy == null || abs(t.energy!! - c.energyTarget) < 0.3f }
        }.filter { (it.energy ?: 0f) <= maxEnergy }

        val budget = when (youtube.quotaState()) {
            QuotaState.NORMAL -> 2
            QuotaState.CONSERVE -> 1
            QuotaState.EXHAUSTED -> 0
        }
        val queries = (c.seedArtists + c.searchQueries).distinct()
        var remote = 0
        val fromSearch = ArrayList<Track>()
        for (q in queries) {
            val cached = youtube.cached(q, SearchFilter.TRACKS)
            val res = when {
                cached != null -> cached
                remote < budget && network.currentlyOnline() -> youtube.search(q, SearchFilter.TRACKS).getOrNull()?.also { if (!it.fromCache) remote++ }
                else -> null
            }
            res?.tracks?.let { fromSearch += it }
            if (fromSearch.size > 120) break
        }
        // Recommendations are singles only: no mixes, mashups, jukeboxes or hour-long sets.
        val feedback = runCatching { recommendations.feedback() }.getOrDefault(Feedback.None)
        val singles = (fromSearch + fromKnown).filter { it.isSingle() && feedback.allows(it) && (it.energy ?: 0f) <= maxEnergy }.distinctBy { it.id }
        // Prefer official audio ("Topic") uploads when the same song appears more than once.
        val prefer = if (settings.settings.value.preferVideos) com.arnav.music.domain.model.MediaVariant.VIDEO else com.arnav.music.domain.model.MediaVariant.SONG
        val deduped = singles.groupBy { it.artistKey + "|" + it.title.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "") }
            .values.map { group -> group.minByOrNull { if (it.variant == prefer) 0 else if (it.variant == null) 1 else 2 }!! }
        return deduped to remote
    }

    // ------------------------------------------------------------------ radio + feedback

    /** Radio from any song: [seed] first, then ~25 recommendations (singles only, explained). */
    suspend fun radio(seed: Track, limit: Int = 25): List<Track> {
        val recs = runCatching { recommendations.radio(seed, limit) }.getOrDefault(emptyList())
        return listOf(seed) + recs.map { it.track }
    }

    /** Recommendations with their reasons, for screens that show them. */
    suspend fun radioWithReasons(seed: Track, limit: Int = 25): List<Recommendation> =
        runCatching { recommendations.radio(seed, limit) }.getOrDefault(emptyList())

    /** Radio from an artist (by [com.arnav.music.domain.model.ArtistKey]). */
    suspend fun artistRadio(artistKey: String, limit: Int = 25): List<Track> =
        runCatching { recommendations.artistRadio(artistKey, limit) }.getOrDefault(emptyList()).map { it.track }

    /** Songs to append when the queue runs out (endless radio). */
    suspend fun endlessRadio(recent: List<Track>, exclude: Set<TrackId>, localOnly: Boolean, limit: Int = 10): List<Track> =
        runCatching { recommendations.continuation(recent, exclude, localOnly, limit) }.getOrDefault(emptyList()).map { it.track }

    /** "Not interested": never recommend this song again. */
    suspend fun notInterested(track: Track) = recommendations.notInterested(track)

    /** "Don't recommend this artist". */
    suspend fun blockArtist(artistKey: String) = recommendations.blockArtist(artistKey)

    /** "More like this": weighs this song's neighbourhood up for a while. */
    suspend fun moreLikeThis(track: Track) = recommendations.moreLikeThis(track)

    /** Undo any of the three above (subject = track id or artist key). */
    suspend fun clearFeedback(subject: String) = recommendations.clearFeedback(subject)

    /** Settings › "Reset recommendations": forget feedback and the learned source blend. */
    suspend fun resetRecommendations() = recommendations.resetLearning()

    fun explain(reason: Reason, track: Track): String = when (reason) {
        Reason.ARTIST_RETURNING -> "Because you've been returning to ${track.artist} lately."
        Reason.SIMILAR_ENERGY -> "Similar energy to what you've been playing."
        Reason.GENRE_MATCH -> "Close to the styles you play most."
        Reason.FORGOTTEN_FAVORITE -> "A favourite you haven't played in a while."
        Reason.NEW_DISCOVERY -> "Something different from your usual rotation."
        Reason.HEAVY_ROTATION -> "In your heavy rotation."
        Reason.LIKED -> "From your liked songs."
        Reason.TIME_OF_DAY -> "Picked for this moment."
    }

    suspend fun recap(period: RecapPeriod): Recap = InsightsEngine.recap(period, library.events(clock.now() - (period.days + 1) * DAY - DAY), clock.now(), zone)

    suspend fun tasteDna(): TasteDna {
        val now = clock.now()
        val events = library.events(now - 365L * DAY)
        val years = library.tracks(events.map { it.trackId }.distinct()).mapNotNull { t -> t.year?.let { t.id to it } }.toMap()
        return InsightsEngine.tasteDna(profile(), events, years, now)
    }

    suspend fun constellation(): ConstellationGraph {
        val events = library.events(clock.now() - 365L * DAY)
        val tracks = library.tracks(events.map { it.trackId }.distinct())
        val names = tracks.associate { it.artistKey to it.artist }
        val genres = tracks.groupBy { it.artistKey }.mapValues { (_, v) -> v.flatMap { it.genres }.toSet() }
        return ConstellationBuilder.build(events, names, genres)
    }

    suspend fun eventsBetween(from: Long, to: Long): List<PlayEvent> = library.eventsBetween(from, to)

    suspend fun artistDisplayNames(profile: TasteProfile): List<String> {
        val keys = profile.artistAffinity.entries.sortedByDescending { it.value }.take(8).map { it.key }
        return keys.mapNotNull { k -> library.tracksByArtist(k).firstOrNull()?.artist }
    }

    fun invalidate() {
        cachedProfile = null
        recommendations.invalidate()
    }

    companion object {
        const val DAY = 86_400_000L
        fun greeting(now: Long): String = Formatters.greeting(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour)
    }
}
