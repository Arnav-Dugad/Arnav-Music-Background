package com.arnav.music.core.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.AudioFeaturesDao
import com.arnav.music.core.db.AudioFeaturesEntity
import com.arnav.music.core.db.SkipMarkDao
import com.arnav.music.core.db.SkipMarkEntity
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.audio.HarmonicMix
import com.arnav.music.domain.audio.MixEntry
import com.arnav.music.domain.audio.TrackSections
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.PlaybackCapabilities
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.queue.QueueItem
import com.arnav.music.domain.queue.QueueState
import com.arnav.music.domain.stats.SkipSpots
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import kotlin.math.max
import kotlin.math.min

enum class Engine { NONE, LOCAL, YOUTUBE }

enum class RepeatMode { OFF, ALL, ONE }

/** Smart transitions: intros shorter than this aren't skipped; land this much before the music. */
private const val SMART_MIN_INTRO_MS = 300L
private const val SMART_INTRO_LEAD_MS = 150L
/** Outro fades last at least this long (or the user's fade length when longer)… */
private const val SMART_MIN_FADE_MS = 1_500L
/** …and end this long before the file does, so the player doesn't reach the end first. */
private const val SMART_END_MARGIN_MS = 250L
/** Not worth fading when less than this is left. */
private const val SMART_SHORTEST_FADE_MS = 400L

sealed interface PlaybackIssue {
    /** [searching] while looking for another upload; [videoId] lets the UI hand off to YouTube Music. */
    data class Unavailable(val title: String, val videoId: String? = null, val searching: Boolean = false, val autoSkipAt: Long? = null) : PlaybackIssue
    data class Replaced(val title: String, val variant: com.arnav.music.domain.model.MediaVariant?) : PlaybackIssue
    data class VariantNotFound(val want: com.arnav.music.domain.model.MediaVariant) : PlaybackIssue
    data object YouTubePausedInBackground : PlaybackIssue
    data object NetworkLost : PlaybackIssue
    data object NeedsNotificationPermission : PlaybackIssue
}

