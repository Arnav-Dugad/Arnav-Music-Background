package com.arnav.music.core.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener

/**
 * Experimental background edition. One application-context WebView survives UI disposal.
 * Playback stays in the IFrame; no stream extraction or download is performed.
 */
class YouTubeEngine(private val context: Context) {
    interface Events {
        fun onYtState(state: PlayerConstants.PlayerState)
        fun onYtSecond(second: Float)
        fun onYtDuration(duration: Float)
        fun onYtError(error: PlayerConstants.PlayerError)
    }

    private var player: YouTubePlayer? = null
    private var pending: Pair<String, Float>? = null
    private var pendingPlay = true
    private var view: YouTubePlayerView? = null
    private var playbackAllowed = false
    private var playRequested = false
    private var position = 0f
    var events: Events? = null
    var currentVideoId: String? = null
        private set

    val isAttached: Boolean get() = player != null

    val listener = object : AbstractYouTubePlayerListener() {
        override fun onReady(youTubePlayer: YouTubePlayer) {
            player = youTubePlayer
            flushPending()
        }
        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) { events?.onYtState(state) }
        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) { position = second; events?.onYtSecond(second) }
        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) { events?.onYtDuration(duration) }
        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) { events?.onYtError(error) }
    }

    fun load(videoId: String, startSeconds: Float = 0f, autoplay: Boolean = true) {
        currentVideoId = videoId
        position = startSeconds
        pending = videoId to startSeconds
        pendingPlay = autoplay
        playRequested = autoplay
        if (autoplay) ensureService()
        playerView()
        flushPending()
    }

    fun play() {
        playRequested = true
        pendingPlay = true
        ensureService()
        playerView()
        if (playbackAllowed) { if (pending != null) flushPending() else player?.play() }
    }
    fun pause() { playRequested = false; pendingPlay = false; player?.pause() }
    fun seekTo(seconds: Float) {
        position = seconds
        pending = pending?.let { it.first to seconds }
        player?.seekTo(seconds)
    }

    /** Main-thread only. The service, rather than a Compose lifecycle, owns this player. */
    fun playerView(): YouTubePlayerView = view ?: YouTubePlayerView(context.applicationContext).apply {
        view = this
        enableAutomaticInitialization = false
        enableBackgroundPlayback(true)
        initialize(listener, true, IFramePlayerOptions.Builder(context.applicationContext)
            .controls(0).fullscreen(0).rel(0).ivLoadPolicy(3).build())
    }

    private fun ensureService() {
        ContextCompat.startForegroundService(context, Intent(context, YouTubePlaybackService::class.java)
            .setAction(YouTubePlaybackService.ACTION_START))
    }

    /** Called only after the media foreground service has been promoted. WebView owns audio focus. */
    fun authorizePlayback(allowed: Boolean) {
        playbackAllowed = allowed
        if (!allowed) { player?.pause(); return }
        if (pending != null) flushPending() else if (playRequested) player?.play()
    }

    private fun flushPending() {
        val p = player ?: return
        val (id, start) = pending ?: return
        if (pendingPlay && !playbackAllowed) return
        pending = null
        if (pendingPlay) p.loadVideo(id, start) else p.cueVideo(id, start)
    }

    /** Called when playback ends or switches to local music, never on UI disposal. */
    fun detach() {
        playbackAllowed = false
        player = null
        view?.release()
        view = null
        currentVideoId?.let { pending = it to position; pendingPlay = false }
        playRequested = false
    }
}
