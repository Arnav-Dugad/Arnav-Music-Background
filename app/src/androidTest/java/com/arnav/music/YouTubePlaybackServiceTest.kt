package com.arnav.music

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.playback.YouTubeEngine
import com.arnav.music.core.playback.YouTubePlaybackService
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.testing.grantAppPermissions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Real Android service/focus handoff, with an offline IFrame transport (no YouTube quota/network). */
@RunWith(AndroidJUnit4::class)
class YouTubePlaybackServiceTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val playback get() = GlobalContext.get().get<PlaybackController>()
    private val engine get() = GlobalContext.get().get<YouTubeEngine>()
    private var embeddedFocus: AudioFocusRequest? = null
    private val loads = AtomicInteger()
    private val pauses = AtomicInteger()

    private fun blockNetwork(view: View) {
        if (view is WebView) { view.settings.blockNetworkLoads = true; view.stopLoading() }
        if (view is ViewGroup) for (i in 0 until view.childCount) blockNetwork(view.getChildAt(i))
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            playback.clearQueue()
            embeddedFocus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        }
    }

    @Test fun embeddedPlayerTakingAudioFocusDoesNotPauseItsOwnService() {
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
        embeddedFocus = focus
        lateinit var iframe: YouTubePlayer
        iframe = Proxy.newProxyInstance(YouTubePlayer::class.java.classLoader, arrayOf(YouTubePlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "loadVideo", "play" -> {
                    // Chromium makes this separate request when the IFrame begins audio.
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus))
                    loads.incrementAndGet()
                    engine.listener.onStateChange(iframe, PlayerConstants.PlayerState.PLAYING)
                    null
                }
                "pause" -> {
                    pauses.incrementAndGet()
                    engine.listener.onStateChange(iframe, PlayerConstants.PlayerState.PAUSED)
                    null
                }
                "addListener", "removeListener" -> true
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "Offline IFrame"
                else -> null
            }
        } as YouTubePlayer
        instrumentation.runOnMainSync {
            blockNetwork(engine.playerView())
            engine.listener.onReady(iframe)
            playback.playTracks(listOf(Track(TrackId.youtube("offline-test"), "Offline focus test", "Test", playbackRef = "offline-test")))
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (loads.get() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Foreground service must authorize the initial load", loads.get() > 0)
        // Allow Android's queued focus-loss callback to run: the previous build pauses here.
        SystemClock.sleep(750)
        instrumentation.runOnMainSync {
            assertTrue("The embedded player's focus request must not pause its own song", playback.state.value.isPlaying)
            assertEquals("No service-driven pause at startup", 0, pauses.get())
            @Suppress("DEPRECATION")
            val service = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                .firstOrNull { it.service.className == YouTubePlaybackService::class.java.name }
            assertTrue("YouTube playback must have a foreground service", service?.foreground == true)
            val notification = context.getSystemService(android.app.NotificationManager::class.java)
                .activeNotifications.firstOrNull { it.id == YouTubePlaybackService.NOTIFICATION }?.notification
            assertTrue("Playback notification must be posted", notification != null)
            assertEquals("Offline focus test", notification!!.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
            val token = notification.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)
            assertTrue("System UI needs a valid media-session token", token != null)
            val controls = android.media.session.MediaController(context, token!!)
            assertEquals(android.media.session.PlaybackState.STATE_PLAYING, controls.playbackState?.state)
            assertEquals("Offline focus test", controls.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
            playback.pause()
            assertTrue(!playback.state.value.isPlaying)
            playback.play()
        }
        SystemClock.sleep(750)
        instrumentation.runOnMainSync {
            assertTrue("Resume must survive the same focus handoff", playback.state.value.isPlaying)
            assertEquals("Only the explicit user pause", 1, pauses.get())
        }
    }
}
