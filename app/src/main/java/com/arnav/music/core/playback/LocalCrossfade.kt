package com.arnav.music.core.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.audio.Crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The session player is always the incoming/current player. A focus-free second decoder
 * finishes the outgoing song underneath it. Both belong to the playback service and stop
 * together on pause, seek, queue edits, focus loss, source switches or service destruction.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LocalCrossfade(
    private val player: ExoPlayer,
    private val tail: ExoPlayer,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    scope: CoroutineScope,
) {
    private var preparedId: String? = null
    private var running = false
    private var internalSeek = false
    private var elapsedMs = 0L
    private var lastTick = 0L
    private var transitionMs = 0L
    private var incomingId: String? = null
    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!internalSeek && reason == Player.DISCONTINUITY_REASON_SEEK) cancel()
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) cancel()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) cancel()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!internalSeek && preparedId != null && !running) cancel()
            if (running && mediaItem?.mediaId != incomingId) cancel()
        }
    }
    private val job: Job
    init {
        player.addListener(listener)
        job = scope.launch {
            while (isActive) {
                tick()
                delay(40)
            }
        }
    }

    private fun allowed(): Boolean {
        val s = playback.state.value
        return settings.settings.value.crossfadeMs > 0 && player.isPlaying &&
            s.engine == Engine.LOCAL && s.sleep != SleepTimer.EndOfTrack &&
            s.repeat != RepeatMode.ONE && player.repeatMode != Player.REPEAT_MODE_ONE
    }

    private fun tick() {
        if (running) {
            if (!allowed() && player.playbackState != Player.STATE_BUFFERING) { cancel(); return }
            if (settings.settings.value.crossfadeMs <= 0 || playback.state.value.engine != Engine.LOCAL || !player.playWhenReady) {
                cancel(); return
            }
            val now = SystemClock.elapsedRealtime()
            // Freeze the envelope while either decoder buffers; do not consume overlap silently.
            if (player.isPlaying && tail.isPlaying) elapsedMs += (now - lastTick).coerceIn(0, 120)
            lastTick = now
            if (!player.isPlaying) tail.pause() else if (!tail.playWhenReady) tail.play()
            val (outgoing, incoming) = Crossfade.gains(elapsedMs.toFloat() / transitionMs)
            tail.volume = outgoing
            player.volume = incoming
            _status.value = Status(player.isPlaying && tail.isPlaying, outgoing, incoming)
            if (elapsedMs >= transitionMs || tail.playbackState == Player.STATE_ENDED || tail.playerError != null) cancel()
            return
        }
        if (!allowed() || !player.hasNextMediaItem() || player.duration == C.TIME_UNSET) {
            if (preparedId != null) cancel()
            return
        }
        val current = player.currentMediaItem ?: return
        val next = player.getMediaItemAt(player.nextMediaItemIndex)
        val queue = playback.state.value.queue
        val nextTrack = queue.items.getOrNull(if (queue.hasNext) queue.currentIndex + 1 else 0)?.track
        val nextDuration = nextTrack?.takeIf { it.id.value == next.mediaId }?.durationMs ?: player.duration
        val duration = Crossfade.duration(settings.settings.value.crossfadeMs, player.duration, nextDuration)
        if (duration < 250) return
        val remaining = player.duration - player.currentPosition
        if (remaining <= duration + 3_000 && preparedId != current.mediaId) {
            cancel()
            preparedId = current.mediaId
            tail.setMediaItem(current, player.currentPosition)
            tail.setPlaybackParameters(player.playbackParameters)
            tail.skipSilenceEnabled = player.skipSilenceEnabled
            tail.volume = 0f
            tail.prepare()
        }
        if (remaining > duration || remaining < 200 || tail.playbackState != Player.STATE_READY || tail.playerError != null) return
        // The preloaded tail seeks to the exact point still playing in the session decoder.
        tail.seekTo(player.currentPosition)
        tail.volume = 1f
        tail.play()
        transitionMs = minOf(duration, remaining)
        elapsedMs = 0
        lastTick = SystemClock.elapsedRealtime()
        running = true
        incomingId = next.mediaId
        internalSeek = true
        PlaybackService.crossfadeFromId = current.mediaId
        try {
            player.volume = 0f
            player.seekToNextMediaItem()
        } finally { internalSeek = false }
    }

    fun cancel() {
        _status.value = Status()
        running = false
        preparedId = null
        incomingId = null
        tail.stop()
        tail.clearMediaItems()
        tail.volume = 0f
        player.volume = 1f
    }
    data class Status(val overlapping: Boolean = false, val outgoingGain: Float = 0f, val incomingGain: Float = 1f)
    companion object {
        private val _status = kotlinx.coroutines.flow.MutableStateFlow(Status())
        val status: kotlinx.coroutines.flow.StateFlow<Status> = _status
    }
    fun release() {
        job.cancel()
        player.removeListener(listener)
        cancel()
        tail.release()
    }
}