sealed interface SleepTimer {
    data class Countdown(val endsAt: Long, val totalMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
    data object EndOfQueue : SleepTimer
}

data class PlayerState(
    val queue: QueueState = QueueState(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val engine: Engine = Engine.NONE,
    val capabilities: PlaybackCapabilities = PlaybackCapabilities.LocalMedia,
    val repeat: RepeatMode = RepeatMode.OFF,
    val issue: PlaybackIssue? = null,
    val sleep: SleepTimer? = null,
) {
    val current: Track? get() = queue.current?.track
}

data class Progress(val positionMs: Long = 0L, val durationMs: Long = 0L) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Single source of truth for playback. The UI talks only to this class and reads
 * [PlaybackCapabilities] — it never needs to know which engine is underneath.
 */
class PlaybackController(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val scope: CoroutineScope,
    val youtube: YouTubeEngine,
    private val resolver: com.arnav.music.core.youtube.UploadResolver,
) : YouTubeEngine.Events {
    private val _switching = MutableStateFlow(false)
    /** True while looking for the other (song/video) upload of the current track. */
    val switchingVariant: StateFlow<Boolean> = _switching.asStateFlow()
    private val triedUploads = HashMap<Long, MutableSet<String>>()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var pendingLocal: (() -> Unit)? = null
    /** Index range of the queue currently loaded into ExoPlayer (contiguous local tracks). */
    private var localRunStart = 0
    private var progressJob: Job? = null
    private var sleepJob: Job? = null
    private var persistJob: Job? = null
    private var fadeJob: Job? = null
    private val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Listening recorder for the current track.
    private var sessionTrack: Track? = null
    private var sessionStartedAt = 0L
    private var sessionListenedMs = 0L
    private val listeningMeter = com.arnav.music.domain.history.ListeningMeter()

    // Smart transitions (local playback only) — see [smartTick] and [skipIntroOnNaturalStart].
    private val featuresDao: AudioFeaturesDao by lazy { GlobalContext.get().get<ArnavDatabase>().audioFeatures() }
    /** Where songs get skipped by hand (Listening stats → "Songs you skip at the same second"). */
    private val skipMarks: SkipMarkDao by lazy { GlobalContext.get().get<ArnavDatabase>().skipMarks() }
    /** Intro/outro per local track id, refreshed whenever a track becomes current. */
    private val sectionCache = HashMap<String, TrackSections>()
    /** Queue uid the outro watcher last saw, and its position/seek count on the previous tick. */
    private var smartUid: Long? = null
    private var smartPrevPos = -1L
    private var smartPrevSerial = 0
    /** Counts seeks reported by the player (ours, the UI's, the notification's, Bluetooth…). */
    private var seekSerial = 0
    private var smartFadeJob: Job? = null

    init {
        youtube.events = this
        restoreQueue()
    }

    // region Public API

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        scope.launch { library.remember(tracks.filter { it.source == SourceType.YOUTUBE }) }
        finishSession(skipped = _state.value.isPlaying)
        var q = QueueState().replace(tracks, startIndex.coerceIn(0, tracks.lastIndex))
        if (shuffle) q = q.shuffle(clock.now())
        _state.update { it.copy(queue = q, issue = null) }
        startCurrent(autoplay = true)
    }

    fun playNext(tracks: List<Track>) {
        val wasEmpty = _state.value.queue.items.isEmpty()
        _state.update { it.copy(queue = it.queue.playNext(tracks)) }
        if (wasEmpty) startCurrent(true) else onQueueEdited()
    }

    fun addToQueue(tracks: List<Track>) {
        val wasEmpty = _state.value.queue.items.isEmpty()
        _state.update { it.copy(queue = it.queue.append(tracks)) }
        if (wasEmpty) startCurrent(true) else onQueueEdited()
    }

    fun move(from: Int, to: Int) {
        _state.update { it.copy(queue = it.queue.move(from, to)) }
        onQueueEdited()
    }

    fun removeAt(index: Int) {
        val wasCurrent = index == _state.value.queue.currentIndex
        _state.update { it.copy(queue = it.queue.removeAt(index)) }
        if (wasCurrent) startCurrent(_state.value.isPlaying) else onQueueEdited()
    }

    fun skipTo(index: Int) {
        finishSession(skipped = true)
        _state.update { it.copy(queue = it.queue.skipTo(index)) }
        startCurrent(true)
    }

    fun togglePlay() = if (_state.value.isPlaying) pause() else play()

    fun play() {
        val s = _state.value
        if (s.current == null) return
        _state.update { it.copy(issue = null) }
        when (s.engine) {
            Engine.LOCAL -> controller?.let { c -> if (c.mediaItemCount == 0) startCurrent(true) else fadeTo(c, play = true) } ?: startCurrent(true)
            Engine.YOUTUBE -> if (youtube.currentVideoId == s.current?.playbackRef) youtube.play() else startCurrent(true)
            Engine.NONE -> startCurrent(true)
        }
    }

    fun pause() {
        when (_state.value.engine) {
            Engine.LOCAL -> controller?.let { fadeTo(it, play = false) }
            Engine.YOUTUBE -> youtube.pause()
            Engine.NONE -> Unit
        }
        _state.update { it.copy(isPlaying = false) }
    }

    fun next() {
        cancelSmartFade(restoreVolume = true)
        val s = _state.value
        finishSession(skipped = true)
        val nq = s.queue.next(repeatAll = s.repeat == RepeatMode.ALL)
        if (nq == s.queue) { if (!continueWithRadio(onEmpty = ::pause)) pause(); return }
        _state.update { it.copy(queue = nq) }
        if (s.engine == Engine.LOCAL && nq.currentIndex in localRunStart until localRunStart + (controller?.mediaItemCount ?: 0) &&
            nq.current?.track?.source == SourceType.LOCAL) {
            controller?.seekTo(nq.currentIndex - localRunStart, 0)
            controller?.play()
        } else startCurrent(true)
    }

    fun previous() {
        val s = _state.value
        if (_progress.value.positionMs > 4_000 || !s.queue.hasPrevious) { seekTo(0); return }
        finishSession(skipped = true)
        _state.update { it.copy(queue = it.queue.previous()) }
        startCurrent(true)
    }

    fun seekTo(positionMs: Long) {
        cancelSmartFade(restoreVolume = true)
        when (_state.value.engine) {
            Engine.LOCAL -> controller?.seekTo(positionMs)
            Engine.YOUTUBE -> youtube.seekTo(positionMs / 1000f)
            Engine.NONE -> Unit
        }
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun toggleShuffle() {
        _state.update { s -> s.copy(queue = if (s.queue.shuffled) s.queue.unshuffle() else s.queue.shuffle(clock.now())) }
        onQueueEdited()
    }

    fun cycleRepeat() {
        _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
        controller?.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun dismissIssue() = _state.update { it.copy(issue = null) }

    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        _state.update { it.copy(sleep = timer) }
        if (timer is SleepTimer.Countdown) {
            sleepJob = scope.launch {
                while (isActive && clock.now() < timer.endsAt) delay(1_000)
                if (isActive) { pause(); _state.update { it.copy(sleep = null) } }
            }
        }
    }

    fun clearQueue() {
        cancelSmartFade(restoreVolume = true)
        finishSession(skipped = false)
        controller?.stop(); controller?.clearMediaItems()
        youtube.pause()
        _state.value = PlayerState(repeat = _state.value.repeat)
        _progress.value = Progress()
        persistQueue()
    }

    /**
     * "Harmonic mix": reorders the upcoming queue like a DJ set — Camelot-compatible keys next to
     * each other, small tempo changes, a gentle energy arc — starting from the song playing now.
     * Played items and the current one stay put; only local songs with on-device analysis are
     * reordered, everything else (YouTube, unanalysed files) follows in its previous order.
     * Returns false and leaves the queue alone when fewer than 3 upcoming songs are analysed
     * (or the queue changed while the order was being worked out).
     */
    suspend fun harmonicMix(): Boolean {
        val snapshot = _state.value.queue
        val current = snapshot.current ?: return false
        val upcoming = snapshot.upNext
        if (upcoming.size < 3) return false
        val rows = HashMap<String, AudioFeaturesEntity>()
        for (item in upcoming + current) {
            val t = item.track
            if (t.source != SourceType.LOCAL || rows.containsKey(t.id.value)) continue
            val row = runCatching { featuresDao.get(t.id.value) }.getOrNull()
            if (row != null && row.ok) rows[t.id.value] = row
        }
        fun entry(item: QueueItem): MixEntry<QueueItem> {
            val row = if (item.track.source == SourceType.LOCAL) rows[item.track.id.value] else null
            return if (row == null) MixEntry(item, analysed = false)
            else MixEntry(item, bpm = row.bpm, key = row.musicalKey, energy = row.energy)
        }
        val entries = upcoming.map(::entry)
        if (entries.count { it.analysed } < 3) return false
        val start = entry(current).takeIf { it.analysed }
        val ordered = withContext(Dispatchers.Default) { HarmonicMix.order(entries, start) }
        val snapshotUids = snapshot.items.map { it.uid }
        var applied = false
        _state.update { s ->
            val q = s.queue
            // Bail out if the user edited the queue or moved on meanwhile.
            applied = q.currentIndex == snapshot.currentIndex && q.items.map { it.uid } == snapshotUids
            if (!applied) s else {
                val items = q.items.take(q.currentIndex + 1) + ordered
                s.copy(queue = q.copy(items = items, originalOrder = if (q.shuffled) q.originalOrder else items.map { it.uid }))
            }
        }
        if (applied) onQueueEdited()
        return applied
    }

    // endregion

    /**
     * Loads and (optionally) plays the current queue item. [natural] = the previous song simply
     * finished (not a user pick), which lets smart transitions skip a silent/quiet intro.
     */
    private fun startCurrent(autoplay: Boolean, natural: Boolean = false) {
        cancelSmartFade(restoreVolume = true)
        val s = _state.value
        val track = s.current ?: run { _state.update { it.copy(engine = Engine.NONE, isPlaying = false) }; return }
        beginSession(track)
        persistQueue()
        when (track.source) {
            SourceType.LOCAL -> {
                youtube.pause()
                _state.update { it.copy(engine = Engine.LOCAL, capabilities = PlaybackCapabilities.LocalMedia, isBuffering = true, isPlaying = autoplay) }
                val uid = s.queue.current?.uid
                withController { c ->
                    val introAt = if (natural && smartAllowed(track)) cachedIntroStart(track) else 0L
                    loadLocalRun(c, autoplay, introAt ?: 0L)
                    if (introAt == null && uid != null) skipIntroWhenLoaded(c, uid)
                }
            }
            SourceType.YOUTUBE -> {
                _state.update { it.copy(engine = Engine.YOUTUBE, capabilities = PlaybackCapabilities.YouTubeEmbed, isBuffering = true, isPlaying = autoplay) }
                // Remove the old local session's playable item and notification. Its transport
                // controls must not start a second source behind the current YouTube queue.
                controller?.stop()
                controller?.clearMediaItems()
                _progress.value = Progress(0, track.durationMs ?: 0)
                youtube.load(track.playbackRef, 0f, autoplay)
            }
        }
        startProgressLoop()
    }

    private fun loadLocalRun(c: MediaController, autoplay: Boolean, startMs: Long = 0L) {
        val q = _state.value.queue
        var start = q.currentIndex
        var end = q.currentIndex
        while (start > 0 && q.items[start - 1].track.source == SourceType.LOCAL) start--
        while (end < q.items.lastIndex && q.items[end + 1].track.source == SourceType.LOCAL) end++
        localRunStart = start
        val items = q.items.subList(start, end + 1).map { it.track.toMediaItem() }
        c.setMediaItems(items, q.currentIndex - start, startMs)
        c.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        c.prepare()
        if (autoplay) fadeTo(c, play = true) else c.pause()
    }

    private fun onQueueEdited() {
        persistQueue()
        val s = _state.value
        if (s.engine == Engine.LOCAL && s.current?.source == SourceType.LOCAL) {
            // Re-sync ExoPlayer's window without interrupting the current track.
            val c = controller ?: return
            val pos = c.currentPosition
            val playing = c.isPlaying
            loadLocalRunPreservingPosition(c, pos, playing)
        }
    }

    private fun loadLocalRunPreservingPosition(c: MediaController, pos: Long, playing: Boolean) {
        val q = _state.value.queue
        var start = q.currentIndex
        var end = q.currentIndex
        while (start > 0 && q.items[start - 1].track.source == SourceType.LOCAL) start--
        while (end < q.items.lastIndex && q.items[end + 1].track.source == SourceType.LOCAL) end++
        localRunStart = start
        c.setMediaItems(q.items.subList(start, end + 1).map { it.track.toMediaItem() }, q.currentIndex - start, pos)
        c.prepare()
        if (playing) c.play()
    }

    private fun fadeTo(c: MediaController, play: Boolean) {
        // A user play/pause wins over a smart outro fade; the ramp below starts from the current volume.
        cancelSmartFade(restoreVolume = false)
        // A newer play/pause cancels an unfinished ramp: otherwise a quick pause → play would be
        // undone when the old fade-out ends with pause().
        fadeJob?.cancel()
        fadeJob = null
        val fade = settings.settings.value.fadeMs.toLong()
        if (fade <= 0) { c.volume = 1f; if (play) c.play() else c.pause(); return }
        // If a fade-out was cut short the player never actually paused, so no "is playing" change
        // will arrive: reflect it here or the UI would keep showing Play.
        if (play && c.isPlaying) _state.update { it.copy(isPlaying = true) }
        fadeJob = scope.launch {
            val steps = 12
            if (play) {
                c.volume = if (c.isPlaying) c.volume.coerceAtMost(1f) else 0f; c.play()
                val start = c.volume
                for (i in 1..steps) { c.volume = start + (1f - start) * i / steps.toFloat(); delay(fade / steps) }
            } else {
                val from = c.volume
                for (i in steps - 1 downTo 0) { c.volume = from * i / steps; delay(fade / steps) }
                c.pause(); c.volume = 1f
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        pendingLocal = { controller?.let(block) }
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: run {
                Log.w("MediaController connection failed"); return@addListener
            }
            controller = c
            c.addListener(localListener)
            pendingLocal?.invoke()
            pendingLocal = null
        }, ContextCompat.getMainExecutor(context))
    }

    private val localListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_state.value.engine == Engine.LOCAL) _state.update { it.copy(isPlaying = isPlaying || (controller?.playWhenReady == true && it.isBuffering)) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (_state.value.engine != Engine.LOCAL) return
            _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) onTrackEnded()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_state.value.engine != Engine.LOCAL || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val c = controller ?: return
            val newIndex = localRunStart + c.currentMediaItemIndex
            if (newIndex != _state.value.queue.currentIndex) {
                val crossfaded = PlaybackService.crossfadeFromId == _state.value.current?.id?.value
                PlaybackService.crossfadeFromId = null
                finishSession(skipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !crossfaded,
                    completedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || crossfaded)
                _state.update { it.copy(queue = it.queue.skipTo(newIndex)) }
                _state.value.current?.let(::beginSession)
                persistQueue()
                if (_state.value.sleep == SleepTimer.EndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    c.pause(); _state.update { it.copy(sleep = null) }
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) skipIntroOnNaturalStart(c)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seekSerial++
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = _state.value.current?.title ?: "This track"
            _state.update { it.copy(issue = PlaybackIssue.Unavailable(title), isPlaying = false) }
            scope.launch { delay(1_500); if (_state.value.issue is PlaybackIssue.Unavailable) next() }
        }
    }

