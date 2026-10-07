package com.arnav.music.ui.player

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import com.arnav.music.core.playback.Engine
import com.arnav.music.core.playback.PlaybackIssue
import com.arnav.music.core.playback.PlayerState
import com.arnav.music.core.playback.Progress
import com.arnav.music.core.playback.RepeatMode
import com.arnav.music.core.playback.YouTubeEngine
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.domain.color.ArtworkPalette
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.HeartButton
import com.arnav.music.ui.components.PlayPauseButton
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.components.rememberInteraction
import com.arnav.music.ui.components.pressScale
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Actions the player layer needs from the app. */
class PlayerActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seekTo: (Long) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val toggleLike: (Track) -> Unit,
    val openArtist: (String) -> Unit,
    val dismissIssue: () -> Unit,
    val onMore: (Track) -> Unit,
    val onSleep: () -> Unit,
    val onShare: (Track) -> Unit,
    val switchVariant: (com.arnav.music.domain.model.MediaVariant) -> Unit = {},
    val findAnotherUpload: () -> Unit = {},
    val skipTo: (Int) -> Unit = {},
    val openCredits: (Track) -> Unit = {},
)

/**
 * The player's continuous spatial model. One progress value (0 = MorphBar, 1 = Now Playing)
 * drives every element; the artwork / video surface is a single element that physically travels
 * between both layouts — no screen swap.
 */
