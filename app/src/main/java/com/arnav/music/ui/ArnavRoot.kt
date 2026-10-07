package com.arnav.music.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arnav.music.core.playback.Engine
import com.arnav.music.domain.model.SourceType
import com.arnav.music.feature.onboarding.OnboardingScreen
import com.arnav.music.ui.artwork.rememberArtworkPalette
import com.arnav.music.ui.components.ArnavMark
import com.arnav.music.ui.components.pressScale
import com.arnav.music.ui.components.rememberInteraction
import com.arnav.music.ui.palette.CommandPalette
import com.arnav.music.ui.player.CreatePlaylistSheet
import com.arnav.music.ui.player.LyricsSheet
import com.arnav.music.ui.player.PlayerActions
import com.arnav.music.ui.player.PlayerLayer
import com.arnav.music.ui.player.PlaylistPickerSheet
import com.arnav.music.ui.player.QueuePanel
import com.arnav.music.ui.player.ShareCards
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.player.SleepTimerSheet
import com.arnav.music.ui.player.TrackActionsSheet
import com.arnav.music.ui.theme.ArnavMusicTheme
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import com.arnav.music.ui.theme.surfaceMode
import kotlinx.coroutines.launch

private data class Dest(val route: String, val label: String, val icon: ImageVector, val iconSelected: ImageVector)