    private fun onTrackEnded() {
        finishSession(skipped = false, completedNaturally = true)
        val s = _state.value
        when {
            s.sleep == SleepTimer.EndOfTrack -> { _state.update { it.copy(isPlaying = false, sleep = null) }; return }
            s.repeat == RepeatMode.ONE -> { seekTo(0); play(); return }
            !s.queue.hasNext && s.repeat != RepeatMode.ALL -> {
                if (continueWithRadio()) return
                _state.update { it.copy(isPlaying = false, sleep = if (it.sleep == SleepTimer.EndOfQueue) null else it.sleep) }
                return
            }
        }
        _state.update { it.copy(queue = it.queue.next(repeatAll = it.repeat == RepeatMode.ALL)) }
        startCurrent(true, natural = true)
    }

    // region Endless radio
    //
    // When the queue runs out and Settings › Endless radio is on, ~10 recommendations seeded from the
    // queue so far are appended and playback continues. The background edition can keep an
    // existing YouTube session playing; local-only sessions continue to prefer local picks.
    // songs on this device are added (none → playback simply stops, as before).

    private val intelligence: IntelligenceRepository by lazy { GlobalContext.get().get<IntelligenceRepository>() }
    private var radioJob: Job? = null

    /** Starts appending radio picks; returns false (caller stops as usual) when endless radio doesn't apply. */
    private fun continueWithRadio(onEmpty: () -> Unit = {}): Boolean {
        val s = _state.value
        if (!settings.settings.value.endlessRadio || s.sleep == SleepTimer.EndOfQueue || s.queue.items.isEmpty()) return false
        if (radioJob?.isActive == true) return true
        val uid = s.queue.current?.uid
        val recent = s.queue.items.map { it.track }
        _state.update { it.copy(isBuffering = true) }
        radioJob = scope.launch {
            val background = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val picks = runCatching {
                intelligence.endlessRadio(recent.takeLast(25), recent.map { it.id }.toSet(), localOnly = background && s.engine != Engine.YOUTUBE)
            }.getOrDefault(emptyList())
            val now = _state.value
            // The listener moved on meanwhile (picked something, cleared the queue): leave it alone.
            if (now.queue.current?.uid != uid || now.queue.hasNext) { _state.update { it.copy(isBuffering = false) }; return@launch }
            if (picks.isEmpty()) {
                _state.update { it.copy(isPlaying = false, isBuffering = false) }
                onEmpty()
                return@launch
            }
            scope.launch { library.remember(picks.filter { it.source == SourceType.YOUTUBE }) }
            _state.update { it.copy(queue = it.queue.append(picks).next()) }
            startCurrent(autoplay = true, natural = true)
        }
        return true
    }
    // endregion

