package com.arnav.music.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space

/** The app-wide shared-transition scope (set around the NavHost). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's enter/exit scope, so artwork can travel between screens. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Stable shared-element keys for artwork that should carry from a card into its detail page.
 *
 * Keys are per entity (album id, artist name…). When the same entity can appear in more than one
 * section of a single screen, give each section its own [from] origin (see [ArtOrigins]) and open
 * the detail page with [ArtRoutes] so it reads the same origin back: both ends then agree on one
 * key, and two copies on one screen never share a key. No origin = the plain key.
 */
object ArtKeys {
    fun playlist(id: String) = "art-playlist-$id"
    fun smart(kind: String) = "art-smart-$kind"
    fun artist(name: String, from: String? = null) = "art-artist-${name.lowercase()}" + suffix(from)
    /** An artist's name label (search result → artist page title). */
    fun artistName(name: String, from: String? = null) = "name-artist-${name.lowercase()}" + suffix(from)
    fun collection(kind: CollectionKind, id: String) = when (kind) {
        CollectionKind.SMART -> smart(id)
        else -> "art-${kind.name.lowercase()}-$id"
    }
    /** An on-device album's cover (cards → album hero). */
    fun album(id: String, from: String? = null) = "art-album-$id" + suffix(from)
    /** An album's title text (card caption → album hero title). */
    fun albumTitle(id: String, from: String? = null) = "title-album-$id" + suffix(from)
    /** An album card's whole container (card → album page). */
    fun albumCard(id: String, from: String? = null) = "card-album-$id" + suffix(from)

    private fun suffix(from: String?) = if (from.isNullOrBlank()) "" else "@$from"
}

/** Section names used as shared-key origins. */
object ArtOrigins {
    /** The "Albums" row on an artist page. */
    const val ARTIST_ALBUMS = "artist-albums"
}

/**
 * Detail routes that carry the section the user came from (`?from=`), so the destination can build
 * the same section-scoped keys as the card that was tapped. Without an origin they equal [Routes].
 */
object ArtRoutes {
    const val FROM = "from"
    fun album(albumId: String, from: String? = null) = Routes.album(albumId) + query(from)
    fun artist(name: String, from: String? = null) = Routes.artist(name) + query(from)
    private fun query(from: String?) = if (from.isNullOrBlank()) "" else "?$FROM=" + Uri.encode(from)
}

/**
 * Marks artwork as the same element across screens: tapping a playlist/mix card makes its cover
 * glide and grow into the page's hero (and back on return). No-op without a transition scope or
 * under reduced motion. [zIndex] lifts it above a container morph it sits in.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArt(key: String?, zIndex: Float = 0f): Modifier {
    if (key == null || ArnavTheme.motion.reduced) return this
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) { this@sharedArt.sharedElement(rememberSharedContentState(key), anim, zIndexInOverlay = zIndex) }
}

/**
 * Morphs bounds between two different-looking things that stand for the same entity (a card's
 * container → its page, a caption → a page title, an avatar → an artist header): the box glides
 * between them while one cross-fades into the other. [shape] clips the morph in flight.
 * No-op without a transition scope or under reduced motion (the plain page fade remains).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMorph(key: String?, shape: Shape? = null, zIndex: Float = 0f): Modifier {
    if (key == null || ArnavTheme.motion.reduced) return this
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        val state = rememberSharedContentState(key)
        if (shape != null) {
            this@sharedMorph.sharedBounds(
                state, anim,
                enter = fadeIn(tween(260)), exit = fadeOut(tween(200)),
                zIndexInOverlay = zIndex,
                clipInOverlayDuringTransition = OverlayClip(shape),
            )
        } else {
            this@sharedMorph.sharedBounds(state, anim, enter = fadeIn(tween(260)), exit = fadeOut(tween(200)), zIndexInOverlay = zIndex)
        }
    }
}

/**
 * Remembers where a cover sits so a play can fly it into Now Playing ([Navigator.flyFrom]).
 * Bounds are read at tap time, unclipped and with the cover's own scale/translation applied, so a
 * parallax hero reports the rect that is actually on screen.
 */
class ArtOrigin {
    internal var coords: LayoutCoordinates? = null

    /** The tracked node's current bounds in root coordinates (px), or null when it isn't laid out. */
    fun rect(): Rect? {
        val c = coords?.takeIf { it.isAttached } ?: return null
        return runCatching { c.findRootCoordinates().localBoundingBoxOf(c, clipBounds = false) }.getOrNull()
            ?.takeIf { it.width > 1f && it.height > 1f }
    }

    /**
     * The 52dp cover inside a [com.arnav.music.ui.components.TrackRow] whose outer box is tracked:
     * it sits after the start gutter, centred vertically.
     */
    fun trackRowArtwork(density: Density, direction: LayoutDirection): Rect? {
        val row = rect() ?: return null
        return with(density) {
            val s = 52.dp.toPx()
            val gutter = Space.gutter.toPx()
            val left = if (direction == LayoutDirection.Rtl) row.right - gutter - s else row.left + gutter
            val top = row.center.y - s / 2f
            Rect(left, top, left + s, top + s)
        }
    }
}

/** Tracks this node's position for [ArtOrigin]. */
fun Modifier.artOrigin(origin: ArtOrigin): Modifier = onGloballyPositioned { origin.coords = it }