@Composable
fun PlayerLayer(
    state: PlayerState,
    progress: Progress,
    palette: ArtworkPalette,
    settings: AppSettings,
    liked: Boolean,
    expand: Animatable<Float, AnimationVector1D>,
    youtube: YouTubeEngine,
    barBottomPx: Float,
    contentLeftPx: Float,
    wide: Boolean,
    actions: PlayerActions,
    switchingVariant: Boolean = false,
    /** Picture-in-picture: only the artwork / visible YouTube player fills the small window. */
    pip: Boolean = false,
    /** Tablet "Studio" layout: when collapsed, the surface docks into this rect (root px) instead of the MorphBar. */
    dockRect: androidx.compose.ui.geometry.Rect? = null,
    /** Bounds (root px) of a tapped card: the artwork flies from there into Now Playing. */
    launchOrigin: androidx.compose.ui.geometry.Rect? = null,
    onLaunchConsumed: () -> Unit = {},
    queueContent: @Composable (onClose: () -> Unit) -> Unit,
) {
    val track = state.current ?: return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    val isYouTube = track.source == SourceType.YOUTUBE
    // Cover-art uploads ("Topic" art tracks) are a square image inside a 16:9 frame: show them square.
    val squareArt = isYouTube && track.variant != com.arnav.music.domain.model.MediaVariant.VIDEO
    val wideVideo = isYouTube && !squareArt
    var immersive by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    // Queue sheet progress (0 = closed, 1 = open): follows the finger while dragged, springs otherwise.
    val queueT = remember { Animatable(if (queueOpen) 1f else 0f) }
    var queueDragging by remember { mutableStateOf(false) }
    val queueVisible by remember { derivedStateOf { queueT.value > 0.001f } }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    val lyricsT by animateFloatAsState(if (lyricsOpen) 1f else 0f, motion.cinematic(), label = "lyrics")
    // Full-screen lyrics: chrome steps aside, only the lines over the blurred cover remain.
    var lyricsFull by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) { if (!lyricsOpen) lyricsFull = false }
    val lyricsFullT by animateFloatAsState(if (lyricsFull && lyricsOpen) 1f else 0f, motion.cinematic(), label = "lyricsFull")
    val c = ArnavTheme.colors
    val ambientAllowed = settings.ambientEdgeGlow && c.isOled && !pip
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ambient by remember { mutableStateOf(false) }
    // Derived so per-frame expand animation doesn't recompose this whole layer.
    val settledOpen by remember { derivedStateOf { expand.value >= 0.999f } }
    val sheetVisible by remember { derivedStateOf { expand.value > 0.01f } }
    val mostlyOpen by remember { derivedStateOf { expand.value > 0.5f } }
    val ambientOn = ambient && ambientAllowed && state.isPlaying && settledOpen && !queueOpen
    LaunchedEffect(lastTouch, ambientAllowed, state.isPlaying, expand.targetValue, queueOpen, lyricsOpen, lyricsFull) {
        ambient = false
        if (ambientAllowed && state.isPlaying && expand.targetValue >= 1f && !queueOpen) {
            kotlinx.coroutines.delay(AMBIENT_AFTER_MS)
            ambient = true
        }
    }
    // Ambient mode is meant to be looked at: keep the (almost black) screen on while it shows.
    val rootView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(ambientOn) {
        rootView.keepScreenOn = ambientOn
        onDispose { rootView.keepScreenOn = false }
    }
    val ambientT by animateFloatAsState(if (ambientOn) 1f else 0f, tween(if (ambientOn) 1400 else 280), label = "ambient")
    val beat = com.arnav.music.core.analysis.rememberBeatPulse(
        trackId = track.id.value.takeIf { !isYouTube && sheetVisible && !pip },
        progress = progress, isPlaying = state.isPlaying, enabled = settings.beatVisuals,
    )

    // The cover breathes very slowly while music plays and settles back (smaller) when paused.
    val coverRest by animateFloatAsState(
        if (state.isPlaying || isYouTube) 1f else 0.9f,
        if (motion.reduced) snap() else spring(dampingRatio = 0.62f, stiffness = 180f),
        label = "coverRest",
    )
    val breathing = state.isPlaying && !isYouTube && !motion.reduced && settledOpen && !lyricsOpen && !pip
    val breath = if (breathing) {
        val inf = rememberInfiniteTransition(label = "breath")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4_800, easing = LinearEasing), AnimRepeatMode.Reverse), label = "breathT")
    } else null
    val coverScale = { coverRest * (1f + 0.014f * (breath?.value ?: 0f)) }

    val playerSpec: AnimationSpec<Float> = if (motion.reduced) motion.responsive()
        else spring(dampingRatio = 0.88f, stiffness = 320f, visibilityThreshold = 0.0005f)
    fun animateTo(target: Float, velocity: Float = 0f) = scope.launch {
        if (target == 1f) haptics.navigate()
        expand.animateTo(target, playerSpec, initialVelocity = velocity.coerceIn(-8f, 8f))
    }

    val queueSpec: AnimationSpec<Float> =
        if (motion.reduced) motion.responsive() else spring(dampingRatio = 0.84f, stiffness = 420f, visibilityThreshold = 0.0005f)
    /** Opens/closes the queue, carrying [velocity] (progress per second) from a release into the spring. */
    fun settleQueue(open: Boolean, velocity: Float = 0f) {
        queueDragging = false
        queueOpen = open
        scope.launch { queueT.animateTo(if (open) 1f else 0f, queueSpec, initialVelocity = velocity) }
    }
    // queueOpen can also change from outside a gesture (restored state); the sheet follows it.
    LaunchedEffect(queueOpen) {
        val target = if (queueOpen) 1f else 0f
        if (!queueDragging && queueT.targetValue != target) queueT.animateTo(target, queueSpec)
    }

    // Double tap to seek (left half back, right half forward), YouTube style.
    val seekTaps = rememberSeekTapState()
    val progressNow by androidx.compose.runtime.rememberUpdatedState(progress)
    val canSeekTap = settings.doubleTapSeek && state.capabilities.canSeek && progress.durationMs > 0 && !pip
    fun seekStep(dir: Int, fx: Float, fy: Float) {
        val p = progressNow
        val target = seekTaps.step(dir, fx, fy, p.positionMs, p.durationMs)
        haptics.select()
        actions.seekTo(target)
    }

    // Particle cover changes (on-device covers): the old cover dissolves, the new one assembles.
    val particlesOn = settings.coverParticles && !motion.reduced && !isYouTube && (ArnavTheme.budget?.particles ?: 1) > 0
    val swapEnabled = particlesOn && settledOpen && !lyricsOpen && !pip
    val queueIndex = state.queue.currentIndex
    val lastQueueIndex = remember { intArrayOf(queueIndex) }
    val swapForward = queueIndex >= lastQueueIndex[0]
    androidx.compose.runtime.SideEffect { lastQueueIndex[0] = queueIndex }
    val swap = rememberCoverSwap(track, enabled = swapEnabled, forward = swapForward, prefetch = particlesOn)

    // Predictive back: the player leans back with the gesture and collapses on release.
    PredictiveBackHandler(enabled = expand.targetValue > 0.5f && !pip) { events ->
        val collapsing = !queueOpen && !lyricsOpen && !immersive && !lyricsFull
        val closingQueue = queueOpen
        try {
            events.collect { ev ->
                if (collapsing) expand.snapTo(1f - 0.16f * ev.progress)
                // The queue sheet sinks a little with the gesture and drops away on release.
                else if (closingQueue) queueT.snapTo(1f - 0.12f * ev.progress)
            }
            when {
                queueOpen -> settleQueue(false)
                lyricsFull -> lyricsFull = false
                lyricsOpen -> lyricsOpen = false
                immersive -> immersive = false
                else -> animateTo(0f)
            }
        } catch (e: CancellationException) {
            if (collapsing) scope.launch { expand.animateTo(1f, motion.responsive()) }
            if (closingQueue) scope.launch { queueT.animateTo(1f, queueSpec) }
            throw e
        }
    }

    // Only while Now Playing covers the screen: a full-size pointer node would otherwise swallow
    // touches meant for the screens under the collapsed MorphBar.
    val watchTouches = mostlyOpen && ambientAllowed
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (watchTouches) Modifier.pointerInput(Unit) {
                // Any touch (without consuming it) resets the ambient idle timer.
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Press) lastTouch = System.currentTimeMillis()
                    }
                }
            } else Modifier,
        ),
    ) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val statusTop = max(WindowInsets.statusBars.getTop(density), WindowInsets.displayCutout.getTop(density)).toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val e = expand.value.coerceIn(0f, 1f)
        val px = { dp: Float -> dp * density.density }

        // ---- MorphBar geometry ----
        val miniArtH = if (wideVideo) max(px(56f), 201f) else px(48f)
        val miniArtW = if (wideVideo) miniArtH * 16f / 9f else miniArtH
        val barH = miniArtH + px(16f)
        val barLeft = contentLeftPx + px(8f)
        val barRight = W - px(8f)
        val barTop = barBottomPx - barH
        val miniLeft = barLeft + px(8f)
        val miniTop = barTop + px(8f)

        // ---- Full geometry ----
        val fullW: Float
        val fullH: Float
        val fullLeft: Float
        val fullTop: Float
        if (wide) {
            val size = min((H - statusTop - navBottom) * 0.62f, W * 0.42f).coerceAtLeast(px(80f))
            fullW = size; fullH = if (wideVideo) size * 9f / 16f else size
            fullLeft = W * 0.27f - size / 2f
            fullTop = statusTop + ((H - statusTop - navBottom - fullH) / 2f).coerceAtLeast(0f)
        } else {
            val maxW = W - px(if (wideVideo) 2 * 20f else 2 * 32f)
            val size = if (wideVideo) maxW else min(maxW, (H - statusTop - navBottom) * 0.44f)
            val scaleUp = if (immersive && !isYouTube) 1.08f else 1f
            fullW = size * scaleUp; fullH = (if (wideVideo) size * 9f / 16f else size) * scaleUp
            fullLeft = (W - fullW) / 2f
            fullTop = statusTop + px(72f) + if (immersive) px(36f) else 0f
        }
        // Lyrics mode (phones): the cover/video tucks into the top-left corner. YouTube stays ≥ 200 px tall.
        val lt = if (wide || pip) 0f else lyricsT
        val lyricArtH = if (wideVideo) max(px(72f), 201f) else px(60f)
        val lyricScale = lyricArtH / fullH
        val lyricLeft = px(24f)
        val lyricTop = statusTop + px(66f)

        // ---- Queue sheet geometry ----
        // The YouTube player is never covered: the sheet stops below the video on phones and sits
        // beside it on wide screens. Geometry is taken with Now Playing fully receded.
        val openLeft = lerp(fullLeft, lyricLeft, lt)
        val openTop = lerp(fullTop, lyricTop, lt)
        val openScale = lerp(1f, lyricScale, lt)
        val recedePivotX = W / 2f
        val recedePivotY = H * RECEDE_PIVOT_Y
        val queueBesideVideo = isYouTube && wide
        // Settled lyrics state (not the animation), so the sheet's size doesn't change every frame.
        val panelLt = if (wide || pip || !lyricsOpen) 0f else 1f
        val queuePanelH = if (isYouTube && !wide) {
            val videoBottom = lerp(fullTop, lyricTop, panelLt) + fullH * lerp(1f, lyricScale, panelLt)
            val receded = recedePivotY + (videoBottom - recedePivotY) * (1f - RECEDE)
            min(H * 0.78f, H - receded - px(12f)).coerceAtLeast(px(96f))
        } else H * 0.78f
        val queuePanelHNow by androidx.compose.runtime.rememberUpdatedState(queuePanelH)

        fun queueDragBy(dy: Float) {
            queueDragging = true
            scope.launch { queueT.snapTo((queueT.value - dy / queuePanelHNow).coerceIn(0f, 1f)) }
        }
        fun queueDragEnd(velocityY: Float) {
            val wasOpen = queueOpen
            val open = when {
                velocityY < -900f -> true
                velocityY > 900f -> false
                else -> queueT.value > (if (wasOpen) 0.7f else 0.3f)
            }
            if (open && !wasOpen) haptics.navigate()
            settleQueue(open, -velocityY / queuePanelHNow)
        }
        // Inside the list: a downward pull at its top drags the sheet; pushing back up raises it first.
        val queuePull = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && queueDragging && available.y < 0f && queueT.value < 1f) {
                        queueDragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0f && queueOpen) {
                        queueDragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!queueDragging) return Velocity.Zero
                    queueDragEnd(available.y)
                    return available
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (queueDragging) queueDragEnd(0f)
                    return Velocity.Zero
                }
            }
        }

        // Everything under the queue recedes as one piece while the sheet rises (scale + the scrim's dim).
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val q = if (e > 0.99f && !pip) queueT.value else 0f
                val k = 1f - RECEDE * q
                scaleX = k; scaleY = k
                transformOrigin = TransformOrigin(0.5f, RECEDE_PIVOT_Y)
            },
        ) {
            // ---- Now Playing sheet (under the travelling surface) ----
            if (e > 0.001f && !pip) {
                val sheetShift = (1f - e) * (H - barTop)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = sheetShift
                            alpha = (e * 1.6f).coerceAtMost(1f)
                        }
                        .clip(RoundedCornerShape(topStart = 28.dp * (1 - e), topEnd = 28.dp * (1 - e)))
                        .pointerInput(Unit) {
                            val tracker = VelocityTracker()
                            // 0 = undecided, 1 = moving the player, 2 = pulling the queue up (finger-locked).
                            var mode = 0
                            detectVerticalDragGestures(
                                onDragStart = { tracker.resetTracking(); mode = 0 },
                                onDragEnd = {
                                    val v = tracker.calculateVelocity().y
                                    if (mode == 2) queueDragEnd(v) else animateTo(if (v > 1200f || expand.value < 0.7f) 0f else 1f, -v / (H - barTop).coerceAtLeast(1f))
                                },
                                onDragCancel = { if (mode == 2) queueDragEnd(0f) else animateTo(1f) },
                            ) { change, dy ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (mode == 0) mode = if (dy < 0f && expand.value >= 0.99f && !queueOpen) 2 else 1
                                if (mode == 2) { queueDragBy(dy); return@detectVerticalDragGestures }
                                scope.launch { expand.snapTo((expand.value - dy / (H - barTop)).coerceIn(0f, 1f)) }
                            }
                        },
                ) {
                    LivingBackdrop(
                        palette, track.artworkUrl, track.id.value, settings.artworkMotion, settings.gyroParallax,
                        intensity = if (immersive) 1.4f else 1f, pulse = { beat.value },
                        artworkBlur = if (wide) 0f else lt,
                        movingGradient = settings.movingGradient && !isYouTube,
                    )
                    NowPlayingContent(
                        track = track, state = state, progress = progress, palette = palette, liked = liked,
                        artSpace = with(density) { fullH.toDp() }, artTopPx = fullTop, wide = wide, immersive = immersive,
                        onCollapse = { animateTo(0f) }, actions = actions, onQueue = { settleQueue(true) },
                        switchingVariant = switchingVariant,
                        lyricsT = lt, lyricsOpen = lyricsOpen,
                        lyricArtWidth = with(density) { (fullW * lyricScale).toDp() }, lyricArtHeight = with(density) { lyricArtH.toDp() },
                        onLyrics = { lyricsOpen = !lyricsOpen; if (lyricsOpen) immersive = false },
                        lyricsFull = lyricsFull, onToggleFull = { lyricsFull = !lyricsFull },
                    )
                }
            }

            // ---- MorphBar ----
            if (e < 0.999f && dockRect == null && !pip) {
                val singing = com.arnav.music.ui.lyrics.nowSinging(track, progress, settings.miniPlayerLyrics)
                MorphBar(
                    track = track, state = state, progress = progress, liked = liked, lyricLine = singing,
                    modifier = Modifier
                        .offset { IntOffset(barLeft.roundToInt(), barTop.roundToInt()) }
                        .requiredSize(with(density) { (barRight - barLeft).toDp() }, with(density) { barH.toDp() })
                        .graphicsLayer { alpha = 1f - (e * 2.2f).coerceAtMost(1f); translationY = -e * px(40f) },
                    artWidthPx = miniArtW,
                    onExpand = { animateTo(1f) },
                    onDragExpand = { delta -> scope.launch { expand.snapTo((expand.value + delta / (H - barTop)).coerceIn(0f, 1f)) } },
                    onDragEnd = { v -> animateTo(if (v < -900f || expand.value > 0.25f) 1f else 0f, -v / (H - barTop).coerceAtLeast(1f)) },
                    actions = actions,
                )
            }

            // ---- The travelling surface: artwork (local) or the visible YouTube player ----
            // When opened from a card, it starts at that card's artwork instead of the MorphBar.
            val origin = launchOrigin
            LaunchedEffect(origin, expand.isRunning, e) { if (origin != null && !expand.isRunning && (e >= 0.999f || e <= 0.001f)) onLaunchConsumed() }
            val dock = dockRect
            val startLeft = origin?.left ?: dock?.left ?: miniLeft
            val startTop = origin?.top ?: dock?.top ?: miniTop
            val startW = origin?.width?.takeIf { it > 1f } ?: dock?.width?.takeIf { it > 1f } ?: miniArtW
            val left = if (pip) 0f else lerp(startLeft, openLeft, e)
            val top = if (pip) 0f else lerp(startTop, openTop, e)
            val surfW = if (pip) W else fullW
            val surfH = if (pip) H else fullH
            val scale = if (pip) 1f else lerp(startW / fullW, 1f, e) * lerp(1f, lyricScale, lt * e)
            // Swipeable cover carousel once Now Playing has settled (on-device covers only).
            val carousel = !isYouTube && !wide && !immersive && !pip && lt < 0.001f && e >= 0.999f && !expand.isRunning && state.queue.items.size > 1
            val cornerFull = androidx.compose.ui.unit.lerp(22.dp, with(density) { (px(12f) / max(lyricScale, 0.05f)).toDp() }, lt)
            val cornerMini = with(density) { (px(if (dock != null) 18f else 10f) / scale).toDp() }
            val corner = if (pip) 0.dp else androidx.compose.ui.unit.lerp(cornerMini, cornerFull, e)
            // Double tap to seek only on the fully open player (not the mini bar, lyrics corner or PiP).
            val seekHere = canSeekTap && settledOpen && lt < 0.001f
            // A soft halo in the cover's colour behind the artwork; it swells a little on the beat.
            val haloA = if (pip) 0f else e * (1f - lt) * (1f - lyricsFullT) * (if (immersive) 0.6f else 1f)
            if (haloA > 0.01f) {
                val haloColor = Color(palette.accent)
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(left + surfW * scale / 2f, top + surfH * scale / 2f)
                    val r = max(surfW, surfH) * scale * (0.78f + 0.05f * beat.value)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(haloColor.copy(alpha = 0.34f * haloA), haloColor.copy(alpha = 0.1f * haloA), Color.Transparent),
                            center = center, radius = r,
                        ),
                        radius = r, center = center,
                    )
                }
            }
            if (pip) Box(Modifier.fillMaxSize().background(Color.Black))
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .requiredSize(with(density) { surfW.toDp() }, with(density) { surfH.toDp() })
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        // Breathing/settling scales around the cover's centre, only once Now Playing is open.
                        val k = if (!isYouTube && !pip) 1f + (coverScale() - 1f) * e else 1f
                        scaleX = scale * k; scaleY = scale * k
                        translationX = size.width * scale * (1f - k) / 2f
                        translationY = size.height * scale * (1f - k) / 2f
                        alpha = if (carousel) 0f else if (!isYouTube) 1f - lyricsFullT else 1f
                        shadowElevation = if (pip) 0f else max(e, if (dock != null) 0.5f else 0f) * 24.dp.toPx()
                        shape = RoundedCornerShape(corner)
                        clip = true
                    },
            ) {
                if (isYouTube) {
                    // One call site for the single persistent player (switching Song/Video must not recreate it).
                    // Cover-art uploads: the player stays visible and playing; only the empty side bars fall
                    // outside the square frame. Double taps are only observed: every touch still reaches the player.
                    Box(
                        Modifier.fillMaxSize().observeDoubleTaps(seekHere, seekTaps) { d, fx, fy -> seekStep(d, fx, fy) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Keep the hidden WebView at a stable usable size while artwork morphs
                        // down to the same compact mini-player used for local songs.
                        YouTubeSurface(youtube, (if (squareArt) Modifier.requiredSize(356.dp, 200.dp) else Modifier.fillMaxSize())
                            .graphicsLayer { alpha = if (squareArt) 0f else 1f })
                        if (squareArt) TrackArtwork(track, state.queue.currentIndex, Modifier.fillMaxSize())
                    }
                } else {
                    TrackArtwork(
                        track = track, index = state.queue.currentIndex,
                        revealKey = state.queue.items.firstOrNull()?.uid,
                        revealFromRight = e < 0.5f,
                        swap = if (particlesOn) swap else null, swapEnabled = swapEnabled,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(track.id, lyricsOpen) {
                                if (lyricsOpen) return@pointerInput
                                // Swipe sideways to change song; pull down (like iOS) to close the player;
                                // push up to bring the queue up under the finger.
                                var dx = 0f
                                var dy = 0f
                                var axis = 0
                                var active = false
                                val tracker = VelocityTracker()
                                detectDragGestures(
                                    onDragStart = { dx = 0f; dy = 0f; axis = 0; active = expand.value >= 0.99f; tracker.resetTracking() },
                                    onDragEnd = {
                                        if (active) when (axis) {
                                            1 -> if (dx < -120f) actions.next() else if (dx > 120f) actions.previous()
                                            2 -> animateTo(if (tracker.calculateVelocity().y > 1000f || expand.value < 0.75f) 0f else 1f)
                                            3 -> queueDragEnd(tracker.calculateVelocity().y)
                                        }
                                    },
                                    onDragCancel = {
                                        if (active && axis == 2) animateTo(1f)
                                        if (active && axis == 3) queueDragEnd(0f)
                                    },
                                ) { change, drag ->
                                    if (!active) return@detectDragGestures
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    dx += drag.x; dy += drag.y
                                    if (axis == 0 && (abs(dx) > 18f || abs(dy) > 18f)) {
                                        axis = if (abs(dx) > abs(dy)) 1 else if (dy < 0f && !queueOpen) 3 else 2
                                    }
                                    if (axis == 2) {
                                        change.consume()
                                        scope.launch { expand.snapTo((expand.value - drag.y / (H - barTop)).coerceIn(0f, 1f)) }
                                    } else if (axis == 3) {
                                        change.consume()
                                        queueDragBy(drag.y)
                                    }
                                }
                            }
                            .coverTaps(
                                seek = if (seekHere && !lyricsOpen) seekTaps else null,
                                onTap = { if (expand.value < 0.5f) animateTo(1f) else if (lyricsOpen) lyricsOpen = false else immersive = !immersive },
                                onLongPress = { haptics.longPress(); actions.onMore(track) },
                                onSeek = { d, fx, fy -> seekStep(d, fx, fy) },
                                longPressLabel = "More actions",
                            )
                            .semantics {
                                contentDescription = "Artwork for ${track.title}"
                                customActions = buildList {
                                    add(CustomAccessibilityAction("Next track") { actions.next(); true })
                                    add(CustomAccessibilityAction("Previous track") { actions.previous(); true })
                                    add(CustomAccessibilityAction(if (immersive) "Exit immersive" else "Immersive mode") { immersive = !immersive; true })
                                    if (canSeekTap) {
                                        add(CustomAccessibilityAction("Back 5 seconds") { seekStep(-1, 0.25f, 0.5f); true })
                                        add(CustomAccessibilityAction("Forward 5 seconds") { seekStep(1, 0.75f, 0.5f); true })
                                    }
                                }
                            },
                    )
                }
            }

            // The YouTube seek feedback sits just under the video, never on it.
            if (isYouTube && !pip && e > 0.99f) {
                SeekChip(
                    seekTaps,
                    Modifier
                        .offset { IntOffset(left.roundToInt(), (top + fullH * scale + px(8f)).roundToInt()) }
                        .width(with(density) { (fullW * scale).toDp() }),
                )
            }

            val pullMode = remember { intArrayOf(0) }
            if (carousel) {
                CoverCarousel(
                    state = state, topPx = fullTop, itemWidthPx = fullW, screenWidthPx = W,
                    // A swipe through the carousel already slid the new cover in: no particle swap for it.
                    onSettle = { p -> swap.suppressNext(); actions.skipTo(p) },
                    onPullDown = { dy ->
                        if (pullMode[0] == 0) pullMode[0] = if (dy < 0f && !queueOpen) 2 else 1
                        if (pullMode[0] == 2) queueDragBy(dy)
                        else scope.launch { expand.snapTo((expand.value - dy / (H - barTop)).coerceIn(0f, 1f)) }
                    },
                    currentScale = coverScale,
                    onPullEnd = { v ->
                        val m = pullMode[0]
                        pullMode[0] = 0
                        if (m == 2) queueDragEnd(v) else animateTo(if (v > 1000f || expand.value < 0.75f) 0f else 1f, -v / (H - barTop).coerceAtLeast(1f))
                    },
                    onTap = { immersive = !immersive },
                    onLongPress = { t -> haptics.longPress(); actions.onMore(t) },
                    seek = if (seekHere) seekTaps else null,
                    onSeek = { d, fx, fy -> seekStep(d, fx, fy) },
                    swap = if (particlesOn) swap else null, swapEnabled = swapEnabled,
                )
            }

            // ---- Particle cover change + double-tap feedback, laid exactly over the open cover ----
            if (!isYouTube && !pip && e > 0.99f && lt < 0.001f) {
                val coverMod = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .requiredSize(with(density) { (fullW * scale).toDp() }, with(density) { (fullH * scale).toDp() })
                if (particlesOn && swap.busy(track, swapEnabled)) {
                    CoverParticleLayer(swap, track, coverMod.graphicsLayer { val k = coverScale(); scaleX = k; scaleY = k })
                }
                SeekRipple(seekTaps, coverMod.graphicsLayer { val k = coverScale(); scaleX = k; scaleY = k }.clip(RoundedCornerShape(22.dp)))
            }
        }

        // ---- Ambient (OLED idle): everything sinks to black except a hairline progress glow on the edge ----
        if (ambientT > 0.001f && e > 0.99f) {
            AmbientEdgeGlow(
                t = ambientT, progress = progress, accent = Color(palette.accent), track = track,
                // Over lyrics the page only dims, so the words stay readable while the edge glows.
                lyrics = lyricsOpen,
                // Never draw over the YouTube player: it must stay fully visible.
                keepClear = if (isYouTube) androidx.compose.ui.geometry.Rect(openLeft, openTop, openLeft + fullW * openScale, openTop + fullH * openScale) else null,
                onWake = { ambient = false; lastTouch = System.currentTimeMillis() },
            )
        }

        // ---- Queue panel ----
        if (e > 0.99f && !pip && queueVisible) {
            // Scrim over the receded player. It dims everything except the YouTube video, and dragging on
            // it moves the sheet just like dragging the sheet itself.
            val videoRect = if (isYouTube) androidx.compose.ui.geometry.Rect(
                openLeft, openTop,
                openLeft + fullW * openScale, openTop + fullH * openScale,
            ) else null
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = { queueDragEnd(tracker.calculateVelocity().y) },
                            onDragCancel = { queueDragEnd(0f) },
                        ) { change, dy ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            queueDragBy(dy)
                        }
                    }
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClickLabel = "Close queue") { settleQueue(false) },
            ) {
                val q = queueT.value
                val dim = Color.Black.copy(alpha = 0.45f * q)
                val rect = videoRect
                if (rect == null) {
                    drawRect(dim)
                } else {
                    // The video recedes with everything else: keep exactly its current rect clear.
                    val k = 1f - RECEDE * q
                    val l = recedePivotX + (rect.left - recedePivotX) * k
                    val t = recedePivotY + (rect.top - recedePivotY) * k
                    val r = recedePivotX + (rect.right - recedePivotX) * k
                    val b = recedePivotY + (rect.bottom - recedePivotY) * k
                    drawRect(dim, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, t.coerceAtLeast(0f)))
                    drawRect(dim, topLeft = Offset(0f, b), size = androidx.compose.ui.geometry.Size(size.width, (size.height - b).coerceAtLeast(0f)))
                    drawRect(dim, topLeft = Offset(0f, t), size = androidx.compose.ui.geometry.Size(l.coerceAtLeast(0f), b - t))
                    drawRect(dim, topLeft = Offset(r, t), size = androidx.compose.ui.geometry.Size((size.width - r).coerceAtLeast(0f), b - t))
                }
            }
            Box(
                Modifier
                    .then(if (queueBesideVideo) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth())
                    .height(with(density) { queuePanelH.toDp() })
                    .align(if (queueBesideVideo) Alignment.BottomEnd else Alignment.BottomCenter)
                    .graphicsLayer { translationY = (1f - queueT.value) * size.height }
                    .glass(GlassMaterial.Elevated, RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl), tint = Color(palette.accent))
                    // Grab the sheet anywhere outside the list (handle, title, chips) to drag it.
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = { queueDragEnd(tracker.calculateVelocity().y) },
                            onDragCancel = { queueDragEnd(0f) },
                        ) { change, dy ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            queueDragBy(dy)
                        }
                    },
            ) {
                CompositionLocalProvider(LocalQueuePull provides queuePull) {
                    queueContent { settleQueue(false) }
                }
            }
        }
    }
}