    // region Smart transitions
    //
    // Local playback only, while Settings › Smart transitions is on, using the intro/outro found by
    // on-device analysis (AnalysisWorker):
    // - Outro: when playback *naturally* crosses a song's analysed outro (its closing fade or
    //   trailing silence), the volume fades out from there over min(time left, max(fadeMs, 1.5 s))
    //   and the next item starts as if the song had ended — counted as a completed listen.
    //   Never when the outro lies in the first half of the song, with repeat-one, with the
    //   "end of track" sleep timer, without a next item, or when the position got there by a seek.
    //   Any user action (play/pause, seek, skip, queue jump — also from the notification) cancels it.
    // - Intro: when a song starts because the previous one finished (not a user pick or seek), and
    //   it has more than 300 ms of silence/quiet intro, playback jumps to 150 ms before the music.
    // YouTube playback is never touched.

    private fun smartAllowed(track: Track?): Boolean =
        track != null && track.source == SourceType.LOCAL && settings.settings.value.smartTransitions && settings.settings.value.crossfadeMs == 0 && _state.value.repeat != RepeatMode.ONE

    private fun introTarget(sections: TrackSections): Long =
        if (sections.introMs > SMART_MIN_INTRO_MS) sections.introMs - SMART_INTRO_LEAD_MS else 0L