private val destinations = listOf(
    Dest(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Dest(Routes.EXPLORE, "Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
    Dest(Routes.LIBRARY, "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    Dest(Routes.AI, "Arnav AI", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
)

@Composable
fun ArnavAppRoot(vm: AppViewModel, deepLink: DeepLink?, onDeepLinkHandled: () -> Unit, openPlayer: Boolean, onOpenPlayerHandled: () -> Unit, pip: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val state by vm.playerState.collectAsStateWithLifecycle()
    val track = state.current
    val mode = surfaceMode(settings)
    // Every palette colour crossfades between songs, so the whole theme glides with the music.
    val palette = com.arnav.music.ui.artwork.animatePalette(rememberArtworkPalette(track?.artworkUrl, mode), durationMs = if (budget.reducedMotion) 0 else 900)

    val loaded by vm.settingsLoaded.collectAsStateWithLifecycle()
    ThemeRevealHost(reduceMotion = budget.reducedMotion) {
    ArnavMusicTheme(settings, budget, palette) {
        if (!loaded) {
            Box(Modifier.fillMaxSize().background(ArnavTheme.colors.background))
            return@ArnavMusicTheme
        }
        if (!settings.onboardingDone) {
            OnboardingScreen(vm)
            return@ArnavMusicTheme
        }
        AppScaffold(vm, deepLink, onDeepLinkHandled, openPlayer, onOpenPlayerHandled, pip)
    }
    }
}

@Composable
private fun AppScaffold(vm: AppViewModel, deepLink: DeepLink?, onDeepLinkHandled: () -> Unit, openPlayer: Boolean, onOpenPlayerHandled: () -> Unit, pip: Boolean) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.playerState.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val switching by vm.player.switchingVariant.collectAsStateWithLifecycle()
    com.arnav.music.core.analysis.BeatDropHaptics(state, progress, settings)
    val expand = remember { Animatable(0f) }
    var sheet by remember { mutableStateOf<SheetRequest?>(null) }
    var launchOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var dockRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var previewing by remember { mutableStateOf<Pair<com.arnav.music.domain.model.Track, (() -> Unit)?>?>(null) }
    var resumeAfterPreview by remember { mutableStateOf(false) }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val palette = com.arnav.music.ui.theme.LocalArtworkPalette.current ?: com.arnav.music.domain.color.ArtworkPalette.neutral(surfaceMode(settings))

    // Reorders Up Next by key compatibility, tempo and energy (songs on this phone that were analyzed).
    val harmonicMix: () -> Unit = {
        scope.launch {
            val ok = vm.player.harmonicMix()
            vm.message(if (ok) "Up Next reordered for smooth key and tempo changes" else "Harmonic mix needs at least 3 analyzed songs from this phone in Up Next")
        }
    }
    val intelligence = org.koin.compose.koinInject<com.arnav.music.core.repo.IntelligenceRepository>()
    val navigator = remember(nav) {
        Navigator(
            nav,
            openSheet = { sheet = it },
            openPlayer = { scope.launch { expand.animateTo(1f, motion.cinematic()) } },
            openPalette = { paletteOpen = true },
            share = { t -> scope.launch { runCatching { ShareCards.share(context, t) } } },
            flyFrom = { bounds ->
                if (!motion.reduced) {
                    launchOrigin = bounds
                    scope.launch { expand.snapTo(0f); expand.animateTo(1f, motion.cinematic()) }
                }
            },
            preview = { t, more ->
                // Pause what's playing; it resumes when the preview closes.
                resumeAfterPreview = vm.player.state.value.isPlaying
                if (resumeAfterPreview) vm.player.pause()
                previewing = t to more
            },
            beforeTopLevel = {
                sheet = null
                paletteOpen = false
                if (expand.targetValue > 0f) scope.launch { expand.animateTo(0f, motion.cinematic()) }
            },
        )
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, m.action, withDismissAction = false, duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
    LaunchedEffect(deepLink) {
        when (val d = deepLink) {
            is DeepLink.Navigate -> navigator.go(d.route)
            is DeepLink.PlayYouTube -> vm.playVideo(d.videoId)
            null -> Unit
        }
        if (deepLink != null) onDeepLinkHandled()
    }
    LaunchedEffect(openPlayer) {
        if (openPlayer && state.current != null) expand.animateTo(1f, motion.cinematic())
        if (openPlayer) onOpenPlayerHandled()
    }

    // Ask for notification permission the first time local (background-capable) playback starts.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.engine, state.isPlaying) {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif && state.engine == Engine.LOCAL && state.isPlaying &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // System bar icons follow what's actually behind them: the app theme, or the player's artwork backdrop.
    val view = androidx.compose.ui.platform.LocalView.current
    val playerOpen by remember { androidx.compose.runtime.derivedStateOf { expand.value > 0.5f } }
    val darkIcons = if (playerOpen && state.current != null) Color(palette.backdrop).luminance() > 0.5f else c.background.luminance() > 0.5f
    LaunchedEffect(darkIcons) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // The highlighted tab is the tab whose root sits under the current screen (Home otherwise),
    // so sub-pages like a playlist keep their tab lit.
    val selectedTab = remember(backStack) {
        Routes.topLevel.lastOrNull { r -> r != Routes.HOME && runCatching { nav.getBackStackEntry(r) }.isSuccess } ?: Routes.HOME
    }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalAppViewModel provides vm) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(c.background)
                .onPreviewKeyEvent { e ->
                    // Ctrl/Cmd + K opens the command palette on keyboards (tablets, Chromebooks, DeX).
                    if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.K) { paletteOpen = true; true } else false
                },
        ) {
            val wide = maxWidth >= 720.dp
            // Big tablets / desktop windows: library, queue and Now Playing side by side.
            val studio = wide && maxWidth >= 1000.dp && !pip
            val railWidth = if (wide) 88.dp else 0.dp
            val navBarInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
            val bottomNavHeight = if (wide) 0.dp else 68.dp
            val hasPlayer = state.current != null
            val barBottom = maxHeight - navBarInset - bottomNavHeight - (if (wide) 16.dp else 6.dp)
            val chromeBottom = navBarInset + bottomNavHeight + (if (hasPlayer && !studio) (if (state.current?.source == SourceType.YOUTUBE) 120.dp else 92.dp) else 16.dp)

            CompositionLocalProvider(LocalChromePadding provides PaddingValues(bottom = chromeBottom)) {
                Row(Modifier.fillMaxSize()) {
                    if (wide) NavRail(selectedTab, onSelect = { navigator.topLevel(it) }, onSettings = { navigator.go(Routes.settings()) })
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AppNavHost(nav)
                    }
                    val current = state.current
                    if (studio && current != null) {
                        StudioPane(
                            track = current, state = state, progress = progress, liked = current.id in liked,
                            onArtBounds = { dockRect = it },
                            onExpand = { scope.launch { expand.animateTo(1f, motion.cinematic()) } },
                            togglePlay = vm.player::togglePlay, next = vm.player::next, previous = vm.player::previous,
                            toggleLike = { vm.toggleLike(it) },
                            openArtist = { navigator.go(Routes.artist(it)) },
                            queue = { QueuePanel(state, vm.player::move, vm.player::removeAt, { vm.player.skipTo(it) }, { vm.saveQueueAsPlaylist() }, {}, onShuffle = vm.player::toggleShuffle, onHarmonicMix = harmonicMix) },
                        )
                    }
                }
            }

            if (!wide && !pip) {
                BottomBar(
                    selectedTab, onSelect = { r -> navigator.topLevel(r) }, modifier = Modifier.align(Alignment.BottomCenter),
                    bleed = if (hasPlayer) Color(palette.accent) else null,
                )
            }

            if (hasPlayer) {
                PlayerLayer(
                    state = state, progress = progress, palette = palette, settings = settings,
                    liked = state.current?.id in liked, expand = expand, youtube = vm.player.youtube,
                    barBottomPx = with(density) { barBottom.toPx() }, contentLeftPx = with(density) { railWidth.toPx() }, wide = wide,
                    actions = PlayerActions(
                        togglePlay = vm.player::togglePlay, next = vm.player::next, previous = vm.player::previous, seekTo = vm.player::seekTo,
                        toggleShuffle = vm.player::toggleShuffle, cycleRepeat = vm.player::cycleRepeat,
                        toggleLike = { vm.toggleLike(it) },
                        openArtist = { name -> scope.launch { expand.animateTo(0f, motion.cinematic()) }; navigator.go(Routes.artist(name)) },
                        dismissIssue = vm.player::dismissIssue,
                        onMore = { sheet = SheetRequest.TrackActions(it) },
                        onSleep = { sheet = SheetRequest.Sleep },
                        onShare = navigator.share,
                        switchVariant = vm.player::switchVariant,
                        findAnotherUpload = vm.player::findAnotherUpload,
                        skipTo = { vm.player.skipTo(it) },
                        openCredits = { t -> scope.launch { expand.animateTo(0f, motion.cinematic()) }; navigator.go(Routes.credits(t.id.value)) },
                    ),
                    switchingVariant = switching,
                    pip = pip,
                    dockRect = if (studio) dockRect else null,
                    launchOrigin = launchOrigin,
                    onLaunchConsumed = { launchOrigin = null },
                    queueContent = { close ->
                        QueuePanel(state, vm.player::move, vm.player::removeAt, { vm.player.skipTo(it) }, { vm.saveQueueAsPlaylist() }, close, onShuffle = vm.player::toggleShuffle, onHarmonicMix = harmonicMix)
                    },
                )
            }

            if (!pip) SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = chromeBottom + 8.dp).padding(horizontal = Space.gutter),
            ) { data ->
                Row(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().glass(GlassMaterial.Elevated, RoundedCornerShape(Radius.m)).padding(horizontal = Space.l, vertical = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(data.visuals.message, style = ArnavTheme.type.bodySmall, color = c.content, modifier = Modifier.weight(1f))
                    data.visuals.actionLabel?.let { label ->
                        Text(label, style = ArnavTheme.type.label, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable { data.performAction() }.padding(Space.s))
                    }
                }
            }

            previewing?.let { (t, more) ->
                if (!pip) com.arnav.music.ui.player.CoverPreview(
                    track = t,
                    onDismiss = {
                        previewing = null
                        if (resumeAfterPreview) { resumeAfterPreview = false; vm.player.play() }
                    },
                    onPlay = { resumeAfterPreview = false; vm.play(listOf(t), 0) },
                    onQueue = { vm.addToQueue(t) },
                    onMore = more,
                )
            }

            AnimatedVisibility(paletteOpen && !pip, enter = fadeIn(motion.fast()) + scaleIn(motion.expressive(), 0.97f), exit = fadeOut(motion.fast()) + scaleOut(motion.fast(), 0.98f)) {
                CommandPalette(onDismiss = { paletteOpen = false })
            }
        }

        when (val s = sheet) {
            is SheetRequest.TrackActions -> TrackActionsSheet(
                track = s.track, liked = s.track.id in liked, onDismiss = { sheet = null },
                onPlayNext = { vm.playNext(s.track) }, onQueue = { vm.addToQueue(s.track) }, onLike = { vm.toggleLike(s.track) },
                onAddToPlaylist = { sheet = SheetRequest.AddToPlaylist(listOf(s.track)) },
                onArtist = { scope.launch { expand.animateTo(0f, motion.cinematic()) }; navigator.go(Routes.artist(s.track.artist)) },
                onShare = { navigator.share(s.track) },
                onLyrics = { sheet = SheetRequest.Lyrics(s.track) },
                onCredits = { scope.launch { expand.animateTo(0f, motion.cinematic()) }; navigator.go(Routes.credits(s.track.id.value)) },
                onRadio = {
                    scope.launch {
                        val radio = intelligence.radio(s.track)
                        if (radio.size > 1) { vm.play(radio, 0); vm.message("Radio from “${s.track.title.take(40)}” · ${radio.size} songs") }
                        else vm.message("Not enough to build a radio for this song yet — play a little more first")
                    }
                },
                onMoreLikeThis = { scope.launch { intelligence.moreLikeThis(s.track); vm.message("You'll hear more like “${s.track.title.take(40)}”") } },
                onNotInterested = {
                    scope.launch { intelligence.notInterested(s.track) }
                    vm.message("Won't recommend this song", "Undo") { scope.launch { intelligence.clearFeedback(s.track.id.value) } }
                },
                onBlockArtist = {
                    scope.launch { intelligence.blockArtist(s.track.artistKey) }
                    vm.message("Won't recommend ${s.track.artist.take(40)}", "Undo") { scope.launch { intelligence.clearFeedback(s.track.artistKey) } }
                },
            )
            is SheetRequest.AddToPlaylist -> PlaylistPickerSheet(
                playlists, onDismiss = { sheet = null },
                onPick = { id -> s.tracks.forEach { vm.addToPlaylist(id, it) } },
                onCreate = { sheet = SheetRequest.CreatePlaylist(s.tracks) },
            )
            is SheetRequest.CreatePlaylist -> CreatePlaylistSheet(s.tracks.size, onDismiss = { sheet = null }) { name -> vm.createPlaylistWith(name, s.tracks) }
            is SheetRequest.Lyrics -> LyricsSheet(s.track) { sheet = null }
            SheetRequest.Sleep -> SleepTimerSheet(state.sleep, onDismiss = { sheet = null }) { vm.setSleep(it) }
            null -> Unit
        }
    }
}