/**
 * Now Playing cover carousel: neighbours peek at the edges and shrink/dim with distance; settling
 * on another cover skips to it. Mirrors the queue and follows external track changes.
 */
@Composable
private fun CoverCarousel(
    state: PlayerState,
    topPx: Float,
    itemWidthPx: Float,
    screenWidthPx: Float,
    onSettle: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: (Track) -> Unit,
    onPullDown: (Float) -> Unit = {},
    onPullEnd: (Float) -> Unit = {},
    currentScale: () -> Float = { 1f },
    /** Double tap to seek on the covers (null = off). */
    seek: SeekTapState? = null,
    onSeek: (dir: Int, fx: Float, fy: Float) -> Unit = { _, _, _ -> },
    /** Particle cover change: the current cover stays hidden until its particles have assembled. */
    swap: CoverSwap? = null,
    swapEnabled: Boolean = false,
) {
    val density = LocalDensity.current
    val items = state.queue.items
    val current = state.queue.currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = current) { items.size }
    val latestCurrent by androidx.compose.runtime.rememberUpdatedState(current)
    val particleJump = swap != null && swapEnabled
    LaunchedEffect(current) {
        if (pager.currentPage != current && !pager.isScrollInProgress) {
            // With particles the cover changes in place: jump straight to its page underneath them.
            if (particleJump) pager.scrollToPage(current) else pager.animateScrollToPage(current)
        }
    }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { p -> if (p != latestCurrent && p in items.indices) onSettle(p) }
    }
    val side = with(density) { ((screenWidthPx - itemWidthPx) / 2f).coerceAtLeast(0f).toDp() }
    androidx.compose.foundation.pager.HorizontalPager(
        state = pager,
        modifier = Modifier.offset { IntOffset(0, topPx.roundToInt()) }.fillMaxWidth().height(with(density) { itemWidthPx.toDp() })
            .pointerInput(Unit) {
                // Vertical pulls close the player; the pager keeps horizontal swipes.
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { onPullEnd(tracker.calculateVelocity().y) },
                    onDragCancel = { onPullEnd(0f) },
                ) { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    onPullDown(dy)
                }
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = side),
        pageSpacing = 14.dp,
        key = { items[it].uid },
    ) { page ->
        val t = items[page].track
        val isCurrent = page == current
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val d = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                val sc = (1f - 0.12f * d) * (1f + (currentScale() - 1f) * (1f - d))
                scaleX = sc; scaleY = sc
                alpha = (1f - 0.45f * d) * (if (isCurrent && swap != null) swap.alpha(t.id, swapEnabled) else 1f)
                shadowElevation = (1f - d) * 24.dp.toPx()
                shape = RoundedCornerShape(22.dp)
                clip = true
            }.coverTaps(
                seek = if (isCurrent) seek else null,
                onTap = onTap,
                onLongPress = { onLongPress(t) },
                onSeek = onSeek,
                longPressLabel = "More actions",
            )
                .semantics { contentDescription = "Cover ${page + 1} of ${items.size}: ${t.title}" },
        ) {
            Artwork(t.artworkUrl, t.id.value, Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape, decodeSize = 900)
        }
    }
}

