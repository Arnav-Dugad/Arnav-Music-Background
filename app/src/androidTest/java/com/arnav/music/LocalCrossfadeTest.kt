package com.arnav.music

import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnav.music.core.playback.LocalCrossfade
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.testing.TestAudioRule
import com.arnav.music.testing.grantAppPermissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class LocalCrossfadeTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val audio = TestAudioRule()
    @get:Rule(order = 2) val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val playback get() = GlobalContext.get().get<PlaybackController>()
    @After fun cleanup() { instrumentation.runOnMainSync { playback.clearQueue() } }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(40)
        assertTrue(message, predicate())
    }
    @Test fun bothLocalDecodersOverlapAndPauseStopsTheTail() {
        val settings = GlobalContext.get().get<SettingsRepository>()
        runBlocking {
            settings.loaded.first { it }
            settings.update { it.copy(crossfadeMs = 6000, fadeMs = 0, smartTransitions = false, endlessRadio = false) }
            settings.settings.first { it.crossfadeMs == 6000 }
        }
        val track = Track(TrackId.local(audio.uri!!.lastPathSegment!!.toLong()), audio.title,
            "Test artist", durationMs = 45000, playbackRef = audio.uri.toString())
        instrumentation.runOnMainSync { playback.playTracks(listOf(track, track)) }
        waitFor("First decoder starts") { playback.state.value.isPlaying && !playback.state.value.isBuffering }
        instrumentation.runOnMainSync { playback.seekTo(37000) }
        waitFor("Two decoders must actually play with nonzero gains") {
            val s = LocalCrossfade.status.value
            s.overlapping && s.outgoingGain > 0.05f && s.incomingGain > 0.05f
        }
        assertEquals("Queue advances to incoming track", 1, playback.state.value.queue.currentIndex)
        instrumentation.runOnMainSync { playback.pause() }
        waitFor("Pause stops the outgoing decoder too") { !LocalCrossfade.status.value.overlapping }
        assertFalse(playback.state.value.isPlaying)
    }
}
