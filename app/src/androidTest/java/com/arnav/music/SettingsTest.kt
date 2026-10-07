package com.arnav.music

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.TAG
import com.arnav.music.testing.click
import com.arnav.music.testing.device
import com.arnav.music.testing.exists
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.spokenText
import com.arnav.music.testing.nodes
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.targetPackage
import com.arnav.music.testing.waitFor
import com.arnav.music.testing.waitUntilTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    /** Settings pages and the title each one shows. Rows added later are discovered and opened too. */
    private val knownPages = linkedMapOf(
        "Account" to "Account", "Appearance" to "Appearance", "Playback" to "Playback", "Arnav AI" to "Arnav AI",
        "Sources" to "Sources", "Library" to "Library", "Data & sync" to "Data & sync", "Notifications" to "Notifications",
        "Privacy" to "Privacy", "Usage & quotas" to "Usage & quotas", "Accessibility" to "Accessibility",
        "Performance" to "Performance", "App updates" to "App updates", "About" to "About", "Developer" to "Developer",
    )

    @Before fun start() {
        rule.skipOnboardingIfShown()
        rule.click(M.HOME_ROOT)
        rule.waitFor(M.PROFILE_ROOT)
        rule.click(M.icon("Settings"))
        rule.waitFor(M.SETTINGS_ROOT)
    }

    /** Navigation rows on the Settings root: role=Button, clickable, with a visible title. */
    private fun rootRowTitles(): List<String> = rule.nodes(M.role(Role.Button) and hasClickAction())
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        .filter { it.isNotBlank() }
        .distinct()

    private fun backToSettingsRoot() {
        if (device.currentPackageName != targetPackage) device.pressBack()
        rule.click(M.icon("Back"))
        rule.waitFor(M.SETTINGS_ROOT)
    }

    @Test
    fun everySettingsPageOpens() {
        val rows = rootRowTitles()
        Log.i(TAG, "Settings rows: $rows")
        for (expected in listOf("Account", "Appearance", "Playback", "Privacy", "About")) {
            assertTrue("Settings root lacks '$expected' (found $rows)", expected in rows)
        }
        for (row in rows) {
            rule.click(M.clickableText(row) and M.role(Role.Button))
            rule.waitUntilTrue("page '$row' replaced the Settings root") { !rule.exists(M.SETTINGS_ROOT) }
            knownPages[row]?.let { title -> rule.waitFor(M.title(title)) }
            rule.waitFor(M.icon("Back"))
            Log.i(TAG, "Opened settings page '$row'")
            backToSettingsRoot()
        }
    }

    @Test
    fun togglesFlipOnEveryPage() {
        var flipped = 0
        for (row in rootRowTitles()) {
            rule.click(M.clickableText(row) and M.role(Role.Button))
            rule.waitUntilTrue("page '$row' replaced the Settings root") { !rule.exists(M.SETTINGS_ROOT) }
            rule.waitFor(M.icon("Back"))
            val toggles = rule.nodes(isToggleable() and isEnabled() and hasClickAction())
            val target = toggles.firstOrNull()
            if (target != null) {
                val name = target.spokenText()
                val wasOn = target.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
                val byName = isToggleable() and hasText(target.config[SemanticsProperties.Text].first().text)
                rule.click(byName)
                rule.waitFor(byName and (if (wasOn) isOff() else isOn()))
                rule.click(byName)
                rule.waitFor(byName and (if (wasOn) isOn() else isOff()))
                flipped++
                Log.i(TAG, "Flipped '$name' on '$row' and back")
            }
            backToSettingsRoot()
        }
        assertTrue("Expected toggles on several settings pages, flipped $flipped", flipped >= 5)
    }

    @Test
    fun accessibilityTogglesPersistAcrossNavigation() {
        rule.click(M.clickableText("Accessibility") and M.role(Role.Button))
        rule.waitFor(M.title("Accessibility"))
        val highContrast = isToggleable() and hasText("High contrast")
        rule.waitFor(highContrast)
        val before = rule.nodes(highContrast).first().config.getOrNull(SemanticsProperties.ToggleableState)
        rule.click(highContrast)
        val after = if (before == ToggleableState.On) isOff() else isOn()
        rule.waitFor(highContrast and after)
        backToSettingsRoot()
        // Navigator ignores a repeat of the same route within 600 ms (double-tap guard).
        SystemClock.sleep(800)
        rule.click(M.clickableText("Accessibility") and M.role(Role.Button))
        rule.waitFor(highContrast and after)
        // Restore.
        rule.click(highContrast)
        rule.waitFor(highContrast and (if (before == ToggleableState.On) isOn() else isOff()))
    }

    @Test
    fun appearanceChoicesApply() {
        rule.click(M.clickableText("Appearance") and M.role(Role.Button))
        rule.waitFor(M.title("Appearance"))
        for (choice in listOf("Dark", "Light", "System")) {
            val pill = M.role(Role.Tab) and hasText(choice)
            rule.click(pill)
            rule.waitFor(pill and hasStateDescription("Selected"))
        }
        for (choice in listOf("Pure")) {
            val pill = M.role(Role.Tab) and hasText(choice)
            rule.click(pill)
            rule.waitFor(pill and hasStateDescription("Selected"))
        }
        backToSettingsRoot()
    }
}