/**
 * Track change as a "depth swap": the old cover sinks back (shrinks, drops, blurs where the platform
 * can blur cheaply) while the new one rises into place from the swipe direction.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun TrackArtwork(
    track: Track,
    index: Int,
    modifier: Modifier,
    revealKey: Any? = null,
    revealFromRight: Boolean = false,
    /** Particle cover change: replaces the depth swap / spiral reveal while it is enabled. */
    swap: CoverSwap? = null,
    swapEnabled: Boolean = false,
) {
    val motion = ArnavTheme.motion
    var lastIndex by remember { mutableStateOf(index) }
    val forward = index >= lastIndex
    LaunchedEffect(index) { lastIndex = index }
    // A brand-new queue (you pressed play on something) spirals the cover out from the play button.
    var lastReveal by remember { mutableStateOf(revealKey) }
    val spiralNow = revealKey != lastReveal && !motion.reduced
    LaunchedEffect(revealKey) { lastReveal = revealKey }
    val canBlur = android.os.Build.VERSION.SDK_INT >= 31 && !motion.reduced
    AnimatedContent(
        targetState = track,
        contentKey = { it.id },
        transitionSpec = {
            // The particle swap draws the change itself; the covers just trade places underneath it.
            if (swap != null && swapEnabled) return@AnimatedContent (EnterTransition.None togetherWith ExitTransition.None)
            if (spiralNow) return@AnimatedContent (fadeIn(tween(60)) togetherWith fadeOut(tween(520))).apply { targetContentZIndex = 1f }
            val dir = if (forward) 1 else -1
            val enter = slideInHorizontally(motion.offsetSpring()) { (it * 0.18f * dir * motion.travel).toInt() } +
                slideInVertically(motion.offsetSpring()) { (it * 0.06f * motion.travel).toInt() } +
                scaleIn(motion.expressive(), 0.92f) + fadeIn(tween(260))
            val exit = scaleOut(tween(380), 0.84f) +
                slideOutVertically(tween(380)) { (it * 0.05f * motion.travel).toInt() } +
                fadeOut(tween(320))
            (enter togetherWith exit).apply { targetContentZIndex = 1f }
        },
        modifier = modifier,
        label = "art",
    ) { t ->
        val blur by transition.animateFloat(transitionSpec = { tween(380) }, label = "sink") { st -> if (st == EnterExitState.PostExit) 18f else 0f }
        val spiral = remember(t.id) { spiralNow }
        val reveal by transition.animateFloat(transitionSpec = { tween(720, easing = androidx.compose.animation.core.FastOutSlowInEasing) }, label = "spiral") { st ->
            if (st == EnterExitState.Visible || !spiral) 1f else 0f
        }
        Artwork(
            t.artworkUrl, t.id.value,
            Modifier.fillMaxSize().graphicsLayer {
                if (swap != null) alpha = swap.alpha(t.id, swapEnabled)
                if (canBlur && blur > 0.5f) {
                    val r = blur.dp.toPx()
                    renderEffect = BlurEffect(r, r, TileMode.Decal)
                }
                if (spiral && reveal < 0.999f) {
                    val p = reveal
                    // A circle growing from the play button while the cover unwinds a quarter turn.
                    rotationZ = -24f * (1f - p)
                    clip = true
                    shape = androidx.compose.foundation.shape.GenericShape { sz, _ ->
                        val cx = if (revealFromRight) sz.width * 3.6f else sz.width * 0.5f
                        val cy = if (revealFromRight) sz.height * 0.5f else sz.height * 1.22f
                        val far = kotlin.math.hypot(kotlin.math.max(cx, kotlin.math.abs(sz.width - cx)), kotlin.math.max(cy, kotlin.math.abs(sz.height - cy)))
                        val rad = far * p
                        addOval(androidx.compose.ui.geometry.Rect(cx - rad, cy - rad, cx + rad, cy + rad))
                    }
                }
            },
            shape = androidx.compose.ui.graphics.RectangleShape, contentDescription = null, decodeSize = 1000,
        )
    }
}

