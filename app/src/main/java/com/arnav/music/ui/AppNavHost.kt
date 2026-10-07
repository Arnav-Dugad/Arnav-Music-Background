package com.arnav.music.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.arnav.music.feature.ai.ArnavAiScreen
import com.arnav.music.feature.album.AlbumScreen
import com.arnav.music.feature.credits.CreditsScreen
import com.arnav.music.feature.auth.AuthScreen
import com.arnav.music.feature.collection.ArtistScreen
import com.arnav.music.feature.collection.CollectionScreen
import com.arnav.music.feature.duplicates.DuplicatesScreen
import com.arnav.music.feature.explore.ExploreScreen
import com.arnav.music.feature.explore.SearchScreen
import com.arnav.music.feature.home.HomeScreen
import com.arnav.music.feature.imports.ImportHistoryScreen
import com.arnav.music.feature.insights.ConstellationScreen
import com.arnav.music.feature.insights.InsightsScreen
import com.arnav.music.feature.insights.TimelineScreen
import com.arnav.music.feature.insights.ListeningStatsScreen
import com.arnav.music.feature.library.LibraryScreen
import com.arnav.music.feature.moments.MomentScreen
import com.arnav.music.feature.profile.ProfileScreen
import com.arnav.music.feature.settings.SettingsScreen
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Easing

/** Optional shared-element origin on detail routes (see [ArtRoutes]). */
private const val FROM_QUERY = "?from={from}"
private fun fromArgument() = navArgument(ArtRoutes.FROM) { type = NavType.StringType; nullable = true; defaultValue = null }

/** A destination whose content can take part in shared-element artwork transitions. */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(nav: NavHostController) {
    val motion = ArnavTheme.motion
    val travel = motion.travel
    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedScope provides this) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        // Spatial hierarchy: deeper screens slide in from the trailing edge with a slight
        // depth shift; top-level tabs crossfade in place.
        // Reduced motion: every page change is a plain cross-fade (shared-element morphs are off too).
        enterTransition = {
            val top = targetState.destination.route in Routes.topLevel
            if (motion.reduced) fadeIn(motion.fast())
            else if (top) fadeIn(motion.fast()) + scaleIn(motion.fast(), 0.985f)
            else slideInHorizontally(motion.offsetSpring()) { (it * 0.18f * travel).toInt() } + fadeIn(androidx.compose.animation.core.tween(220, easing = Easing.Emphasized))
        },
        exitTransition = {
            val top = targetState.destination.route in Routes.topLevel
            if (top || motion.reduced) fadeOut(motion.fast()) else fadeOut(motion.fast()) + scaleOut(motion.fast(), 0.97f)
        },
        // Android 15-style predictive back: while you swipe, the page shrinks toward the swipe edge and
        // the one underneath rises from slightly behind. Tweens so the gesture can scrub them linearly.
        popEnterTransition = {
            if (motion.reduced) fadeIn(motion.fast()) else fadeIn(androidx.compose.animation.core.tween(300)) + scaleIn(androidx.compose.animation.core.tween(300, easing = Easing.Emphasized), if (motion.reduced) 1f else 0.94f)
        },
        popExitTransition = {
            if (motion.reduced) fadeOut(motion.fast()) else scaleOut(androidx.compose.animation.core.tween(300, easing = Easing.Emphasized), if (motion.reduced) 1f else 0.9f) +
                slideOutHorizontally(androidx.compose.animation.core.tween(300, easing = Easing.Emphasized)) { (it * 0.12f * travel).toInt() } +
                fadeOut(androidx.compose.animation.core.tween(240))
        },
    ) {
        screen(Routes.HOME) { HomeScreen() }
        screen(Routes.EXPLORE) { ExploreScreen() }
        screen(Routes.LIBRARY) { LibraryScreen() }
        screen(Routes.AI, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
            ArnavAiScreen(initialQuery = it.arguments?.getString("q").orEmpty())
        }
        screen(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
            SearchScreen(initialQuery = it.arguments?.getString("q").orEmpty())
        }
        screen(Routes.COLLECTION, arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType })) {
            val kind = runCatching { CollectionKind.valueOf(it.arguments?.getString("kind").orEmpty()) }.getOrDefault(CollectionKind.LIKED)
            CollectionScreen(kind, it.arguments?.getString("id").orEmpty())
        }
        // Artist and album pages accept an optional "?from=" origin (see ArtRoutes) so shared-element
        // keys can be scoped to the section the user tapped; plain Routes.artist/album still match.
        screen(Routes.ARTIST + FROM_QUERY, arguments = listOf(navArgument("name") { type = NavType.StringType }, fromArgument())) {
            ArtistScreen(it.arguments?.getString("name").orEmpty(), from = it.arguments?.getString(ArtRoutes.FROM))
        }
        screen(Routes.MOMENT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            MomentScreen(it.arguments?.getString("id").orEmpty())
        }
        screen(Routes.INSIGHTS) { InsightsScreen() }
        screen(Routes.CONSTELLATION) { ConstellationScreen() }
        screen(Routes.TIMELINE) { TimelineScreen() }
        screen(Routes.LISTENING_STATS) { ListeningStatsScreen() }
        screen(Routes.PROFILE) { ProfileScreen() }
        screen(Routes.AUTH) { AuthScreen() }
        screen(Routes.DUPLICATES) { DuplicatesScreen() }
        screen(Routes.IMPORTS) { ImportHistoryScreen() }
        screen(Routes.ALBUM + FROM_QUERY, arguments = listOf(navArgument("albumId") { type = NavType.StringType }, fromArgument())) {
            AlbumScreen(it.arguments?.getString("albumId").orEmpty(), from = it.arguments?.getString(ArtRoutes.FROM))
        }
        screen(Routes.CREDITS, arguments = listOf(navArgument("trackId") { type = NavType.StringType })) {
            CreditsScreen(it.arguments?.getString("trackId").orEmpty())
        }
        screen(Routes.SETTINGS) { SettingsScreen(page = "") }
        screen(Routes.SETTINGS_PAGE, arguments = listOf(navArgument("page") { type = NavType.StringType })) {
            SettingsScreen(page = it.arguments?.getString("page").orEmpty())
        }
    }
    }
    }
}
