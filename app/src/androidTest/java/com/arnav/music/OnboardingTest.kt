package com.arnav.music

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.STARTUP_TIMEOUT
import com.arnav.music.testing.click
import com.arnav.music.testing.exists
import com.arnav.music.testing.goToTab
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.waitFor
import com.arnav.music.testing.waitForAny
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun walkThroughEveryOnboardingStep() {
        // Fresh data (orchestrator clears the package) → onboarding; tolerate a pre-onboarded device.
        rule.waitForAny(M.clickableText("Get started"), M.tab("Home"), timeout = STARTUP_TIMEOUT)
        if (rule.exists(M.clickableText("Get started"))) {
            rule.click(M.clickableText("Get started"))

            // Step 1: look.
            rule.waitFor(hasText("Choose your look"))
            rule.click(M.role(Role.Tab) and hasText("Dark"))
            rule.waitFor(M.role(Role.Tab) and hasText("Dark") and hasStateDescription("Selected"))
            rule.click(M.role(Role.Tab) and hasText("Subtle glass"))
            rule.click(M.clickableText("Continue"))

            // Step 2: moods.
            rule.waitFor(hasText("What do you reach for?"))
            rule.click(M.role(Role.Tab) and hasText("Calm"))
            rule.waitFor(M.role(Role.Tab) and hasText("Calm") and hasStateDescription("Selected"))
            rule.click(M.clickableText("Continue"))

            // Step 3: on-device music — permission was granted by the rule, so no system dialog.
            rule.waitFor(hasText("Bring your own library"))
            rule.waitFor(hasText("Access allowed"))
            rule.click(M.clickableText("Continue"))

            // Step 4: account (optional).
            rule.waitFor(hasText("Sync across devices?"))
            rule.click(M.clickableText("Continue without an account"))
        }
        rule.waitFor(M.HOME_ROOT, STARTUP_TIMEOUT)
        assertFalse("Onboarding must not come back after finishing it", rule.exists(M.ONBOARDING_SKIP))
        // The scaffold works right after onboarding.
        rule.goToTab("Library")
        rule.goToTab("Home")
    }

    @Test
    fun skipLandsOnHome() {
        rule.skipOnboardingIfShown()
        rule.waitFor(M.HOME_SEARCH_BAR)
        assertFalse(rule.exists(M.ONBOARDING_SKIP))
    }
}