/** Attach the service-owned view; UI disposal must not stop the background song. */
@Composable
private fun YouTubeSurface(engine: YouTubeEngine, modifier: Modifier) {
    AndroidView(factory = {
        engine.playerView().also { view -> (view.parent as? android.view.ViewGroup)?.removeView(view) }
    }, modifier = modifier.background(Color.Black))
}

@Composable
private fun MorphBar(
    track: Track,
    state: PlayerState,
    progress: Progress,
    liked: Boolean,
    modifier: Modifier,
    artWidthPx: Float,
    lyricLine: String? = null,
    onExpand: () -> Unit,
    onDragExpand: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    actions: PlayerActions,
) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val density = LocalDensity.current
    val interaction = rememberInteraction()
    val tracker = remember { VelocityTracker() }
    Box(
        modifier
            .glass(GlassMaterial.Thick, RoundedCornerShape(Radius.l))
            .pressScale(interaction, 0.985f)
            .clickable(interaction, indication = null, onClickLabel = "Open player", onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { onDragEnd(tracker.calculateVelocity().y) },
                    onDragCancel = { onDragEnd(0f) },
                ) { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    onDragExpand(-dy)
                }
            }
            .pointerInput(track.id) {
                var total = 0f
                detectHorizontalDragGestures(onDragEnd = {
                    if (total < -140f) actions.next() else if (total > 140f) actions.previous()
                    total = 0f
                }) { _, dx -> total += dx }
            },
    ) {
        // Liquid glass on Android 13+: the cover, blurred and refracted at the pane's edges.
        com.arnav.music.ui.theme.LiquidGlassBackdrop(track.artworkUrl, track.id.value, Radius.l, alpha = 0.42f)
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(with(density) { artWidthPx.toDp() }))
            Spacer(Modifier.width(Space.m))
            AnimatedContent(track, contentKey = { it.id }, transitionSpec = {
                (fadeIn(motion.fast()) + slideInHorizontally { it / 6 }) togetherWith fadeOut(motion.fast())
            }, modifier = Modifier.weight(1f), label = "bartitle") { t ->
                Column {
                    Text(t.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // "Now singing": the current lyric line replaces the artist while synced lyrics play.
                    AnimatedContent(
                        lyricLine, transitionSpec = {
                            (fadeIn(motion.fast()) + slideInVertically { it / 2 }) togetherWith (fadeOut(motion.fast()) + slideOutVertically { -it / 2 })
                        }, label = "singing",
                    ) { line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (line != null) {
                                Icon(Icons.Rounded.Lyrics, null, tint = c.accent, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(line, style = ArnavTheme.type.bodySmall, color = c.content.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { contentDescription = "Now singing: $line" })
                            } else {
                                if (t.source == SourceType.YOUTUBE) { SourceBadge(true); Spacer(Modifier.width(6.dp)) }
                                Text(t.artist, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            HeartButton(liked, { actions.toggleLike(track) }, size = 20.dp)
            PlayPauseButton(state.isPlaying, actions.togglePlay, size = 40.dp, container = c.content, content = c.background, buffering = state.isBuffering)
            ArnavIconButton(Icons.Rounded.SkipNext, "Next", actions.next, enabled = state.queue.hasNext && state.capabilities.canSkip)
        }
        // Hairline progress along the bottom edge.
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.content.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(c.accent))
        }
    }
}

@Composable
private fun NowPlayingContent(
    track: Track,
    state: PlayerState,
    progress: Progress,
    palette: ArtworkPalette,
    liked: Boolean,
    artSpace: androidx.compose.ui.unit.Dp,
    artTopPx: Float,
    wide: Boolean,
    immersive: Boolean,
    onCollapse: () -> Unit,
    actions: PlayerActions,
    onQueue: () -> Unit,
    switchingVariant: Boolean = false,
    lyricsT: Float = 0f,
    lyricsOpen: Boolean = false,
    lyricArtWidth: androidx.compose.ui.unit.Dp = 60.dp,
    lyricArtHeight: androidx.compose.ui.unit.Dp = 60.dp,
    onLyrics: () -> Unit = {},
    lyricsFull: Boolean = false,
    onToggleFull: () -> Unit = {},
) {
    val on = Color(palette.onBackdrop)
    val muted = Color(palette.onBackdropMuted)
    val accent = Color(palette.accent)
    val motion = ArnavTheme.motion
    val density = LocalDensity.current
    val controlsAlpha by animateFloatAsState(if (immersive) 0f else 1f, motion.cinematic(), label = "immersive")
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().padding(WindowInsetsPaddingStatusNav()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.animation.AnimatedVisibility(!(lyricsOpen && lyricsFull && !wide)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            ArnavIconButton(Icons.Rounded.KeyboardArrowDown, "Collapse player", onCollapse, tint = on)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (track.source == SourceType.YOUTUBE) {
                    VariantSwitch(track.variant, switchingVariant, on, muted, Color(palette.accent), actions.switchVariant)
                } else {
                    Text(if (lyricsOpen) "LYRICS" else "NOW PLAYING", style = ArnavTheme.type.overline, color = muted)
                    Text(track.album ?: "On this device", style = ArnavTheme.type.caption, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ArnavIconButton(Icons.Rounded.MoreHoriz, "More actions", { actions.onMore(track) }, tint = on)
        }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Column(Modifier.weight(1f).padding(end = Space.xxl).graphicsLayer { alpha = controlsAlpha }, verticalArrangement = Arrangement.Center) {
                    if (lyricsOpen) {
                        Spacer(Modifier.height(Space.l))
                        TitleRow(track, liked, on, muted, actions)
                        com.arnav.music.ui.lyrics.LyricsPanel(
                            track = track, progress = progress, isPlaying = state.isPlaying, onSeek = actions.seekTo,
                            on = on, muted = muted, accent = accent,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Space.xl),
                        )
                        Transport(track, state, progress, on, muted, accent, actions, onQueue, onLyrics, lyricsOpen)
                        Spacer(Modifier.height(Space.l))
                    } else {
                        Spacer(Modifier.weight(1f))
                        MetaAndControls(track, state, progress, liked, on, muted, accent, actions, onQueue, onLyrics, lyricsOpen)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                if (lyricsT < 0.999f) {
                    Column(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - (lyricsT * 1.6f).coerceAtMost(1f) }) {
                        val topPad = with(density) { (artTopPx).toDp() } - 64.dp - WindowInsetsTopDp()
                        Spacer(Modifier.height(topPad.coerceAtLeast(0.dp) + artSpace + Space.xl))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).graphicsLayer { alpha = controlsAlpha }) {
                            MetaAndControls(track, state, progress, liked, on, muted, accent, actions, onQueue, onLyrics, lyricsOpen)
                            // Extras only where the screen has room for them.
                            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                            if (screenH >= 760) {
                                Spacer(Modifier.height(Space.s))
                                VolumeRow(on, muted)
                            }
                            if (screenH >= (if (track.source == SourceType.YOUTUBE) 860 else 700)) {
                                Spacer(Modifier.height(Space.m))
                                UpNextChip(state, on, muted, onQueue)
                            }
                            if (track.source == SourceType.YOUTUBE) {
                                Spacer(Modifier.height(Space.l))
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).background(on.copy(alpha = 0.06f))
                                        .clickable {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${track.playbackRef}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        }
                                        .padding(Space.m),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Visibility, null, tint = muted, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Space.s))
                                    Text("Background playback enabled. Song mode shows artwork; Video mode shows the video. Tap to open YouTube Music.", style = ArnavTheme.type.caption, color = muted, modifier = Modifier.weight(1f))
                                    Icon(Icons.Rounded.OpenInNew, "Open in YouTube Music", tint = on, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                if (lyricsT > 0.001f) {
                    // Apple Music-style lyrics: small cover + title up top, big synced lines, compact controls below.
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((lyricsT - 0.25f) / 0.75f).coerceIn(0f, 1f) }) {
                    Column(Modifier.fillMaxSize()) {
                        val video = track.source == SourceType.YOUTUBE
                        androidx.compose.animation.AnimatedVisibility(!lyricsFull) {
                            Row(
                                Modifier.fillMaxWidth().height(lyricArtHeight + 2.dp).padding(start = 24.dp + lyricArtWidth + Space.m, end = Space.l),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, style = ArnavTheme.type.titleSmall, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, style = ArnavTheme.type.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { actions.openArtist(track.artist) })
                                }
                                HeartButton(liked, { actions.toggleLike(track) }, size = 22.dp, tint = on)
                            }
                        }
                        // The YouTube player stays visible in full-screen lyrics too: keep its corner clear.
                        if (lyricsFull && video) Spacer(Modifier.height(lyricArtHeight + 58.dp))
                        com.arnav.music.ui.lyrics.LyricsPanel(
                            track = track, progress = progress, isPlaying = state.isPlaying, onSeek = actions.seekTo,
                            on = on, muted = muted, accent = accent,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = if (lyricsFull) Space.xxl else Space.xl, bottom = Space.xxl),
                        )
                        androidx.compose.animation.AnimatedVisibility(!lyricsFull) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                                Transport(track, state, progress, on, muted, accent, actions, onQueue, onLyrics, lyricsOpen)
                            }
                        }
                    }
                    ArnavIconButton(
                        if (lyricsFull) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        if (lyricsFull) "Exit full-screen lyrics" else "Full-screen lyrics",
                        onToggleFull, tint = muted, size = 18.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = if (lyricsFull) 0.dp else lyricArtHeight + 4.dp, end = Space.s),
                    )
                    }
                }
            }
        }
    }
    state.issue?.let { issue ->
        IssueToast(issue, actions)
    }
}

