package com.arnav.music.feature.album

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.color.SurfaceMode
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.library.AlbumSummary
import com.arnav.music.domain.library.Albums
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.artwork.rememberArtworkPalette
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.components.TrackRowSkeleton
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.ArtKeys
import com.arnav.music.ui.ArtOrigin
import com.arnav.music.ui.artOrigin
import com.arnav.music.ui.sharedArt
import com.arnav.music.ui.sharedMorph
import com.arnav.music.ui.theme.AccentScope
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

/** Shared-element key for an album cover (library grid → album hero). */
fun albumArtKey(albumId: String) = ArtKeys.album(albumId)

/**
 * An album of on-device songs: a collapsing hero (cover, title, album artist, year · songs ·
 * length), Play/Shuffle, then the songs under "Disc 1 / Disc 2" headers with their track numbers.
 *
 * [from] is the section of the previous screen whose card opened this page (see ArtRoutes): the
 * card's container, cover and title then morph into the page, the hero and its title.
 */
@Composable
fun AlbumScreen(albumId: String, from: String? = null, vm: AlbumViewModel = koinViewModel()) {
    // Fill synchronously when possible, so the hero exists on the first frame of the morph.
    remember(albumId) { vm.prime(albumId); albumId }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val app = LocalAppViewModel.current
    val nav = LocalNavigator.current
    val chrome = LocalChromePadding.current
    val player by app.playerState.collectAsStateWithLifecycle()
    val base = ArnavTheme.colors
    LaunchedEffect(albumId) { vm.load(albumId) }
    val album = ui.album
    val palette = rememberArtworkPalette(album?.artworkUrl, if (base.isOled) SurfaceMode.OLED else if (base.isDark) SurfaceMode.DARK else SurfaceMode.LIGHT)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val heroPx = with(density) { 300.dp.toPx() }
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroPx } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, ArnavTheme.motion.fast(), label = "albumBar")
    val playingId = player.current?.id
    // Plays fly the hero cover into Now Playing while it is still (mostly) on screen.
    val heroArt = remember { ArtOrigin() }
    val play: (Int, Boolean) -> Unit = { index, shuffle ->
        app.play(ui.tracks, index, shuffle = shuffle)
        val coverShown = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < heroPx * 0.6f
        if (coverShown) heroArt.rect()?.let(nav.flyFrom)
    }
    // Opened from a card: the page itself grows out of that card's container (and shrinks back).
    val cardKey = from?.let { ArtKeys.albumCard(albumId, it) }

    AccentScope(if (album?.artworkUrl == null) null else Color(palette.accent)) {
        val c = ArnavTheme.colors
        Box(
            Modifier.sharedMorph(cardKey, RoundedCornerShape(Radius.m)).fillMaxSize()
                .then(if (cardKey != null) Modifier.background(c.background) else Modifier),
        ) {
            Box(
                Modifier.fillMaxWidth().height(460.dp)
                    .graphicsLayer { translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset * 0.4f else -460.dp.toPx() }
                    .background(Brush.verticalGradient(listOf(Color(palette.backdrop), c.background))),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
                item(key = "hero") {
                    Column {
                        // Room for the back button bar drawn on top.
                        Spacer(Modifier.statusBarsPadding().height(Space.touch))
                        if (album != null) Hero(album, albumId, from, heroArt, listState, heroPx, onArtist = { nav.go(Routes.artist(it)) }, onPlay = { shuffle -> play(0, shuffle) })
                    }
                }
                when {
                    ui.loading -> items(8) { TrackRowSkeleton() }
                    album == null -> item(key = "missing") {
                        EmptyState(Icons.Rounded.Album, "Album not found", "It may have been moved or deleted from this phone.")
                    }
                    else -> {
                        val multiDisc = ui.discs.size > 1
                        val all = ui.tracks
                        ui.discs.forEach { disc ->
                            if (multiDisc) item(key = "disc_${disc.number}") {
                                Text(
                                    "Disc ${disc.number}", style = ArnavTheme.type.label, color = c.contentMuted,
                                    modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.xs),
                                )
                            }
                            items(disc.tracks, key = { t -> t.id.value }) { t ->
                                AlbumTrackRow(
                                    track = t,
                                    albumArtist = album.artist,
                                    playing = t.id == playingId,
                                    onClick = { play(all.indexOf(t).coerceAtLeast(0), false) },
                                    onMore = { nav.openSheet(SheetRequest.TrackActions(t)) },
                                )
                            }
                        }
                        item(key = "footer") {
                            val year = album.year
                            Text(
                                listOfNotNull(year?.toString(), "${album.trackCount} ${if (album.trackCount == 1) "song" else "songs"}", album.totalMs.takeIf { it > 0 }?.let(Formatters::longDuration))
                                    .joinToString(" · "),
                                style = ArnavTheme.type.caption, color = c.contentSubtle,
                                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xl),
                            )
                        }
                    }
                }
            }
            // Collapsed bar: back stays put; glass and the title fade in once the hero has scrolled away.
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = barAlpha }.glass(GlassMaterial.Thin, RectangleShape))
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back)
                    Text(
                        album?.title.orEmpty(), style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = barAlpha },
                    )
                    if (album != null) {
                        ArnavIconButton(
                            Icons.Rounded.Shuffle, "Shuffle ${album.title}", { app.play(ui.tracks, 0, shuffle = true) },
                            modifier = Modifier.graphicsLayer { alpha = barAlpha }, tint = c.content, enabled = collapsed,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    album: AlbumSummary, albumId: String, from: String?, art: ArtOrigin, listState: LazyListState, heroPx: Float,
    onArtist: (String) -> Unit, onPlay: (Boolean) -> Unit,
) {
    val c = ArnavTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(
            album.artworkUrl, album.id,
            // Above the page's container morph while both are in flight.
            Modifier.sharedArt(ArtKeys.album(albumId, from), zIndex = 1f).size(240.dp)
                .graphicsLayer {
                    // The cover sinks, shrinks and fades as it scrolls under the bar (read here, not in composition).
                    val first = listState.firstVisibleItemIndex == 0
                    val offset = if (first) listState.firstVisibleItemScrollOffset.toFloat() else heroPx
                    val progress = (offset / heroPx).coerceIn(0f, 1f)
                    val s = 1f - 0.25f * progress
                    scaleX = s; scaleY = s
                    alpha = 1f - 0.8f * progress
                    translationY = offset * 0.35f
                    shadowElevation = 24.dp.toPx()
                    shape = RoundedCornerShape(Radius.heroArtwork)
                    clip = true
                }
                .artOrigin(art),
            RoundedCornerShape(Radius.heroArtwork),
            contentDescription = "Cover of ${album.title}",
            decodeSize = 720,
        )
        Spacer(Modifier.height(Space.xl))
        Text("ALBUM", style = ArnavTheme.type.overline, color = c.contentMuted)
        Spacer(Modifier.height(Space.xs))
        Text(
            album.title, style = ArnavTheme.type.display, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.sharedMorph(ArtKeys.albumTitle(albumId, from), zIndex = 1f),
        )
        Spacer(Modifier.height(Space.xs))
        val various = album.artist == Albums.VARIOUS
        Text(
            album.artist, style = ArnavTheme.type.titleSmall, color = if (various) c.contentMuted else c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(Radius.s))
                .clickable(enabled = !various, role = Role.Button, onClickLabel = "Open ${album.artist}") { onArtist(album.artist) }
                .padding(horizontal = Space.s, vertical = Space.xxs),
        )
        Spacer(Modifier.height(Space.xxs))
        Text(
            listOfNotNull(album.year?.toString(), "${album.trackCount} ${if (album.trackCount == 1) "song" else "songs"}", album.totalMs.takeIf { it > 0 }?.let(Formatters::longDuration))
                .joinToString(" · "),
            style = ArnavTheme.type.caption, color = c.contentSubtle,
        )
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            PrimaryButton("Play", { onPlay(false) }, enabled = album.tracks.isNotEmpty(), icon = Icons.Rounded.PlayArrow)
            SecondaryButton("Shuffle", { onPlay(true) }, icon = Icons.Rounded.Shuffle, enabled = album.tracks.size > 1)
        }
        Spacer(Modifier.height(Space.m))
    }
}

@Composable
private fun AlbumTrackRow(track: Track, albumArtist: String, playing: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val c = ArnavTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(start = Space.gutter, end = Space.xs, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (playing) Icon(Icons.Rounded.GraphicEq, "Now playing", tint = c.accent, modifier = Modifier.size(18.dp))
            else Text(track.trackNumber?.toString() ?: "–", style = ArnavTheme.type.numeric, color = c.contentSubtle)
        }
        Column(Modifier.weight(1f).padding(vertical = Space.xs)) {
            Text(track.title, style = ArnavTheme.type.titleSmall, color = if (playing) c.accent else c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Only songs by someone other than the album artist name them (features, compilations).
            if (ArtistKey.of(track.artist) != ArtistKey.of(albumArtist)) {
                Text(track.artist, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        track.durationMs?.let { Text(Formatters.duration(it), style = ArnavTheme.type.numeric, color = c.contentSubtle, modifier = Modifier.padding(start = Space.s)) }
        ArnavIconButton(Icons.Rounded.MoreVert, "More options for ${track.title}", onMore, tint = c.contentSubtle, size = 20.dp)
    }
}