    /** Where a natural start should begin, or null when the track's sections aren't loaded yet. */
    private fun cachedIntroStart(track: Track): Long? = sectionCache[track.id.value]?.let(::introTarget)

    private suspend fun loadSections(track: Track): TrackSections? {
        if (track.source != SourceType.LOCAL) return null
        val row = runCatching { featuresDao.get(track.id.value) }.getOrNull()
        val sections = if (row != null && row.ok) TrackSections(row.introMs, row.outroMs) else TrackSections.NONE
        if (sectionCache.size > 256) sectionCache.clear()
        sectionCache[track.id.value] = sections
        return sections
    }

    /** Called when ExoPlayer moved on to the next song by itself. */
    private fun skipIntroOnNaturalStart(c: MediaController) {
        val item = _state.value.queue.current ?: return
        if (!smartAllowed(item.track)) return
        val target = cachedIntroStart(item.track)
        when {
            target == null -> skipIntroWhenLoaded(c, item.uid)
            target > 0L && c.currentPosition < target -> c.seekTo(target)
        }
    }

    /** Looks the intro up, then skips it — unless the song changed or someone seeked meanwhile. */
    private fun skipIntroWhenLoaded(c: MediaController, uid: Long) {
        val item = _state.value.queue.current?.takeIf { it.uid == uid } ?: return
        if (!smartAllowed(item.track)) return
        val serial = seekSerial
        scope.launch {
            val sections = loadSections(item.track) ?: return@launch
            val target = introTarget(sections)
            val s = _state.value
            if (target <= 0L || s.engine != Engine.LOCAL || s.queue.current?.uid != uid || seekSerial != serial || !smartAllowed(item.track)) return@launch
            if (c.currentPosition < target) c.seekTo(target)
        }
    }

