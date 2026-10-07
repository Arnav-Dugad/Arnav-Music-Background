package com.arnav.music

import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.TAG
import com.arnav.music.testing.click
import com.arnav.music.testing.exists
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.waitFor
import com.arnav.music.testing.waitForAny
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() = rule.skipOnboardingIfShown()

    /** Terminal search states — works with or without a YouTube key, online or offline. */
    private val settledStates = arrayOf(
        M.title("Connect YouTube search"),
        M.title("You're offline"),
        M.title("Search is resting for today"),
        M.title("Search hit a snag"),
        M.title("No matches"),
        M.title("Songs"),
        M.title("Artists"),
        M.title("Playlists on YouTube"),
        M.title("In your library"),
    )

    @Test
    fun homeSearchBarOpensSearchWithFocusedField() {
        rule.click(M.HOME_SEARCH_BAR)
        rule.waitFor(M.TEXT_FIELD and isFocused())
        rule.waitFor(M.icon("Back"))
        // Empty query shows the intro / recent searches, not results.
        rule.waitForAny(hasText("Your next obsession starts here"), M.title("Recent searches"))
    }

    @Test
    fun typingShowsAGracefulStateEvenWithoutAnApiKey() {
        rule.click(M.HOME_SEARCH_BAR)
        rule.waitFor(M.TEXT_FIELD and isFocused())
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("arnav lofi rain")

        val state = rule.waitForAny(*settledStates, timeout = 45_000)
        Log.i(TAG, "Search settled on: ${state.description}")

        // Filters can be switched while results / errors are shown.
        for (filter in listOf("Songs", "Videos", "Artists", "Playlists", "All")) {
            rule.click(M.role(Role.Tab) and hasText(filter))
            rule.waitFor(M.role(Role.Tab) and hasText(filter) and hasStateDescription("Selected"))
        }
        rule.waitForAny(*settledStates, timeout = 45_000)

        // Clearing the field goes back to the empty state.
        rule.click(M.icon("Clear"))
        rule.waitForAny(hasText("Your next obsession starts here"), M.title("Recent searches"))

        // Leave Search with the in-app back arrow.
        rule.click(M.icon("Back"))
        rule.waitFor(M.HOME_ROOT)
    }

    @Test
    fun missingKeyStateLinksToSourcesSettings() {
        rule.click(M.HOME_SEARCH_BAR)
        rule.waitFor(M.TEXT_FIELD and isFocused())
        rule.onAllNodes(M.TEXT_FIELD).onFirst().performTextInput("calm piano")
        rule.waitForAny(*settledStates, timeout = 45_000)
        // Only meaningful in builds without a YouTube key (forks / local builds).
        if (rule.exists(M.title("Connect YouTube search"))) {
            rule.click(M.clickableText("Add key"))
            rule.waitFor(M.title("Sources"))
            rule.click(M.icon("Back"))
            rule.waitFor(M.TEXT_FIELD)
        }
    }
}
