package com.arnav.music

import android.view.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.TestAudioRule
import com.arnav.music.testing.click
import com.arnav.music.testing.device
import com.arnav.music.testing.goToTab
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.pressBack
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.waitFor
import com.arnav.music.testing.waitForAny
import com.arnav.music.testing.waitGone
import com.arnav.music.testing.waitUntilTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * End-to-end local playback with a generated song inserted into MediaStore: Library → On device →
 * play → MorphBar → pause/resume → Now Playing → lyrics (paste LRC) → queue, plus search and rotation.
 *
 * While a song plays, time-synced lyrics redraw every frame, which keeps Compose from ever being
 * idle — so playback is paused before the lyrics/queue checks.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlaybackTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val audio = TestAudioRule()
    @get:Rule(order = 3) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() = rule.skipOnboardingIfShown()

    @After fun stopPlayback() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                GlobalContext.get().get<PlaybackController>().pause()
            }
        }
        runCatching { device.setOrientationNatural() }
        runCatching { device.unfreezeRotation() }
    }

    private val songRow: SemanticsMatcher get() = M.clickableText(audio.title) and !M.MORPH_BAR
    private val morphBarWithSong: SemanticsMatcher get() = M.MORPH_BAR and hasText(audio.title)

    private fun lyricLine(text: String) = hasText(text) or hasContentDescription(text)

    private fun openOnDeviceSongs() {
        rule.goToTab("Library")
        rule.click(M.clickableText("On device") and M.role(Role.Button))
        rule.waitFor(M.title("On this device"))
        rule.waitFor(songRow, 30_000)
    }

    private fun playFromLibrary() {
        openOnDeviceSongs()
        rule.click(songRow)
        rule.waitFor(morphBarWithSong, 30_000)
        rule.waitFor(M.PAUSE, 30_000) // isPlaying
    }

    private fun pause() {
        rule.click(M.PAUSE)
        rule.waitFor(M.PLAY)
    }

    @Test
    fun playLocalSongEndToEnd() {
        playFromLibrary()

        // Play / pause toggles from the MorphBar.
        pause()
        rule.click(M.PLAY)
        rule.waitFor(M.PAUSE)
        pause()

        // Expand Now Playing.
        rule.click(M.MORPH_BAR)
        rule.waitFor(M.COLLAPSE_PLAYER)
        rule.waitFor(hasText("NOW PLAYING"))
        rule.waitFor(M.title(audio.title))
        rule.waitFor(M.PLAY)

        // Lyrics: none in the file → paste a small LRC.
        rule.click(M.icon("Lyrics"))
        rule.waitFor(hasText("No lyrics for this song yet"))
        rule.click(M.clickableText("Paste lyrics"))
        val field = rule.waitForAny(
            M.TEXT_FIELD and hasAnyAncestor(isDialog()),
            M.TEXT_FIELD and hasText("[00:12.00] First line…", substring = true),
        )
        rule.onAllNodes(field).onFirst().performTextReplacement(LRC)
        rule.click(M.clickableText("Save"))
        rule.waitFor(lyricLine("Arnav first line"))
        rule.waitFor(lyricLine("Arnav second line"))
        rule.waitFor(lyricLine("Arnav third line"))
        rule.waitFor(M.icon("Hide lyrics"))

        // Back closes lyrics first, then collapses the player.
        rule.pressBack()
        rule.waitFor(M.icon("Lyrics"))
        rule.waitFor(M.COLLAPSE_PLAYER)
        rule.pressBack()
        rule.waitGone(M.COLLAPSE_PLAYER)
        rule.waitFor(morphBarWithSong)

        // Queue panel.
        rule.click(M.MORPH_BAR)
        rule.waitFor(M.COLLAPSE_PLAYER)
        rule.click(M.icon("Queue"))
        rule.waitFor(hasText("Up next"))
        rule.pressBack()
        rule.waitGone(hasText("Up next"))
        rule.waitFor(M.COLLAPSE_PLAYER)

        // Saved lyrics come back when reopening the lyrics view.
        rule.click(M.icon("Lyrics"))
        rule.waitFor(lyricLine("Arnav second line"))
        rule.click(M.icon("Hide lyrics"))
        rule.waitFor(M.icon("Lyrics"))

        // Collapse with the arrow button.
        rule.click(M.COLLAPSE_PLAYER)
        rule.waitGone(M.COLLAPSE_PLAYER)
        rule.waitFor(morphBarWithSong)

        // Track actions sheet from the song row.
        rule.click(M.icon("More options for ${audio.title}"))
        rule.waitFor(M.clickableText("Add to queue"))
        rule.click(M.clickableText("Song details"))
        rule.waitFor(hasText("This device"))
        rule.pressBack()
        rule.waitGone(M.clickableText("Add to queue"))

        // Resume from the MorphBar, then pause again.
        rule.click(M.PLAY)
        rule.waitFor(M.PAUSE)
        pause()
    }

    @Test
    fun searchFindsTheSongInYourLibrary() {
        openOnDeviceSongs()
        rule.goToTab("Home")
        rule.click(M.HOME_SEARCH_BAR)
        rule.waitFor(M.TEXT_FIELD and isFocused())
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput(audio.title)
        rule.waitFor(M.title("In your library"), 30_000)
        rule.click(songRow)
        rule.waitFor(M.PAUSE, 30_000)
        pause()
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)
        rule.waitFor(morphBarWithSong)
    }

    @Test
    fun rotatingNowPlayingKeepsWorking() {
        playFromLibrary()
        pause()
        rule.click(M.MORPH_BAR)
        rule.waitFor(M.COLLAPSE_PLAYER)
        try {
            device.setOrientationLeft()
            rule.waitUntilTrue("display rotated to landscape") { device.displayRotation == Surface.ROTATION_90 }
            rule.waitForIdle()
            rule.waitFor(M.COLLAPSE_PLAYER)
            rule.waitFor(M.PLAY)
            rule.click(M.icon("Lyrics"))
            rule.waitFor(hasText("No lyrics for this song yet"))
            rule.pressBack()
            rule.waitFor(M.icon("Lyrics"))

            device.setOrientationNatural()
            rule.waitUntilTrue("display back to portrait") { device.displayRotation == Surface.ROTATION_0 }
            rule.waitForIdle()
            rule.waitFor(M.COLLAPSE_PLAYER)
            rule.pressBack()
            rule.waitFor(morphBarWithSong)

            // Collapsed player + navigation rail in landscape.
            device.setOrientationLeft()
            rule.waitUntilTrue("display rotated to landscape") { device.displayRotation == Surface.ROTATION_90 }
            rule.waitForIdle()
            rule.waitFor(M.MORPH_BAR)
            rule.goToTab("Explore")
            rule.goToTab("Home")
            rule.click(M.MORPH_BAR)
            rule.waitFor(M.COLLAPSE_PLAYER)
            rule.click(M.COLLAPSE_PLAYER)
            rule.waitFor(M.MORPH_BAR)
        } finally {
            runCatching { device.setOrientationNatural() }
            runCatching { device.unfreezeRotation() }
        }
        rule.waitFor(M.tab("Home"))
        rule.goToTab("Library")
    }

    private companion object {
        val LRC = listOf(
            "[ti:Arnav Test Tone]",
            "[00:01.00]Arnav first line",
            "[00:05.00]Arnav second line",
            "[00:10.00]Arnav third line",
        ).joinToString("\n")
    }
}