@Composable
private fun TitleRow(track: Track, liked: Boolean, on: Color, muted: Color, actions: PlayerActions) {
    val motion = ArnavTheme.motion
    Row(
        Modifier.fillMaxWidth().pointerInput(track.id) {
            var total = 0f
            detectHorizontalDragGestures(onDragEnd = {
                if (total < -120f) actions.next() else if (total > 120f) actions.previous()
                total = 0f
            }) { _, dx -> total += dx }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(track, contentKey = { it.id }, transitionSpec = {
            (fadeIn(motion.fast()) + slideInHorizontally(motion.offsetSpring()) { (it * 0.08f * motion.travel).toInt() }) togetherWith
                (fadeOut(motion.fast()) + slideOutHorizontally(motion.offsetSpring()) { (-it * 0.08f * motion.travel).toInt() })
        }, modifier = Modifier.weight(1f), label = "meta") { t ->
            Column {
                Text(t.title, style = ArnavTheme.type.headline, color = on, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(t.artist, style = ArnavTheme.type.body, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { actions.openArtist(t.artist) })
                val context = listOfNotNull(t.album, t.credits).joinToString(" · ")
                if (context.isNotBlank() && t.source == SourceType.YOUTUBE) {
                    Text(context, style = ArnavTheme.type.caption, color = muted.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HeartButton(liked, { actions.toggleLike(track) }, size = 26.dp, tint = on)
    }
}

@Composable
private fun MetaAndControls(
    track: Track, state: PlayerState, progress: Progress, liked: Boolean,
    on: Color, muted: Color, accent: Color, actions: PlayerActions, onQueue: () -> Unit,
    onLyrics: () -> Unit, lyricsOpen: Boolean,
) {
    TitleRow(track, liked, on, muted, actions)
    Spacer(Modifier.height(Space.l))
    // Long mixes and audiobook-style files: jump between their chapters (draws nothing otherwise).
    com.arnav.music.core.chapters.ChapterStrip(track, progress, actions.seekTo, Modifier.padding(bottom = Space.m))
    Transport(track, state, progress, on, muted, accent, actions, onQueue, onLyrics, lyricsOpen)
}

@Composable
private fun Transport(
    track: Track, state: PlayerState, progress: Progress,
    on: Color, muted: Color, accent: Color, actions: PlayerActions, onQueue: () -> Unit,
    onLyrics: () -> Unit, lyricsOpen: Boolean,
) {
    val envelope = if (track.source == SourceType.LOCAL) rememberEnvelope(track.id.value) else null
    SeekBar(progress, state.capabilities.canSeek, accent, on, muted, actions.seekTo, envelope)
    Spacer(Modifier.height(Space.m))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        ArnavIconButton(Icons.Rounded.Shuffle, if (state.queue.shuffled) "Shuffle on" else "Shuffle off", actions.toggleShuffle, tint = if (state.queue.shuffled) accent else muted)
        ArnavIconButton(Icons.Rounded.SkipPrevious, "Previous", actions.previous, tint = on, size = 34.dp, enabled = state.capabilities.canSkip)
        PlayPauseButton(state.isPlaying, actions.togglePlay, container = on, content = Color(0xFF0B0B0F).takeIf { on.red > 0.5f } ?: Color.White, buffering = state.isBuffering)
        ArnavIconButton(Icons.Rounded.SkipNext, "Next", actions.next, tint = on, size = 34.dp, enabled = state.capabilities.canSkip && (state.queue.hasNext || state.repeat == RepeatMode.ALL))
        ArnavIconButton(
            if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat ${state.repeat.name.lowercase()}", actions.cycleRepeat, tint = if (state.repeat != RepeatMode.OFF) accent else muted,
        )
    }
    Spacer(Modifier.height(Space.m))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val context = LocalContext.current
        ArnavIconButton(Icons.Rounded.SpeakerGroup, "Output device", { openOutputSwitcher(context) }, tint = muted, size = 20.dp)
        Box(contentAlignment = Alignment.Center) {
            val pill by animateFloatAsState(if (lyricsOpen) 1f else 0f, ArnavTheme.motion.responsive(), label = "lyricsPill")
            if (pill > 0.01f) Box(Modifier.size(40.dp, 30.dp).graphicsLayer { alpha = pill; scaleX = 0.7f + 0.3f * pill; scaleY = 0.7f + 0.3f * pill }.clip(CircleShape).background(on.copy(alpha = 0.14f)))
            ArnavIconButton(Icons.Rounded.Lyrics, if (lyricsOpen) "Hide lyrics" else "Lyrics", onLyrics, tint = if (lyricsOpen) on else muted, size = 20.dp)
        }
        // Sing: lowers the vocals of songs on this phone (on-device DSP).
        if (track.source == SourceType.LOCAL) com.arnav.music.ui.lyrics.SingButton(enabled = true, tint = muted, accent = accent)
        ArnavIconButton(Icons.Rounded.Bedtime, if (state.sleep != null) "Sleep timer on" else "Sleep timer", actions.onSleep, tint = if (state.sleep != null) accent else muted, size = 20.dp)
        ArnavIconButton(Icons.Rounded.Groups, "Credits", { actions.openCredits(track) }, tint = muted, size = 20.dp)
        ArnavIconButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", onQueue, tint = muted, size = 20.dp)
    }
    if (state.sleep != null) SleepCountdown(state.sleep, muted)
}

@Composable
private fun SeekBar(progress: Progress, canSeek: Boolean, accent: Color, on: Color, muted: Color, onSeek: (Long) -> Unit, envelope: ByteArray? = null) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: progress.fraction
    val motion = ArnavTheme.motion
    val thumb by animateFloatAsState(if (dragging != null) 1f else 0f, motion.responsive(), label = "thumb")
    val haptics = ArnavTheme.haptics
    Column {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .semantics {
                    contentDescription = "Seek bar, ${Formatters.duration(progress.positionMs)} of ${Formatters.duration(progress.durationMs)}"
                }
                .then(
                    if (canSeek && progress.durationMs > 0) Modifier.pointerInput(progress.durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f); haptics.select() },
                            onDragEnd = { dragging?.let { onSeek((it * progress.durationMs).toLong()) }; dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ ->
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            // A soft tick at every tenth of the song while scrubbing.
                            if (((dragging ?: f) * 10).toInt() != (f * 10).toInt()) haptics.snap()
                            dragging = f
                        }
                    }.pointerInput(progress.durationMs) {
                        detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * progress.durationMs).toLong()) }
                    } else Modifier,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = lerpDp(4.dp, 8.dp, thumb)
            if (envelope != null && envelope.size >= 8) {
                // Wave: the song's measured loudness as slim bars; played part bright, the rest faint.
                val grow = 1f + 0.25f * thumb
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val barW = 2.5.dp.toPx()
                    val gap = 1.5.dp.toPx()
                    val count = (size.width / (barW + gap)).toInt().coerceAtLeast(1)
                    val minH = 3.dp.toPx()
                    val maxH = size.height
                    for (i in 0 until count) {
                        val pos = i.toFloat() / count
                        val idx = (pos * envelope.size).toInt().coerceIn(0, envelope.size - 1)
                        val level = (envelope[idx].toInt() and 0xFF) / 255f
                        val bh = ((minH + (maxH * 0.75f - minH) * level) * grow).coerceAtMost(maxH)
                        val x = i * (barW + gap)
                        drawRoundRect(
                            color = if (pos <= fraction) on else on.copy(alpha = 0.22f),
                            topLeft = Offset(x, (size.height - bh) / 2f),
                            size = androidx.compose.ui.geometry.Size(barW, bh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f),
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(on.copy(alpha = 0.16f)))
                Box(Modifier.fillMaxWidth(fraction).height(h).clip(RoundedCornerShape(4.dp)).background(on))
            }
            Box(
                Modifier.offset(x = maxWidth * fraction - 7.dp).size(14.dp).graphicsLayer { scaleX = 0.6f + 0.6f * thumb; scaleY = 0.6f + 0.6f * thumb; alpha = 0.4f + 0.6f * thumb }
                    .clip(androidx.compose.foundation.shape.CircleShape).background(accent),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Formatters.duration(dragging?.let { (it * progress.durationMs).toLong() } ?: progress.positionMs), style = ArnavTheme.type.numeric, color = muted)
            Spacer(Modifier.weight(1f))
            Text(if (progress.durationMs > 0) "-" + Formatters.duration(progress.durationMs - progress.positionMs) else "", style = ArnavTheme.type.numeric, color = muted)
        }
    }
}