    /** Runs on every progress tick of local playback: refreshes sections and watches for the outro. */
    private fun smartTick(c: MediaController, s: PlayerState, progress: Progress) {
        val item = s.queue.current
        if (item?.uid != smartUid) {
            smartUid = item?.uid
            smartPrevPos = -1L
            smartPrevSerial = seekSerial
            if (item != null && item.track.source == SourceType.LOCAL) {
                val nextTrack = s.queue.items.getOrNull(s.queue.currentIndex + 1)?.track
                scope.launch {
                    loadSections(item.track)
                    if (nextTrack != null) loadSections(nextTrack)
                }
            }
            return
        }
        val prev = smartPrevPos
        val prevSerial = smartPrevSerial
        val pos = progress.positionMs
        smartPrevPos = pos
        smartPrevSerial = seekSerial
        if (item == null || smartFadeJob != null || !smartAllowed(item.track)) return
        if (!s.isPlaying || s.isBuffering || s.engine != Engine.LOCAL || s.sleep == SleepTimer.EndOfTrack) return
        if (!s.queue.hasNext && s.repeat != RepeatMode.ALL) return
        val outro = sectionCache[item.track.id.value]?.outroMs ?: return
        val duration = progress.durationMs
        if (outro <= 0L || duration <= 0L || outro < duration / 2 || outro >= duration) return
        // Only a natural crossing: no seek since the last tick and a normal-sized step.
        if (prev < 0L || prevSerial != seekSerial || prev >= outro || pos < outro || pos - prev > 2_000L) return
        val fade = min(duration - pos - SMART_END_MARGIN_MS, max(settings.settings.value.fadeMs.toLong(), SMART_MIN_FADE_MS))
        if (fade < SMART_SHORTEST_FADE_MS) return
        startSmartFade(c, item.uid, pos, fade)
    }

    private fun startSmartFade(c: MediaController, uid: Long, fromPos: Long, fadeMs: Long) {
        smartFadeJob?.cancel()
        val steps = (fadeMs / 50L).toInt().coerceIn(8, 60)
        smartFadeJob = scope.launch {
            val from = c.volume
            for (i in 1..steps) {
                delay(fadeMs / steps)
                val s = _state.value
                val stillValid = s.engine == Engine.LOCAL && s.queue.current?.uid == uid && c.playWhenReady &&
                    c.currentPosition >= fromPos - 1_000L && settings.settings.value.smartTransitions
                if (!stillValid) {
                    smartFadeJob = null
                    c.volume = 1f
                    return@launch
                }
                val t = i / steps.toFloat()
                c.volume = from * (1f - t) * (1f - t) // ease-out: quick at first, gentle into silence
            }
            smartFadeJob = null
            completeSmartOutro(c, uid)
        }
    }

    /** Stops an outro fade in progress (user action); [restoreVolume] puts the volume back to 1. */
    private fun cancelSmartFade(restoreVolume: Boolean) {
        val job = smartFadeJob ?: return
        smartFadeJob = null
        job.cancel()
        if (restoreVolume) controller?.volume = 1f
    }

    /** The outro fade finished: move on exactly as if the song had ended. */
    private fun completeSmartOutro(c: MediaController, uid: Long) {
        val s = _state.value
        if (s.engine != Engine.LOCAL || s.queue.current?.uid != uid) { c.volume = 1f; return }
        val nq = s.queue.next(repeatAll = s.repeat == RepeatMode.ALL)
        finishSession(skipped = false, completedNaturally = true)
        if (nq == s.queue) {
            c.pause(); c.volume = 1f
            _state.update { it.copy(isPlaying = false) }
            return
        }
        _state.update { it.copy(queue = nq) }
        val next = nq.current ?: return
        val inRun = next.track.source == SourceType.LOCAL && nq.currentIndex in localRunStart until localRunStart + c.mediaItemCount
        if (!inRun) {
            c.volume = 1f
            startCurrent(true, natural = true)
            return
        }
        beginSession(next.track)
        persistQueue()
        val introAt = if (smartAllowed(next.track)) cachedIntroStart(next.track) else 0L
        c.seekTo(nq.currentIndex - localRunStart, introAt ?: 0L)
        if (introAt == null) skipIntroWhenLoaded(c, next.uid)
        fadeTo(c, play = true)
    }
    // endregion

