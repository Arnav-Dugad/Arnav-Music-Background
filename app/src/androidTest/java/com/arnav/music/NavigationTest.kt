package com.arnav.music

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.click
import com.arnav.music.testing.exists
import com.arnav.music.testing.goToTab
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.isShown
import com.arnav.music.testing.pressBack
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.waitFor
import com.arnav.music.testing.waitGone
import com.arnav.music.testing.waitUntilTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() = rule.skipOnboardingIfShown()

    private fun openSettingsFromProfile() {
        rule.click(M.HOME_ROOT) // the profile avatar in Home's header
        rule.waitFor(M.PROFILE_ROOT)
        rule.click(M.icon("Settings"))
        rule.waitFor(M.SETTINGS_ROOT)
    }

    private fun openSettingsPage(title: String) {
        rule.click(M.clickableText(title) and M.role(Role.Button))
        rule.waitFor(M.title(title))
        rule.waitGone(M.SETTINGS_ROOT)
    }

    @Test
    fun everyBottomTabOpensItsScreen() {
        for (tab in listOf("Explore", "Library", "Arnav AI", "Home", "Library", "Explore", "Home")) {
            rule.goToTab(tab)
            rule.waitFor(M.tab(tab) and isSelected())
            for (other in M.TABS.filter { it != tab }) {
                assertFalse("$other root still visible on the $tab tab", rule.exists(M.tabRoot(other)))
            }
        }
    }

    @Test
    fun backFromSettingsSubPageReturnsToSettingsRoot() {
        openSettingsFromProfile()

        // System back from a sub-page → Settings root (regression: it used to jump back to Profile).
        openSettingsPage("Appearance")
        rule.pressBack()
        rule.waitFor(M.SETTINGS_ROOT)
        assertFalse("Back from Appearance went past Settings", rule.exists(M.PROFILE_ROOT))

        // The in-app back arrow behaves the same.
        openSettingsPage("Privacy")
        rule.click(M.icon("Back"))
        rule.waitFor(M.SETTINGS_ROOT)
        assertFalse(rule.exists(M.PROFILE_ROOT))

        // Two levels deep, then unwind one level at a time.
        openSettingsPage("Accessibility")
        rule.pressBack()
        rule.waitFor(M.SETTINGS_ROOT)
        rule.pressBack()
        rule.waitFor(M.PROFILE_ROOT)
        rule.pressBack()
        rule.waitFor(M.HOME_ROOT)
    }

    @Test
    fun tabFromSettingsSubPageLandsOnTabRoot() {
        openSettingsFromProfile()
        openSettingsPage("Playback")

        rule.goToTab("Library")
        rule.waitFor(M.tab("Library") and isSelected())
        assertFalse(rule.exists(M.title("Playback")))

        // Back from a tab root returns to Home, not to the old settings stack.
        rule.pressBack()
        rule.waitFor(M.HOME_ROOT)
        assertFalse(rule.exists(M.SETTINGS_ROOT))

        // Home tab from deep inside Settings lands on Home's root.
        openSettingsFromProfile()
        openSettingsPage("About")
        rule.goToTab("Home")
        assertFalse(rule.exists(M.title("About")))
        assertFalse(rule.exists(M.SETTINGS_ROOT))
    }

    @Test
    fun tabFromLibrarySubPageLandsOnLibraryRoot() {
        rule.goToTab("Library")
        // A Library sub-page (History collection).
        rule.click(M.clickableText("History") and M.role(Role.Button))
        rule.waitGone(M.LIBRARY_ROOT)
        rule.waitFor(M.icon("Back"))

        // Reselecting the current tab from a sub-page goes to the tab's root.
        rule.click(M.tab("Library"))
        rule.waitFor(M.LIBRARY_ROOT)

        // Sub-page → another tab → back to Library shows Library's root, not the old sub-page.
        rule.click(M.clickableText("History") and M.role(Role.Button))
        rule.waitGone(M.LIBRARY_ROOT)
        rule.goToTab("Explore")
        rule.goToTab("Library")

        // Explore → Search → Explore tab lands on Explore's root.
        rule.goToTab("Explore")
        rule.click(M.clickLabel("Search"))
        rule.waitFor(M.TEXT_FIELD)
        rule.goToTab("Explore")
        rule.waitGone(M.TEXT_FIELD)
    }

    @Test
    fun tabReselectScrollsToTopAndKeepsWorking() {
        rule.goToTab("Explore")
        val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        rule.waitFor(verticalList)
        rule.onAllNodes(verticalList).onFirst().performScrollToIndex(12)
        rule.waitUntilTrue("Explore's title scrolled out of view") { !rule.isShown(M.EXPLORE_ROOT) }

        // Reselect: scrolls back to the top.
        rule.click(M.tab("Explore"))
        rule.waitUntilTrue("Explore's title visible again after reselecting the tab") { rule.isShown(M.EXPLORE_ROOT) }

        // Reselecting repeatedly is harmless, and other tabs keep working afterwards.
        repeat(3) { rule.click(M.tab("Explore")) }
        rule.waitFor(M.EXPLORE_ROOT)
        rule.click(M.tab("Home"))
        rule.waitFor(M.HOME_ROOT)
        repeat(2) { rule.click(M.tab("Home")) }
        rule.waitFor(M.HOME_ROOT)
        rule.goToTab("Library")
        rule.goToTab("Arnav AI")
        rule.waitUntilTrue("only one tab is selected") { rule.nodesCount(M.role(Role.Tab) and isSelected() and isBottomTab()) == 1 }
    }

    private fun isBottomTab(): SemanticsMatcher =
        M.TABS.map { hasText(it) }.reduce { a, b -> a or b }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.nodesCount(m: SemanticsMatcher): Int =
        onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false).size
}