/** The song's loudness envelope from on-device analysis (null until analyzed). */
@Composable
private fun rememberEnvelope(trackId: String): ByteArray? {
    val dao = org.koin.compose.koinInject<com.arnav.music.core.db.ArnavDatabase>().audioFeatures()
    val flow = remember(trackId) { dao.observe(trackId) }
    val row by flow.collectAsState(initial = null)
    return row?.takeIf { it.ok }?.envelope
}

private fun lerpDp(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp, t: Float) = androidx.compose.ui.unit.lerp(a, b, t)

@Composable
private fun WindowInsetsPaddingStatusNav(): androidx.compose.foundation.layout.PaddingValues {
    val d = LocalDensity.current
    return androidx.compose.foundation.layout.PaddingValues(
        top = with(d) { WindowInsets.statusBars.getTop(d).toDp() } + 8.dp,
        bottom = with(d) { WindowInsets.navigationBars.getBottom(d).toDp() } + 8.dp,
    )
}

@Composable
private fun WindowInsetsTopDp(): androidx.compose.ui.unit.Dp {
    val d = LocalDensity.current
    return with(d) { WindowInsets.statusBars.getTop(d).toDp() } + 8.dp
}

private fun openOutputSwitcher(context: android.content.Context) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        } else {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun IssueToast(issue: PlaybackIssue, actions: PlayerActions) {
    val c = ArnavTheme.colors
    val context = LocalContext.current
    val text = when (issue) {
        is PlaybackIssue.Unavailable ->
            if (issue.searching) "“${issue.title.take(40)}” can't play in Arnav Music. Looking for another upload…"
            else "“${issue.title.take(40)}” can't be played outside YouTube. Skipping in a few seconds."
        is PlaybackIssue.Replaced -> "Playing another upload of “${issue.title.take(40)}”" + when (issue.variant) {
            com.arnav.music.domain.model.MediaVariant.SONG -> " (official audio)."
            com.arnav.music.domain.model.MediaVariant.VIDEO -> " (video)."
            null -> "."
        }
        is PlaybackIssue.VariantNotFound -> if (issue.want == com.arnav.music.domain.model.MediaVariant.SONG) "No official audio upload found for this song." else "No music video found for this song."
        PlaybackIssue.YouTubePausedInBackground -> "Paused — YouTube can't play while Arnav Music is in the background."
        PlaybackIssue.NetworkLost -> "Connection lost. Playback will resume when you're back online."
        PlaybackIssue.NeedsNotificationPermission -> "Allow notifications to control local playback from the lock screen."
    }
    Box(Modifier.fillMaxSize().padding(bottom = 120.dp, start = Space.gutter, end = Space.gutter), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().glass(GlassMaterial.Elevated, RoundedCornerShape(Radius.l)).clickable(onClick = actions.dismissIssue).padding(Space.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (issue is PlaybackIssue.Unavailable && issue.searching) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(Space.m))
                }
                Text(text, style = ArnavTheme.type.bodySmall, color = c.content, modifier = Modifier.weight(1f))
            }
            if (issue is PlaybackIssue.Unavailable && !issue.searching) {
                Spacer(Modifier.height(Space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    IssueAction("Find another upload", c.accent) { actions.findAnotherUpload() }
                    issue.videoId?.let { id ->
                        IssueAction("Open in YouTube Music", c.content) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    IssueAction("Skip", c.contentMuted) { actions.next() }
                }
            }
        }
    }
}

@Composable
private fun IssueAction(label: String, color: Color, onClick: () -> Unit) {
    Text(label, style = ArnavTheme.type.label, color = color,
        modifier = Modifier.clip(RoundedCornerShape(Radius.s)).background(color.copy(alpha = 0.1f)).clickable(onClick = onClick).padding(horizontal = Space.m, vertical = Space.s))
}