@Composable
private fun BottomBar(selectedTab: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, bleed: Color? = null) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    val selectedIndex = destinations.indexOfFirst { d -> d.route == selectedTab }
    // While music plays, the artwork's colour bleeds up into the bar from below.
    val bleedColor by animateColorAsState(bleed ?: Color.Transparent, androidx.compose.animation.core.tween(if (motion.reduced) 0 else 900), label = "bleed")
    Box(
        modifier
            .fillMaxWidth()
            .glass(GlassMaterial.Thick, RoundedCornerShape(topStart = Radius.l, topEnd = Radius.l))
            .drawBehind {
                if (bleedColor.alpha > 0.01f) {
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bleedColor.copy(alpha = 0f), bleedColor.copy(alpha = bleedColor.alpha * (if (c.isDark) 0.20f else 0.12f)))))
                }
            }
            .navigationBarsPadding()
            .height(68.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemW = maxWidth / destinations.size
            // Sliding indicator pill: one element that travels between tabs.
            val x by animateDpAsState(itemW * selectedIndex.coerceAtLeast(0) + (itemW - 64.dp) / 2, motion.responsive(), label = "ind")
            if (selectedIndex >= 0) {
                Box(Modifier.offset(x = x, y = 10.dp).size(64.dp, 32.dp).clip(CircleShape).background(c.accentSoft))
            }
            Row(Modifier.fillMaxSize()) {
                destinations.forEachIndexed { i, d ->
                    val selected = i == selectedIndex
                    val tint by animateColorAsState(if (selected) c.content else c.contentSubtle, motion.fast(), label = "tint")
                    val interaction = rememberInteraction()
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressScale(interaction, 0.9f)
                            .clickable(interaction, indication = null, role = Role.Tab) { haptics.navigate(); onSelect(d.route.substringBefore('?')) }
                            .semantics { this.selected = selected },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(if (selected) d.iconSelected else d.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(d.label, style = ArnavTheme.type.caption, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(selectedTab: String, onSelect: (String) -> Unit, onSettings: () -> Unit) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(c.surface).statusBarsPadding().navigationBarsPadding().padding(vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArnavMark(Modifier.size(36.dp))
        Spacer(Modifier.height(Space.xxl))
        destinations.forEach { d ->
            val selected = d.route == selectedTab
            Column(
                Modifier.padding(vertical = Space.s).clip(RoundedCornerShape(Radius.m))
                    .clickable(role = Role.Tab) { haptics.navigate(); onSelect(d.route.substringBefore('?')) }
                    .semantics { this.selected = selected }
                    .padding(Space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp, 32.dp).clip(CircleShape).background(if (selected) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
                    Icon(if (selected) d.iconSelected else d.icon, null, tint = if (selected) c.content else c.contentSubtle)
                }
                Text(d.label, style = ArnavTheme.type.caption, color = if (selected) c.content else c.contentSubtle)
            }
        }
        Spacer(Modifier.weight(1f))
        com.arnav.music.ui.components.ArnavIconButton(Icons.Rounded.Settings, "Settings", onSettings, tint = c.contentMuted)
    }
}

@Suppress("unused") private fun Dp.px() = this
@Suppress("unused") private val unused = IntOffset.Zero
