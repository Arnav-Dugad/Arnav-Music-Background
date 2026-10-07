package com.arnav.music.core.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.arnav.music.MainActivity
import com.arnav.music.core.audio.SingAudioProcessor
import com.arnav.music.core.chapters.ChapterRepository
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.system.AutoLibrary
import com.arnav.music.domain.chapters.Chapter
import com.arnav.music.domain.chapters.ChapterMath
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Native background playback for user-owned local audio: MediaSession, media notification,
 * lock-screen, Bluetooth and headset controls, audio focus, becoming-noisy handling, gapless.
 *
 * It is also a [MediaLibraryService] so Android Auto / Automotive can browse and play the user's
 * **on-device** music ([AutoLibrary]). YouTube content is never routed through here: nothing in
 * the browse tree is YouTube, and requests from other apps only ever resolve to MediaStore tracks.
 *
 * The in-app [PlaybackController] stays the single source of truth: when another app (Auto, the
 * Assistant, a car head unit) asks to play something, the resolved local tracks are handed to
 * [PlaybackController.playTracks], which loads them through its own MediaController.
 */
@OptIn(UnstableApi::class) // Media button preferences and onSetMediaItems are marked unstable in Media3.
class PlaybackService : MediaLibraryService() {
    private val settings: SettingsRepository by inject()
    private val library: LibraryRepository by inject()
    private val local: LocalMediaSource by inject()
    private val chapterRepo: ChapterRepository by inject()
    private val playback: PlaybackController by inject()
    private val autoLibrary by lazy { AutoLibrary(library, local) }
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Media id (= TrackId value) of the current item, for the notification's Like button. */
    private val currentMediaId = MutableStateFlow<String?>(null)
    /** null when nothing is loaded (no Like button), otherwise whether the current item is liked. */
    private var liked: Boolean? = null
    /** Chapters of the current item (media id → chapters), for the chapter buttons. */
    private var chapters: Pair<String, List<Chapter>>? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, SingRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(settings.settings.value.pauseOnDisconnect)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { _audioSessionId.value = audioSessionId }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentMediaId.value = player.currentMediaItem?.mediaId?.ifEmpty { null } }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { currentMediaId.value = player.currentMediaItem?.mediaId?.ifEmpty { null } }
        })
        _audioSessionId.value = player.audioSessionId

        scope.launch {
            settings.settings.map { Triple(it.skipSilence, it.playbackSpeed, it.pauseOnDisconnect) }.distinctUntilChanged().collect { (skip, speed, noisy) ->
                player.skipSilenceEnabled = skip
                player.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
                player.setHandleAudioBecomingNoisy(noisy)
            }
        }

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(activityIntent)
            .build()

        // Chapters of the current (always on-device) item, read from the file itself.
        val currentChapters = currentMediaId.flatMapLatest { id ->
            val item = player.currentMediaItem
            val track = if (id != null && item != null && item.mediaId == id) item.toTrack(id) else null
            if (track == null || track.source != SourceType.LOCAL) flowOf(id to emptyList<Chapter>())
            else chapterRepo.observe(track).map { id to it }
        }

        // Keep the heart (and chapter buttons) in the notification / lock screen / Auto in sync.
        scope.launch {
            combine(currentMediaId, library.likedIds, currentChapters) { id, likedIds, ch ->
                val chaptersForId = if (ch.first == id) ch.second else emptyList()
                Triple(id, id?.let { TrackId(it) in likedIds }, chaptersForId)
            }
                .distinctUntilChanged()
                .collect { (id, likedState, list) ->
                    liked = likedState
                    chapters = if (id != null && list.size >= 2) id to list else null
                    session?.setMediaButtonPreferences(buttons())
                }
        }
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND).add(PREV_CHAPTER_COMMAND).add(NEXT_CHAPTER_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_LIKE -> toggleLikeForCurrent(session)
                ACTION_PREV_CHAPTER -> seekChapter(session.player, next = false)
                ACTION_NEXT_CHAPTER -> seekChapter(session.player, next = true)
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // region Browsing (Android Auto / Automotive / other media browsers) — on-device music only

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // Must be immediate: legacy browsers block the main thread on this call.
            Futures.immediateFuture(LibraryResult.ofItem(autoLibrary.rootItem(recent = params?.isRecent == true), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            autoLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(AutoLibrary.page(autoLibrary.children(parentId), page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            val count = autoLibrary.search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(AutoLibrary.page(autoLibrary.search(query), page, pageSize), params)
        }

        // endregion

        // region Play requests

        /**
         * The app's own MediaController (PlaybackController) sends playable items with a uri: pass
         * them through untouched. Anything else (Auto's playFromMediaId / playFromSearch, another
         * app) is resolved to on-device tracks and handed to the in-app queue.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (isOwnPlayableRequest(controller, mediaItems)) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return future {
                val resolved = autoLibrary.resolve(mediaItems, startIndex) ?: throw UnsupportedOperationException("Nothing on this device matches")
                if (handOff { playback.playTracks(resolved.tracks, resolved.startIndex) }) throw HandledByAppQueue()
                // The in-app queue isn't reachable: play directly so the request still works.
                MediaSession.MediaItemsWithStartPosition(resolved.tracks.map { it.toPlayableItem() }, resolved.startIndex, C.TIME_UNSET)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (isOwnPlayableRequest(controller, mediaItems)) return super.onAddMediaItems(mediaSession, controller, mediaItems)
            return future {
                val resolved = autoLibrary.resolve(mediaItems, 0) ?: throw UnsupportedOperationException("Nothing on this device matches")
                // Never mix a car/remote request into a queue that holds YouTube items: those may only
                // play in the visible app, and the queue would advance into them from the car.
                val queueHasYouTube = runCatching { playback.state.value.queue.items.any { it.track.source == SourceType.YOUTUBE } }.getOrDefault(true)
                if (queueHasYouTube) throw UnsupportedOperationException("The current queue has YouTube items")
                if (handOff { playback.addToQueue(resolved.tracks) }) throw HandledByAppQueue()
                resolved.tracks.map { it.toPlayableItem() }.toMutableList()
            }
        }

        // endregion
    }

    /** True for the app's own MediaController sending items that already carry a playable uri. */
    private fun isOwnPlayableRequest(controller: MediaSession.ControllerInfo, items: List<MediaItem>): Boolean =
        controller.packageName == packageName && items.isNotEmpty() && items.all { it.localConfiguration != null }

    /** Runs [block] against the in-app queue; false when it couldn't (e.g. DI not ready). */
    private inline fun handOff(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Thrown after a request was handed to [PlaybackController] so the session doesn't also apply it.
     * Legacy controllers (Android Auto) ignore the failure; playback state updates as normal.
     */
    private class HandledByAppQueue : Exception("Handled by the in-app queue")

    /** Runs [block] on the service scope (main thread, IO inside AutoLibrary) and exposes it as a future. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        return result
    }

    private fun Track.toPlayableItem(): MediaItem = MediaItem.Builder()
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

    // region Chapters

    private fun seekChapter(player: Player, next: Boolean) {
        val id = player.currentMediaItem?.mediaId ?: return
        val (forId, list) = chapters ?: return
        if (forId != id) return
        val position = player.currentPosition
        val target = if (next) ChapterMath.nextStart(list, position) else ChapterMath.previousStart(list, position)
        if (target != null) player.seekTo(target)
    }

    // endregion

    /** Likes/unlikes the current item; the [LibraryRepository.likedIds] observer then updates the button. */
    private fun toggleLikeForCurrent(session: MediaSession) {
        val item = session.player.currentMediaItem ?: return
        val id = item.mediaId.ifEmpty { return }
        scope.launch {
            val track = withContext(Dispatchers.IO) {
                runCatching { library.tracks(listOf(TrackId(id))).firstOrNull() }.getOrNull()
            } ?: item.toTrack(id) ?: return@launch
            runCatching { library.toggleLike(track) }
        }
    }

    /** Fallback when the library can't resolve the id: rebuild the Track from the item's metadata. */
    private fun MediaItem.toTrack(id: String): Track? {
        val uri = localConfiguration?.uri?.toString() ?: return null
        return Track(
            id = TrackId(id),
            title = mediaMetadata.title?.toString() ?: return null,
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString(),
            artworkUrl = mediaMetadata.artworkUri?.toString(),
            playbackRef = uri,
        )
    }

    /**
     * Previous/next chapter (only when the current item has chapters), then the heart. Custom
     * buttons go to the overflow/custom-action area so the regular previous/next track buttons stay.
     * Android 13+ media controls show the first two custom actions, so on a chaptered track the two
     * chapter buttons take those places; the heart remains in Auto, Wear and the app.
     */
    private fun buttons(): List<CommandButton> {
        val out = ArrayList<CommandButton>(3)
        if (chapters != null) {
            out += CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .setDisplayName("Previous chapter")
                .setSessionCommand(PREV_CHAPTER_COMMAND)
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build()
            out += CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .setDisplayName("Next chapter")
                .setSessionCommand(NEXT_CHAPTER_COMMAND)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build()
        }
        val state = liked
        if (state != null) {
            out += CommandButton.Builder(if (state) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (state) "Remove from liked songs" else "Like")
                .setSessionCommand(LIKE_COMMAND)
                .build()
        }
        return out
    }

    /**
     * The default renderers, with the Sing (vocal reduction) processor added to the audio sink. It
     * runs before the sink's own silence-skipping and speed processors and is a bit-exact
     * pass-through while Sing is off ([com.arnav.music.core.audio.SingMode]).
     */
    private class SingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink? = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(SingAudioProcessor()))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_TOGGLE_LIKE = "com.arnav.music.TOGGLE_LIKE"
        private const val ACTION_PREV_CHAPTER = "com.arnav.music.PREV_CHAPTER"
        private const val ACTION_NEXT_CHAPTER = "com.arnav.music.NEXT_CHAPTER"
        private val LIKE_COMMAND = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
        private val PREV_CHAPTER_COMMAND = SessionCommand(ACTION_PREV_CHAPTER, Bundle.EMPTY)
        private val NEXT_CHAPTER_COMMAND = SessionCommand(ACTION_NEXT_CHAPTER, Bundle.EMPTY)

        private val _audioSessionId = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
        val audioSessionId: StateFlow<Int> = _audioSessionId
    }
}
