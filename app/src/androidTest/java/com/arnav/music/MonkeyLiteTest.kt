package com.arnav.music

import android.content.Intent
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.arnav.music.testing.CrashLoggerRule
import com.arnav.music.testing.M
import com.arnav.music.testing.click
import com.arnav.music.testing.device
import com.arnav.music.testing.exists
import com.arnav.music.testing.grantAppPermissions
import com.arnav.music.testing.spokenText
import com.arnav.music.testing.nodes
import com.arnav.music.testing.screenSummary
import com.arnav.music.testing.skipOnboardingIfShown
import com.arnav.music.testing.targetPackage
import com.arnav.music.testing.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Deterministic "monkey-lite": ~60 seeded random taps on visible, enabled, clickable nodes across
 * all tabs. Anything that would leave the app (system settings, share sheets, YouTube Music, the
 * equalizer, file pickers, Google sign-in) or destroy data is skipped. A crash kills the process
 * and fails the run; the activity must still be alive at the end.
 */
@RunWith(AndroidJUnit4::class)
class MonkeyLiteTest {
    @get:Rule(order = 0) val permissions = grantAppPermissions()
    @get:Rule(order = 1) val crashes = CrashLoggerRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() = rule.skipOnboardingIfShown()

    /** Lower-case fragments of labels that must never be tapped. */
    private val blocked = listOf(
        "youtube music", "system settings", "equalizer", "share", "output device", "import .lrc", "import playlists",
        "replay onboarding", "sign in", "sign out", "google", "free key", "on github", "terms of service", "privacy policy",
        "install", "allow installs", "allow access", "delete", "remove", "clear", "disconnect", "refresh from youtube",
        "open in", "add key", "save key", "download", "manage permission", "analyze now", "sync now", "resend",
        "skip onboarding", "try again", "check for updates", "check again", "retry", "search", "filter",
        // These hand off to system UI by design (share sheet, output switcher, launcher pin dialog).
        "share", "output device", "add to home screen", "open full player", "picture-in-picture",
    )

    private val history = ArrayDeque<String>()
    private var relaunched = false

    private fun record(action: String) {
        Log.i("ArnavMonkey", action)
        history.addLast(action)
        while (history.size > 12) history.removeFirst()
    }

    private fun candidates(): List<SemanticsNode> {
        val w = device.displayWidth.toFloat()
        val h = device.displayHeight.toFloat()
        return rule.nodes(hasClickAction() and isEnabled() and !hasSetTextAction()).filter { n ->
            val b: Rect = n.boundsInWindow
            val visible = b.width > 4f && b.height > 4f && b.right > 0f && b.bottom > 0f && b.left < w && b.top < h
            val text = n.spokenText().lowercase()
            visible && blocked.none { it in text }
        }
    }

    /** If anything outside the app came to the front, go back (or relaunch the app). */
    private fun ensureInApp() {
        repeat(3) {
            if (device.currentPackageName == targetPackage) return
            record("left the app (${device.currentPackageName}) → back")
            device.pressBack()
            device.waitForIdle(2_000)
        }
        if (device.currentPackageName != targetPackage) {
            record("relaunching the app")
            relaunched = true
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val launch = ctx.packageManager.getLaunchIntentForPackage(targetPackage)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), 15_000)
        }
    }

    private fun overlayOpen(): Boolean =
        rule.nodes(isRoot()).size > 1 || rule.exists(M.COLLAPSE_PLAYER)

    @Test
    fun randomTapsAcrossTabsNeverCrash() {
        val random = Random(20261006)
        val steps = 60
        for (step in 0 until steps) {
            try {
                ensureInApp()
                rule.waitForIdle()
                if (step % 15 == 0) {
                    val tab = M.TABS[(step / 15) % M.TABS.size]
                    record("step $step: tab $tab")
                    if (overlayOpen()) device.pressBack()
                    rule.click(M.tab(tab))
                    continue
                }
                if (overlayOpen() && random.nextInt(4) == 0) {
                    record("step $step: system back (overlay open)")
                    device.pressBack()
                    continue
                }
                val nodes = candidates()
                if (nodes.isEmpty()) {
                    record("step $step: nothing tappable → Home tab")
                    if (overlayOpen()) device.pressBack()
                    rule.click(M.tab("Home"))
                    continue
                }
                val node = nodes[random.nextInt(nodes.size)]
                record("step $step: tap '${node.spokenText().take(80)}'")
                runCatching { rule.onAllNodes(M.id(node.id)).onFirst().performClick() }
                    .onFailure { Log.w("ArnavMonkey", "tap skipped: ${it.message?.take(200)}") }
            } catch (t: Throwable) {
                throw AssertionError(
                    "Monkey step $step failed: ${t.javaClass.simpleName}: ${t.message?.take(500)}\n" +
                        "Recent actions:\n  " + history.joinToString("\n  ") + "\nOn screen: " + rule.screenSummary(),
                    t,
                )
            }
        }

        // Still alive and usable.
        ensureInApp()
        repeat(3) { if (overlayOpen()) device.pressBack() }
        val foreground = device.currentPackageName
        if (foreground != targetPackage) {
            Log.w("ArnavMonkey", "Foreground is $foreground after the run; recent actions: ${history.takeLast(12)}")
            com.arnav.music.testing.dismissSystemDialogs()
        }
        assertEquals(
            "App is not in the foreground after the monkey run (was $foreground). Recent actions: ${history.takeLast(12)}",
            targetPackage, device.currentPackageName,
        )
        if (!relaunched) {
            assertNotEquals("MainActivity was destroyed during the monkey run", Lifecycle.State.DESTROYED, rule.activityRule.scenario.state)
        }
        rule.waitFor(M.tab("Home"))
        rule.click(M.tab("Home"))
        rule.waitFor(M.HOME_ROOT)
    }
}