/** YouTube Music-style Song | Video switch. The YouTube player stays visible in both modes. */
@Composable
private fun VariantSwitch(
    current: com.arnav.music.domain.model.MediaVariant?,
    busy: Boolean,
    on: Color,
    muted: Color,
    accent: Color,
    onSwitch: (com.arnav.music.domain.model.MediaVariant) -> Unit,
) {
    val selected = current ?: com.arnav.music.domain.model.MediaVariant.VIDEO
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    Row(
        Modifier.clip(CircleShape).background(on.copy(alpha = 0.10f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.arnav.music.domain.model.MediaVariant.entries.forEach { v ->
            val isSel = v == selected
            val bg by androidx.compose.animation.animateColorAsState(if (isSel) on else Color.Transparent, motion.fast(), label = "vs")
            Row(
                Modifier.clip(CircleShape).background(bg)
                    .clickable(enabled = !busy && !isSel, role = androidx.compose.ui.semantics.Role.Tab, onClickLabel = "Switch to ${v.name.lowercase()}") { haptics.select(); onSwitch(v) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy && !isSel) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = accent, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (v == com.arnav.music.domain.model.MediaVariant.SONG) "Song" else "Video", style = ArnavTheme.type.label, color = if (isSel) Color(0xFF0B0B0F).takeIf { on.red > 0.5f } ?: Color.White else muted)
            }
        }
    }
}

@Composable
private fun SleepCountdown(sleep: com.arnav.music.core.playback.SleepTimer, color: Color) {
    val label = when (sleep) {
        is com.arnav.music.core.playback.SleepTimer.Countdown -> {
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(sleep) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) } }
            "Sleep in " + Formatters.duration((sleep.endsAt - now).coerceAtLeast(0))
        }
        com.arnav.music.core.playback.SleepTimer.EndOfTrack -> "Sleep after this track"
        com.arnav.music.core.playback.SleepTimer.EndOfQueue -> "Sleep after the queue"
    }
    Text(label, style = ArnavTheme.type.caption, color = color, modifier = Modifier.fillMaxWidth().padding(top = Space.xs), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

private const val AMBIENT_AFTER_MS = 8_000L
/** How far Now Playing shrinks back as the queue rises over it, and the height it shrinks towards. */
private const val RECEDE = 0.06f
private const val RECEDE_PIVOT_Y = 0.42f

/**
 * OLED ambient mode for an idle Now Playing screen: the page sinks to black and a hairline of light
 * traces the song's progress around the screen edge (following the display's rounded corners).
 * Any tap wakes it. [keepClear] is left untouched so a YouTube player is never covered.
 */
@Composable
private fun AmbientEdgeGlow(
    t: Float,
    progress: Progress,
    accent: Color,
    track: Track,
    lyrics: Boolean,
    keepClear: androidx.compose.ui.geometry.Rect?,
    onWake: () -> Unit,
) {
    val depth by animateFloatAsState(if (lyrics) 0.42f else 0.92f, tween(600), label = "ambientDepth")
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val cornerPx = remember(view) {
        val insets = view.rootWindowInsets
        val fromDisplay = if (android.os.Build.VERSION.SDK_INT >= 31 && insets != null) {
            insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
        } else null
        fromDisplay ?: with(density) { 28.dp.toPx() }
    }
    val edge = remember { androidx.compose.ui.graphics.Path() }
    val segment = remember { androidx.compose.ui.graphics.Path() }
    val measure = remember { androidx.compose.ui.graphics.PathMeasure() }
    // Burn-in care: the caption drifts by a few pixels every minute.
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); minute = System.currentTimeMillis() / 60_000L } }
    val drift = ((minute % 5L) - 2L).toInt()
    Box(
        Modifier.fillMaxSize()
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClickLabel = "Wake", onClick = onWake),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val dim = Color.Black.copy(alpha = depth * t)
            val clear = keepClear
            if (clear == null) {
                drawRect(dim)
            } else {
                drawRect(dim, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, clear.top.coerceAtLeast(0f)))
                drawRect(dim, topLeft = Offset(0f, clear.bottom), size = androidx.compose.ui.geometry.Size(size.width, (size.height - clear.bottom).coerceAtLeast(0f)))
                drawRect(dim, topLeft = Offset(0f, clear.top), size = androidx.compose.ui.geometry.Size(clear.left.coerceAtLeast(0f), clear.height))
                drawRect(dim, topLeft = Offset(clear.right, clear.top), size = androidx.compose.ui.geometry.Size((size.width - clear.right).coerceAtLeast(0f), clear.height))
            }
            val inset = 3.dp.toPx()
            val rr = (cornerPx - inset).coerceAtLeast(0f)
            edge.reset()
            // Start at top-centre and run clockwise, like a clock hand.
            val w = size.width; val h = size.height
            edge.moveTo(w / 2f, inset)
            edge.lineTo(w - inset - rr, inset)
            edge.quadraticTo(w - inset, inset, w - inset, inset + rr)
            edge.lineTo(w - inset, h - inset - rr)
            edge.quadraticTo(w - inset, h - inset, w - inset - rr, h - inset)
            edge.lineTo(inset + rr, h - inset)
            edge.quadraticTo(inset, h - inset, inset, h - inset - rr)
            edge.lineTo(inset, inset + rr)
            edge.quadraticTo(inset, inset, inset + rr, inset)
            edge.lineTo(w / 2f, inset)
            measure.setPath(edge, false)
            val len = measure.length
            val f = progress.fraction.coerceIn(0f, 1f)
            segment.reset()
            if (f > 0f && measure.getSegment(0f, len * f, segment, true)) {
                drawPath(segment, accent.copy(alpha = 0.16f * t), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
                drawPath(segment, accent.copy(alpha = 0.9f * t), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                val head = measure.getPosition(len * f)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f * t), Color.Transparent), center = head, radius = 14.dp.toPx()), radius = 14.dp.toPx(), center = head)
            }
        }
        if (!lyrics) Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).offset { IntOffset(drift * 3, drift * 2) }.graphicsLayer { alpha = t },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(track.title, style = ArnavTheme.type.label, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = ArnavTheme.type.caption, color = Color.White.copy(alpha = 0.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The next song in the queue; tap to open the queue. Draws nothing at the end of the queue. */
@Composable
private fun UpNextChip(state: PlayerState, on: Color, muted: Color, onQueue: () -> Unit) {
    val next = state.queue.items.getOrNull(state.queue.currentIndex + 1)?.track ?: return
    val motion = ArnavTheme.motion
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).background(on.copy(alpha = 0.06f))
            .clickable(onClickLabel = "Open queue", onClick = onQueue)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(next, contentKey = { it.id }, transitionSpec = {
            (fadeIn(motion.fast()) + slideInHorizontally(motion.offsetSpring()) { (it * 0.06f * motion.travel).toInt() }) togetherWith fadeOut(motion.fast())
        }, modifier = Modifier.weight(1f), label = "upNext") { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.arnav.music.ui.components.Artwork(t.artworkUrl, t.id.value, Modifier.size(34.dp), shape = RoundedCornerShape(8.dp), decodeSize = 96)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text("UP NEXT", style = ArnavTheme.type.overline, color = muted)
                    Text("${t.title} · ${t.artist}", style = ArnavTheme.type.caption, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = muted, modifier = Modifier.padding(start = Space.s).size(18.dp))
    }
}

/** Media volume, kept in step with the hardware buttons. */
@Composable
private fun VolumeRow(on: Color, muted: Color) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(android.media.AudioManager::class.java) } ?: return
    val stream = android.media.AudioManager.STREAM_MUSIC
    val maxLevel = remember { audio.getStreamMaxVolume(stream).coerceAtLeast(1) }
    var level by remember { mutableStateOf(audio.getStreamVolume(stream)) }
    DisposableEffect(audio) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) { level = audio.getStreamVolume(stream) }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val haptics = ArnavTheme.haptics
    var dragging by remember { mutableStateOf(false) }
    val thick by animateFloatAsState(if (dragging) 1f else 0f, ArnavTheme.motion.responsive(), label = "volThick")
    fun set(fraction: Float) {
        val v = (fraction.coerceIn(0f, 1f) * maxLevel).roundToInt()
        if (v != level) {
            // Do Not Disturb can refuse volume changes; the bar then simply stays where it was.
            if (runCatching { audio.setStreamVolume(stream, v, 0) }.isSuccess) {
                level = v
                if (v == 0 || v == maxLevel) haptics.snap()
            }
        }
    }
    val shown by animateFloatAsState(level.toFloat() / maxLevel, ArnavTheme.motion.responsive(), label = "vol")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = muted, modifier = Modifier.size(18.dp))
        Box(
            Modifier.weight(1f).height(28.dp).padding(horizontal = Space.s)
                .semantics {
                    contentDescription = "Volume"
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(level.toFloat(), 0f..maxLevel.toFloat(), steps = (maxLevel - 1).coerceAtLeast(0))
                    setProgress { set(it / maxLevel); true }
                }
                .pointerInput(maxLevel) { detectTapGestures { set(it.x / size.width) } }
                .pointerInput(maxLevel) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> set(change.position.x / size.width) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = 4.dp + 3.dp * thick
            Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(on.copy(alpha = 0.16f)))
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(h).clip(CircleShape).background(on.copy(alpha = 0.85f)))
        }
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = muted, modifier = Modifier.size(18.dp))
    }
}

@Suppress("unused") private fun Float.abs() = abs(this)
@Suppress("unused") private val zero = Offset.Zero
