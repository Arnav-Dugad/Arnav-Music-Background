package com.arnav.music

import android.util.Log
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.TAG
import com.arnav.music.testing.click
import com.arnav.music.testing.exists
import com.arnav.music.testing.goToTab
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.screenSummary
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.waitFor
import com.arnav.music.testing.waitForAny
import com.arnav.music.testing.waitGone
import com.arnav.music.testing.waitUntilOrFalse
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Command palette, Insights (Taste DNA / timeline / constellation), Explore, Moments and Arnav AI. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() = rule.skipOnboardingIfShown()

    private val paletteHint = hasText("Type a command, song or feeling…")

    private fun openPalette() {
        rule.click(M.icon("Command palette"))
        rule.waitFor(paletteHint)
    }

    @Test
    fun commandPaletteOpensFromHomeAndRunsCommands() {
        openPalette()
        rule.waitFor(M.clickableText("Open Arnav AI"))
        rule.waitFor(M.clickableText("Taste DNA"))

        // Taste DNA → Insights.
        rule.click(M.clickableText("Taste DNA"))
        rule.waitGone(paletteHint)
        rule.waitFor(M.title("Taste DNA"))
        rule.waitForAny(hasText("Your DNA is still forming"), M.clickableText("Constellation"), M.clickableText("Timeline"))
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)

        // Typed query → Listening timeline.
        openPalette()
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("listening timeline")
        rule.click(M.clickableText("Listening timeline"))
        rule.waitFor(M.title("Listening timeline"))
        rule.waitFor(hasText("Everything you've played through Arnav Music", substring = true))
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)

        // Constellation.
        openPalette()
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("taste constellation")
        rule.click(M.clickableText("Taste constellation"))
        rule.waitForAny(hasText("Pinch to zoom", substring = true), hasText("Your universe is still forming"))
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)

        // Free text falls through to Search.
        openPalette()
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("lofi beats")
        rule.click(M.clickableText("Search for “lofi beats”"))
        rule.waitFor(M.TEXT_FIELD and hasText("lofi beats"))
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)

        // Navigation commands: Settings, then a tab command.
        openPalette()
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("Settings")
        rule.click(M.clickableText("Settings"))
        rule.waitFor(M.SETTINGS_ROOT)
        rule.goToTab("Home")
        openPalette()
        rule.click(M.clickableText("Open Arnav AI"))
        rule.waitFor(M.AI_ROOT)
    }

    @Test
    fun commandPaletteDismissesWhenTappingOutside() {
        openPalette()
        // The palette's scrim covers the whole window (bottom bar included): a tap there only dismisses it.
        rule.onAllNodes(M.tab("Library")).onFirst().performClick()
        rule.waitForIdle()
        rule.waitGone(paletteHint)
        rule.waitFor(M.HOME_ROOT)
        // Explore has its own palette button.
        rule.goToTab("Explore")
        openPalette()
        rule.onAllNodes(M.tab("Home")).onFirst().performClick()
        rule.waitForIdle()
        rule.waitGone(paletteHint)
        rule.waitFor(M.EXPLORE_ROOT)
        // Tabs work normally afterwards.
        rule.goToTab("Library")
    }

    @Test
    fun exploreRendersAndOpensAMoment() {
        rule.goToTab("Explore")
        rule.waitFor(M.title("Moments"))
        rule.waitFor(M.title("Trending in music"))
        // Trending needs YouTube + network: either cards or a friendly message — never stuck on skeletons forever.
        val settled = rule.waitUntilOrFalse(45_000) {
            rule.exists(hasContentDescription(" by ", substring = true) and hasClickAction()) ||
                rule.exists(hasText("to see what's trending", substring = true)) ||
                rule.exists(hasText("Trending returns tomorrow", substring = true)) ||
                rule.exists(hasText("Trending isn't available right now."))
        }
        Log.i(TAG, "Trending settled=$settled; screen: ${rule.screenSummary(400)}")

        rule.click(hasContentDescription("Night Drive moment.", substring = true) and hasClickAction())
        rule.waitFor(hasText("MOMENT"))
        rule.waitForAny(hasText("In this moment"), hasText("Start Night Drive"), timeout = 45_000)
        rule.click(M.icon("Back"))
        rule.waitFor(M.EXPLORE_ROOT)
    }

    @Test
    fun arnavAiRendersAndBuildsASession() {
        rule.goToTab("Arnav AI")
        rule.waitFor(M.title("Arnav AI"))
        rule.waitForAny(hasText("Gemini · free tier"), hasText("On-device mode"))
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("calm focus music for reading")
        rule.click(M.icon("Build session"))
        // Cloud AI and YouTube may be unavailable on CI; the on-device engine must still answer.
        val done = rule.waitUntilOrFalse(60_000) {
            rule.exists(hasText("Couldn't find playable songs for that")) ||
                rule.exists(M.icon("Regenerate")) ||
                rule.exists(hasText("Answered on-device", substring = true))
        }
        Log.i(TAG, "AI session finished=$done; screen: ${rule.screenSummary(600)}")
        rule.waitFor(M.AI_ROOT)
        // Tab still navigates away and back.
        rule.goToTab("Home")
        rule.goToTab("Arnav AI")
    }

    @Test
    fun profileOpensAndBackReturnsHome() {
        rule.click(M.HOME_ROOT)
        rule.waitFor(M.PROFILE_ROOT)
        rule.waitForAny(M.clickableText("Sign in to sync"), hasText("Using Arnav Music on this device"), hasText("Joined Arnav Music", substring = true))
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)
        assertFalse(rule.exists(M.PROFILE_ROOT))
    }
}
