package com.arnav.music.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.player.SheetRequest

/** Thin navigation facade handed to screens, so they never touch the NavController directly. */
class Navigator(
    private val nav: NavHostController,
    val openSheet: (SheetRequest) -> Unit,
    val openPlayer: () -> Unit,
    val openPalette: () -> Unit,
    val share: (Track) -> Unit,
    /** Opens Now Playing with the artwork flying from [bounds] (root coordinates, px). */
    val flyFrom: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** Long-press "peek": plays a short excerpt of [Track]; the optional action opens its menu. */
    val preview: (Track, (() -> Unit)?) -> Unit = { _, _ -> },
    /** Called before a tab switch: collapses the player and closes sheets/overlays. */
    private val beforeTopLevel: () -> Unit = {},
) {
    private val _reselect = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a tab's route when its button is tapped while already on that tab's root (scroll to top). */
    val reselect: SharedFlow<String> = _reselect
    private var lastRoute: String? = null
    private var lastAt = 0L

    /**
     * Pushes a screen. Not single-top: same-pattern routes with different arguments (artist → artist,
     * settings → settings/appearance) must stack so Back returns to the previous one. Rapid
     * double-taps on the same target are ignored instead.
     */
    fun go(route: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (route == lastRoute && now - lastAt < 600) return
        lastRoute = route; lastAt = now
        nav.navigate(route)
    }
    fun back() { if (!nav.popBackStack()) Unit }
    /**
     * Tab buttons always land on the tab's root, wherever you are: sub-pages are cleared (no
     * restored deep state) and the player/sheets get out of the way. Tapping the current tab's
     * root again scrolls it to the top.
     */
    fun topLevel(route: String) {
        beforeTopLevel()
        val target = route.substringBefore('?')
        if (nav.currentDestination?.route?.substringBefore('?') == target) {
            _reselect.tryEmit(target)
            return
        }
        lastRoute = null
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
/** Scrolls [scrollToTop] whenever the tab [route] is reselected in the navigation bar. */
@androidx.compose.runtime.Composable
fun OnTabReselect(route: String, scrollToTop: suspend () -> Unit) {
    val nav = LocalNavigator.current
    androidx.compose.runtime.LaunchedEffect(nav, route) { nav.reselect.collect { if (it == route.substringBefore('?')) scrollToTop() } }
}

val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> { error("AppViewModel not provided") }
/** Space reserved at the bottom of scrolling content for the MorphBar + navigation chrome. */
val LocalChromePadding = staticCompositionLocalOf { PaddingValues(bottom = 160.dp) }