    // region YouTube events
    override fun onYtState(state: PlayerConstants.PlayerState) {
        if (_state.value.engine != Engine.YOUTUBE) return
        when (state) {
            PlayerConstants.PlayerState.PLAYING -> _state.update { it.copy(isPlaying = true, isBuffering = false, issue = null) }
            PlayerConstants.PlayerState.PAUSED -> _state.update { it.copy(isPlaying = false, isBuffering = false) }
            PlayerConstants.PlayerState.BUFFERING -> _state.update { it.copy(isBuffering = true) }
            PlayerConstants.PlayerState.ENDED -> onTrackEnded()
            PlayerConstants.PlayerState.VIDEO_CUED -> _state.update { it.copy(isBuffering = false, isPlaying = false) }
            else -> Unit
        }
    }

    override fun onYtSecond(second: Float) {
        if (_state.value.engine == Engine.YOUTUBE) _progress.update { it.copy(positionMs = (second * 1000).toLong()) }
    }

    override fun onYtDuration(duration: Float) {
        if (_state.value.engine == Engine.YOUTUBE && duration > 0) _progress.update { it.copy(durationMs = (duration * 1000).toLong()) }
    }

    override fun onYtError(error: PlayerConstants.PlayerError) {
        val item = _state.value.queue.current ?: return
        val title = item.track.title
        val tried = triedUploads.getOrPut(item.uid) { mutableSetOf() }.apply { add(item.track.playbackRef) }
        // Embedding disabled / removed: quietly try another upload of the same song first.
        if (settings.settings.value.autoReplaceUnavailable && tried.size <= 2) {
            _state.update { it.copy(issue = PlaybackIssue.Unavailable(title, item.track.playbackRef, searching = true), isPlaying = false, isBuffering = true) }
            scope.launch { if (!replaceCurrent(preferredVariant(), tried, announce = true)) markUnavailable(title, item.track.playbackRef) }
        } else markUnavailable(title, item.track.playbackRef)
    }

    private fun markUnavailable(title: String, videoId: String?) {
        val skipAt = clock.now() + 6_000
        _state.update { it.copy(issue = PlaybackIssue.Unavailable(title, videoId, searching = false, autoSkipAt = skipAt), isPlaying = false, isBuffering = false) }
        scope.launch {
            delay(6_000)
            val i = _state.value.issue
            if (i is PlaybackIssue.Unavailable && i.autoSkipAt == skipAt) next()
        }
    }

    private fun preferredVariant() =
        if (settings.settings.value.preferVideos) com.arnav.music.domain.model.MediaVariant.VIDEO else com.arnav.music.domain.model.MediaVariant.SONG

    /** "Find another upload" from the error card. */
    fun findAnotherUpload() {
        val item = _state.value.queue.current ?: return
        val tried = triedUploads.getOrPut(item.uid) { mutableSetOf() }.apply { add(item.track.playbackRef) }
        _state.update { it.copy(issue = PlaybackIssue.Unavailable(item.track.title, item.track.playbackRef, searching = true)) }
        scope.launch { if (!replaceCurrent(null, tried, announce = true)) markUnavailable(item.track.title, item.track.playbackRef) }
    }

    /** YouTube Music-style Song/Video switch: swaps to the other upload and keeps the position. */
    fun switchVariant(want: com.arnav.music.domain.model.MediaVariant) {
        val item = _state.value.queue.current ?: return
        if (item.track.source != SourceType.YOUTUBE || _switching.value) return
        if (item.track.variant == want) return
        _switching.value = true
        scope.launch {
            val ok = replaceCurrent(want, setOf(item.track.playbackRef), announce = false, keepPosition = true)
            _switching.value = false
            if (!ok) _state.update { it.copy(issue = PlaybackIssue.VariantNotFound(want)) }
        }
    }

