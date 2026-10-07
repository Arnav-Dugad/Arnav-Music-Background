package com.arnav.music.testing

import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

const val TAG = "ArnavTest"

/** Generous: CI emulators are slow, and cold starts after `pm clear` take a while. */
const val STARTUP_TIMEOUT = 60_000L
const val TIMEOUT = 20_000L
const val SHORT = 5_000L

val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
val targetPackage: String get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

fun ComposeTestRule.nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
    onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false)

fun ComposeTestRule.exists(matcher: SemanticsMatcher): Boolean = nodes(matcher).isNotEmpty()

/** Exists and is at least partly on screen. */
fun ComposeTestRule.isShown(matcher: SemanticsMatcher): Boolean =
    exists(matcher) && runCatching { onAllNodes(matcher).onFirst().assertIsDisplayed() }.isSuccess

/** One line of what's on screen right now, for failure messages readable from plain CI logs. */
fun ComposeTestRule.screenSummary(max: Int = 1_800): String = runCatching {
    val roots = onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).size
    val labels = nodes(SemanticsMatcher("any node") { true }).map { it.spokenText() }.filter { it.isNotBlank() }.distinct()
    "[$roots root(s)] " + labels.joinToString(" · ").take(max)
}.getOrElse { "<screen unavailable: ${it.javaClass.simpleName}: ${it.message?.take(300)}>" }

private fun ComposeTestRule.fail(what: String, cause: Throwable?): Nothing {
    val screen = screenSummary()
    Log.e(TAG, "FAILED: $what\nScreen: $screen", cause)
    throw AssertionError("$what\nOn screen: $screen", cause)
}

/** Waits until [condition] holds; on timeout fails with a readable description and the visible screen. */
fun ComposeTestRule.waitUntilTrue(what: String, timeout: Long = TIMEOUT, condition: () -> Boolean) {
    val r = runCatching { waitUntil(timeout) { condition() } }
    r.exceptionOrNull()?.let { fail("Timed out after ${timeout / 1000}s: $what", it) }
}

/** Like [waitUntilTrue] but returns false instead of failing (for optional / network-dependent states). */
fun ComposeTestRule.waitUntilOrFalse(timeout: Long, condition: () -> Boolean): Boolean =
    runCatching { waitUntil(timeout) { condition() } }.isSuccess

fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeout: Long = TIMEOUT): SemanticsNodeInteraction {
    waitUntilTrue("waiting for [${matcher.description}]", timeout) { exists(matcher) }
    return onAllNodes(matcher).onFirst()
}

fun ComposeTestRule.waitGone(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) {
    waitUntilTrue("waiting for [${matcher.description}] to disappear", timeout) { !exists(matcher) }
}

/** Waits until any of [matchers] exists and returns the first one that does. */
fun ComposeTestRule.waitForAny(vararg matchers: SemanticsMatcher, timeout: Long = TIMEOUT): SemanticsMatcher {
    var found: SemanticsMatcher? = null
    waitUntilTrue("waiting for any of " + matchers.joinToString { "[${it.description}]" }, timeout) {
        found = matchers.firstOrNull { exists(it) }
        found != null
    }
    return found!!
}

/**
 * Scrolls the node into view when it sits in a scrollable container, then activates its click
 * action the way TalkBack / Switch Access do. That never misses because of the floating bottom bar
 * or MorphBar covering a row near the bottom edge (a raw touch there would hit the bar instead).
 * Use [tap] when the touch path itself matters.
 */
fun ComposeTestRule.click(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) {
    val node = waitFor(matcher, timeout)
    runCatching { node.performScrollTo() }
    val r = runCatching { onAllNodes(matcher).onFirst().performSemanticsAction(SemanticsActions.OnClick) }
    r.exceptionOrNull()?.let { fail("Could not click [${matcher.description}]", it) }
    waitForIdle()
}

/** A real touch in the middle of the node (goes through hit-testing like a finger). */
fun ComposeTestRule.tap(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) {
    waitFor(matcher, timeout)
    val r = runCatching { onAllNodes(matcher).onFirst().performClick() }
    r.exceptionOrNull()?.let { fail("Could not tap [${matcher.description}]", it) }
    waitForIdle()
}

/**
 * Slow CI emulators sometimes pop a system "isn't responding" dialog (package `android`) on top of
 * the app. It steals key events and focus, so dismiss it (choosing "Wait") before acting.
 */
fun dismissSystemDialogs() {
    repeat(3) {
        if (device.currentPackageName == "com.arnav.music") return
        val button = listOf("Wait", "WAIT", "Close app", "OK", "Close").firstNotNullOfOrNull { label ->
            device.findObject(androidx.test.uiautomator.By.text(label))
        } ?: return
        Log.w("ArnavTest", "Dismissing a system dialog over the app (${device.currentPackageName})")
        runCatching { button.click() }
        device.waitForIdle(1_000)
    }
}

/** System back (key event through the window, like a person pressing back). */
fun ComposeTestRule.pressBack() {
    dismissSystemDialogs()
    device.pressBack()
    waitForIdle()
}

/**
 * Gets past first-run onboarding if it shows (it always does after the orchestrator's `pm clear`),
 * and waits until Home is on screen. Safe to call when onboarding was already completed.
 */
fun ComposeTestRule.skipOnboardingIfShown() {
    waitForAny(M.ONBOARDING_SKIP, M.tab("Home"), timeout = STARTUP_TIMEOUT)
    if (exists(M.ONBOARDING_SKIP)) {
        click(M.ONBOARDING_SKIP)
        waitFor(M.tab("Home"), TIMEOUT)
    }
    if (!exists(M.HOME_ROOT)) click(M.tab("Home"))
    waitFor(M.HOME_ROOT, TIMEOUT)
}

/** Taps a bottom tab and waits for that tab's root screen. */
fun ComposeTestRule.goToTab(label: String) {
    click(M.tab(label))
    waitFor(M.tabRoot(label))
}
