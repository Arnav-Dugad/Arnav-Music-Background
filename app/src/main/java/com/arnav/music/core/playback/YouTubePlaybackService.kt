package com.arnav.music.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.arnav.music.MainActivity
import com.arnav.music.R
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Media controls and process priority for the experimental IFrame background player. */
class YouTubePlaybackService : Service() {
    private val playback: PlaybackController by inject()
    private val engine: YouTubeEngine by inject()
    private val settings: SettingsRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var wakeLock: PowerManager.WakeLock
    private var hasFocus = false
    private var resumeAfterFocus = false
    private var foreground = false
    private var metadataDuration = -1L
    private var idleJob: Job? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && settings.settings.value.pauseOnDisconnect) {
                resumeAfterFocus = false
                playback.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "YouTube playback", NotificationManager.IMPORTANCE_LOW))
        audio = getSystemService(AudioManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:youtube")
            .apply { setReferenceCounted(false) }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focus@{ change ->
                if (playback.state.value.engine != Engine.YOUTUBE) return@focus
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        if (resumeAfterFocus && playback.state.value.engine == Engine.YOUTUBE) {
                            resumeAfterFocus = false
                            promote()
                            engine.authorizePlayback(true)
                            playback.play()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeAfterFocus = false
                        hasFocus = false
                        engine.authorizePlayback(false)
                        playback.pause()
                        audio.abandonAudioFocusRequest(focusRequest)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeAfterFocus = playback.state.value.isPlaying
                        hasFocus = false
                        engine.authorizePlayback(false)
                        playback.pause()
                    }
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        session = MediaSession(this, "ArnavMusicYouTube").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resumeAfterFocus = false; promote(); playback.play() }
                override fun onPause() { resumeAfterFocus = false; playback.pause() }
                override fun onSkipToNext() { playback.next() }
                override fun onSkipToPrevious() { playback.previous() }
                override fun onSeekTo(pos: Long) { playback.seekTo(pos) }
                override fun onStop() { playback.clearQueue(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            setSessionActivity(openApp())
            isActive = true
        }
        promote()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            playback.state.collect { state ->
                if (state.engine != Engine.YOUTUBE || state.current == null) {
                    stopSelf()
                    return@collect
                }
                val track = state.current!!
                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id.value)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putString(MediaMetadata.METADATA_KEY_ART_URI, track.artworkUrl)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, playback.progress.value.durationMs)
                    .build())
                metadataDuration = playback.progress.value.durationMs
                if (state.isPlaying) {
                    idleJob?.cancel(); idleJob = null
                    promote()
                    obtainFocus()
                } else if (idleJob == null && !resumeAfterFocus) {
                    // Retain a resumable media notification, but do not keep a paused FGS forever.
                    idleJob = scope.launch {
                        delay(5 * 60_000L)
                        engine.authorizePlayback(false)
                        audio.abandonAudioFocusRequest(focusRequest)
                        hasFocus = false
                        stopForeground(STOP_FOREGROUND_DETACH)
                        foreground = false
                    }
                }
                updateWakeLock()
                updateSession()
                notifications.notify(NOTIFICATION, notification())
            }
        }
        scope.launch {
            playback.progress.collect { progress ->
                if (progress.durationMs != metadataDuration) {
                    session.controller.metadata?.let { metadata ->
                        session.setMetadata(MediaMetadata.Builder(metadata)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, progress.durationMs).build())
                    }
                    metadataDuration = progress.durationMs
                }
                updateSession()
                updateWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        when (intent?.action) {
            ACTION_TOGGLE -> { resumeAfterFocus = false; playback.togglePlay() }
            ACTION_NEXT -> playback.next()
            ACTION_PREVIOUS -> playback.previous()
            ACTION_STOP -> { playback.clearQueue(); stopSelf() }
            ACTION_START -> if (playback.state.value.engine == Engine.YOUTUBE) obtainFocus()
        }
        return START_NOT_STICKY // Never restart music unexpectedly after Android kills the process.
    }

    private fun obtainFocus() {
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        engine.authorizePlayback(hasFocus)
        if (!hasFocus) playback.pause()
    }

    private fun updateWakeLock() {
        val state = playback.state.value
        if (state.engine == Engine.YOUTUBE && state.isPlaying && hasFocus) {
            // Bounded acquisition; the progress flow renews only if playback is still active.
            if (!wakeLock.isHeld) wakeLock.acquire(10 * 60_000L)
        } else if (wakeLock.isHeld) wakeLock.release()
    }

    private fun updateSession() {
        val state = playback.state.value
        val progress = playback.progress.value
        val status = when {
            state.isBuffering -> PlaybackState.STATE_BUFFERING
            state.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(status, progress.positionMs, if (state.isPlaying && !state.isBuffering) 1f else 0f)
            .build())
    }

    private fun promote() {
        if (foreground) return
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification())
        foreground = true
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(this, code,
        Intent(this, YouTubePlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val state = playback.state.value
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_arnav)
            .setContentTitle(state.current?.title ?: "Arnav Music")
            .setContentText(state.current?.artist ?: "YouTube playback")
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.isPlaying) "Pause" else "Play", action(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP, 4)).build())
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        if (playback.state.value.engine == Engine.YOUTUBE) playback.pause()
        engine.authorizePlayback(false)
        if (playback.state.value.engine != Engine.YOUTUBE) engine.detach()
        audio.abandonAudioFocusRequest(focusRequest)
        if (wakeLock.isHeld) wakeLock.release()
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "youtube_background"
        private const val NOTIFICATION = 2102
        const val ACTION_START = "com.arnav.music.youtube.START"
        private const val ACTION_TOGGLE = "com.arnav.music.youtube.TOGGLE"
        private const val ACTION_NEXT = "com.arnav.music.youtube.NEXT"
        private const val ACTION_PREVIOUS = "com.arnav.music.youtube.PREVIOUS"
        private const val ACTION_STOP = "com.arnav.music.youtube.STOP"
    }
}