    private suspend fun replaceCurrent(
        want: com.arnav.music.domain.model.MediaVariant?,
        exclude: Set<String>,
        announce: Boolean,
        keepPosition: Boolean = false,
    ): Boolean {
        val item = _state.value.queue.current ?: return false
        val alt = runCatching { resolver.alternative(item.track, want, exclude) }.getOrNull() ?: return false
        if (_state.value.queue.current?.uid != item.uid) return false
        val position = if (keepPosition) _progress.value.positionMs else 0L
        library.remember(listOf(alt))
        _state.update { s -> s.copy(queue = s.queue.replaceAt(s.queue.currentIndex, alt), issue = if (announce) PlaybackIssue.Replaced(alt.title, alt.variant) else null) }
        persistQueue()
        sessionTrack = alt
        _progress.value = Progress(position, alt.durationMs ?: 0)
        youtube.load(alt.playbackRef, position / 1000f, autoplay = true)
        if (announce) scope.launch { delay(3_500); if (_state.value.issue is PlaybackIssue.Replaced) _state.update { it.copy(issue = null) } }
        return true
    }
    // endregion

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val s = _state.value
                if (s.engine == Engine.LOCAL) {
                    controller?.let { c ->
                        val p = Progress(c.currentPosition.coerceAtLeast(0), c.duration.takeIf { it > 0 } ?: (s.current?.durationMs ?: 0))
                        _progress.value = p
                        smartTick(c, _state.value, p)
                    }
                }
                val speed = if (s.engine == Engine.LOCAL) controller?.playbackParameters?.speed ?: 1f else 1f
                sessionListenedMs += listeningMeter.sample(android.os.SystemClock.elapsedRealtime(), _progress.value.positionMs, s.isPlaying, s.isBuffering, speed)
                delay(if (s.isPlaying) 250 else 1_000)
            }
        }
    }

    private fun beginSession(track: Track) {
        sessionTrack = track
        sessionStartedAt = clock.now()
        sessionListenedMs = 0
        listeningMeter.reset()
    }

    /** Converts the finished listen into a local PlayEvent. Under 5 s is noise and ignored. */
    private fun finishSession(skipped: Boolean, completedNaturally: Boolean = false) {
        val t = sessionTrack ?: return
        sessionTrack = null
        val listened = sessionListenedMs
        if (listened < 5_000) return
        val duration = t.durationMs ?: _progress.value.durationMs.takeIf { it > 0 }
        val completed = completedNaturally || (duration != null && listened >= duration * 0.8)
        val event = PlayEvent(t.id, t.artistKey, sessionStartedAt, listened, duration, completed, skipped = skipped && !completed && listened < 30_000 + (duration ?: 0) / 3)
        scope.launch { runCatching { library.recordPlay(event, t.source.name) } }
        // A hand skip before the end (not a failed track): remember the second, whatever the play counts as.
        val position = _progress.value.positionMs
        if (skipped && _state.value.issue !is PlaybackIssue.Unavailable && SkipSpots.isMeaningful(position, duration)) {
            val mark = SkipMarkEntity(trackId = t.id.value, playStartedAt = sessionStartedAt, positionMs = position, durationMs = duration, skippedAt = clock.now())
            scope.launch { runCatching { skipMarks.insert(mark) } }
        }
    }

    private fun persistQueue() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            val q = _state.value.queue
            runCatching {
                prefs.edit()
                    .putString("queue", json.encodeToString(ListSerializer(Track.serializer()), q.items.map { it.track }.take(300)))
                    .putInt("index", q.currentIndex)
                    .apply()
            }
        }
    }

    fun stopForRestore() {
        cancelSmartFade(restoreVolume = true)
        persistJob?.cancel()
        sessionTrack = null
        controller?.stop()
        controller?.clearMediaItems()
        youtube.pause()
        _state.value = PlayerState()
        _progress.value = Progress()
    }
    fun reloadStoredQueue() {
        persistJob?.cancel()
        _state.value = PlayerState()
        _progress.value = Progress()
        restoreQueue()
    }
    private fun restoreQueue() {
        runCatching {
            val raw = prefs.getString("queue", null) ?: return
            val tracks = json.decodeFromString(ListSerializer(Track.serializer()), raw)
            if (tracks.isEmpty()) return
            val q = QueueState().replace(tracks, prefs.getInt("index", 0))
            val engine = if (q.current?.track?.source == SourceType.YOUTUBE) Engine.YOUTUBE else Engine.LOCAL
            _state.value = PlayerState(queue = q, engine = Engine.NONE, capabilities = if (engine == Engine.YOUTUBE) PlaybackCapabilities.YouTubeEmbed else PlaybackCapabilities.LocalMedia)
            _progress.value = Progress(0, q.current?.track?.durationMs ?: 0)
        }
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setUri(Uri.parse(playbackRef))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .build(),
        )
        .build()
}
