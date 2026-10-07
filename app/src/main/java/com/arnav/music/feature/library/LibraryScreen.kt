package com.arnav.music.feature.library

import com.arnav.music.ui.sharedMorph
import com.arnav.music.ui.sharedArt
import com.arnav.music.ui.ArtKeys
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.settings.LibraryLayout
import com.arnav.music.domain.library.AlbumSummary
import com.arnav.music.feature.album.albumArtKey
import com.arnav.music.domain.model.PlaylistKind
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.CollectionKind
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.TrackCard
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.components.pressScale
import com.arnav.music.ui.components.rememberInteraction
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibraryScreen(vm: LibraryViewModel = koinViewModel(), importVm: PlaylistImportViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val ctx = LocalContext.current
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val tab by vm.tab.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val likedIds by app.liked.collectAsStateWithLifecycle()
    val player by app.playerState.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    var permissionTick by remember { mutableStateOf(0) }
    val hasLocal = remember(permissionTick) { app.hasLocalPermission() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var importChooserOpen by remember { mutableStateOf(false) }
    val matchProgress by importVm.progress.collectAsStateWithLifecycle()
    if (importOpen) YouTubeImportSheet({ importOpen = false })
    if (importChooserOpen) ImportPlaylistsSheet(
        onDismiss = { importChooserOpen = false },
        onYouTube = { importChooserOpen = false; importOpen = true },
        onOpenPlaylist = { id -> importChooserOpen = false; nav.go(Routes.collection(CollectionKind.PLAYLIST, id)) },
        onHistory = { importChooserOpen = false; nav.go(Routes.IMPORTS) },
    )
    // Quiet daily refresh of playlists imported from YouTube: only when Google grants access without
    // asking (consent was given before). Never shows any sign-in or consent UI on its own.
    LaunchedEffect(Unit) {
        if (importVm.silentRefreshDue()) {
            val token = silentYouTubeToken(ctx)
            if (token != null) importVm.refreshQuietly(token) else importVm.markRefreshChecked()
        }
    }
    val grid = layout == LibraryLayout.GRID
    // Albums are always a grid of covers.
    val gridLike = grid || tab == LibraryTab.ALBUMS
    val playingId = player.current?.id

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    com.arnav.music.ui.OnTabReselect(Routes.LIBRARY) { gridState.animateScrollToItem(0) }
    LazyVerticalGrid(
        columns = if (gridLike) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl),
        horizontalArrangement = Arrangement.spacedBy(if (gridLike) Space.m else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (gridLike) Space.l else 0.dp),
    ) {
        full {
            Column(Modifier.statusBarsPadding().padding(top = Space.l)) {
                Row(Modifier.padding(start = Space.gutter, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = ArnavTheme.type.display, color = c.content, modifier = Modifier.weight(1f))
                    ArnavIconButton(
                        when (layout) { LibraryLayout.LIST -> Icons.AutoMirrored.Rounded.ViewList; LibraryLayout.GRID -> Icons.Rounded.GridView; LibraryLayout.COMPACT -> Icons.Rounded.ViewAgenda },
                        "Layout: ${layout.name.lowercase()}. Tap to change",
                        { vm.setLayout(LibraryLayout.entries[(layout.ordinal + 1) % LibraryLayout.entries.size]) },
                    )
                    Box {
                        ArnavIconButton(Icons.AutoMirrored.Rounded.Sort, "Sort: ${sort.label}", { sortOpen = true })
                        DropdownMenu(sortOpen, { sortOpen = false }, containerColor = c.surfaceRaised) {
                            LibrarySort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label, color = c.content) }, onClick = { vm.sort.value = s; sortOpen = false }) }
                        }
                    }
                    ArnavIconButton(Icons.Rounded.Add, "New playlist", { nav.openSheet(SheetRequest.CreatePlaylist(emptyList())) })
                    // Import, import history and the duplicate finder share one menu so the title keeps its room.
                    Box {
                        ArnavIconButton(Icons.Rounded.MoreVert, "Import and library tools", { moreOpen = true })
                        DropdownMenu(moreOpen, { moreOpen = false }, containerColor = c.surfaceRaised) {
                            DropdownMenuItem(
                                text = { Text("Import playlists", color = c.content) },
                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, null, tint = c.contentMuted) },
                                onClick = { moreOpen = false; importChooserOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Find duplicates", color = c.content) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = c.contentMuted) },
                                onClick = { moreOpen = false; nav.go(Routes.DUPLICATES) },
                            )
                            DropdownMenuItem(
                                text = { Text("Import history", color = c.content) },
                                leadingIcon = { Icon(Icons.Rounded.History, null, tint = c.contentMuted) },
                                onClick = { moreOpen = false; nav.go(Routes.IMPORTS) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.m))
                Row(
                    Modifier.padding(horizontal = Space.gutter).fillMaxWidth().height(44.dp).glass(GlassMaterial.Thin, RoundedCornerShape(Radius.m)).padding(horizontal = Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FilterList, null, tint = c.contentSubtle, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.m))
                    BasicTextField(
                        filter, { vm.filter.value = it.take(60) }, singleLine = true, textStyle = ArnavTheme.type.body.copy(color = c.content),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.weight(1f),
                        decorationBox = { inner -> if (filter.isEmpty()) Text("Filter your library", style = ArnavTheme.type.body, color = c.contentSubtle); inner() },
                    )
                }
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(LibraryTab.entries) { t -> Pill(t.label, t == tab, { vm.tab.value = t }) }
                }
            }
        }
        if (matchProgress.open > 0) full {
            ImportMatchBanner(
                matchProgress,
                onMatchMore = { importVm.matchMoreNow(); app.message("Matching more songs now. Stops early if YouTube's daily limit gets close.") },
                modifier = Modifier.padding(bottom = Space.m),
            )
        }

        when (tab) {
            LibraryTab.ALL -> {
                full {
                    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            QuickTile("Liked", "${liked.size} songs", Icons.Rounded.Favorite, Modifier.weight(1f), listOf(Color(0xFF8C7CFF), Color(0xFF4B3BBF))) { nav.go(Routes.collection(CollectionKind.LIKED)) }
                            QuickTile("On device", if (hasLocal) "${local.size} songs" else "Allow access", Icons.Rounded.PhoneAndroid, Modifier.weight(1f), listOf(Color(0xFF2E9C8A), Color(0xFF145247))) {
                                if (hasLocal) nav.go(Routes.collection(CollectionKind.LOCAL)) else permissionLauncher.launch(app.localPermission)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            QuickTile("History", "Everything you played", Icons.Rounded.History, Modifier.weight(1f), listOf(Color(0xFFE07A5F), Color(0xFF8A3B26))) { nav.go(Routes.collection(CollectionKind.HISTORY)) }
                            QuickTile("Shuffle all", "Liked + device", Icons.Rounded.Shuffle, Modifier.weight(1f), listOf(Color(0xFF5B8DEF), Color(0xFF263E78))) { app.play(liked + local, 0, shuffle = true) }
                        }
                    }
                }
                if (mixes.isNotEmpty()) {
                    full { SectionLabel("Smart playlists") }
                    full {
                        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            items(mixes, key = { it.kind.name }) { m ->
                                com.arnav.music.ui.components.MixCard(m.kind.title, m.kind.blurb, m.artwork, m.kind.name, { nav.go(Routes.collection(CollectionKind.SMART, m.kind.name)) }, width = 136.dp, sharedKey = ArtKeys.smart(m.kind.name))
                            }
                        }
                    }
                }
                full { SectionLabel("Playlists") }
                playlistItems(playlists, grid, layout)
                if (playlists.isEmpty()) full {
                    EmptyState(Icons.Rounded.LibraryMusic, "No playlists yet", "Create one, save any queue from the player, or bring your playlists over from YouTube, Spotify or a CSV file.", action = "Import playlists", onAction = { importChooserOpen = true })
                }
            }
            LibraryTab.PLAYLISTS -> {
                playlistItems(playlists, grid, layout)
                if (playlists.isEmpty()) full { EmptyState(Icons.Rounded.LibraryMusic, "No playlists yet", "Arnav playlists sync to your account when you're signed in.") }
            }
            LibraryTab.LIKED -> {
                if (liked.isEmpty()) full { EmptyState(Icons.Rounded.Favorite, "Your favorites will appear here", "Tap the heart on anything you love — or swipe left on a song.") }
                trackItems(liked, grid, layout, playingId, likedIds)
            }
            LibraryTab.ARTISTS -> {
                if (artists.isEmpty()) full { EmptyState(Icons.Rounded.LibraryMusic, "No artists yet", "Artists from your liked songs and device appear here.") }
                items(artists, key = { it.key }, span = { if (grid) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { nav.go(Routes.artist(a.name)) }.padding(horizontal = Space.gutter, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(a.artworkUrl, a.key, Modifier.sharedMorph(ArtKeys.artist(a.name), CircleShape).size(52.dp), CircleShape, decodeSize = 160)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.sharedMorph(ArtKeys.artistName(a.name)))
                            Text("${a.tracks} in your library", style = ArnavTheme.type.caption, color = c.contentMuted)
                        }
                    }
                }
            }
            LibraryTab.ALBUMS -> {
                if (!hasLocal) full {
                    EmptyState(Icons.Rounded.Album, "Albums from this phone", "Allow access to music on this device to browse it album by album.",
                        action = "Allow access", onAction = { permissionLauncher.launch(app.localPermission) })
                } else if (albums.isEmpty()) full {
                    EmptyState(Icons.Rounded.Album, if (filter.isBlank()) "No albums yet" else "No matching albums", "Songs on this phone that carry album tags are grouped here, album by album.")
                }
                albumItems(albums)
            }
            LibraryTab.LOCAL -> {
                if (!hasLocal) full {
                    EmptyState(Icons.Rounded.PhoneAndroid, "Bring your own library", "Play music files on this phone with full background playback, lock-screen and Bluetooth controls. Files never leave your device.",
                        action = "Allow access", onAction = { permissionLauncher.launch(app.localPermission) })
                } else if (local.isEmpty()) full {
                    EmptyState(Icons.Rounded.PhoneAndroid, "No music files found", "Add MP3, FLAC, M4A, OGG or WAV files to your phone and they'll appear here automatically.")
                }
                trackItems(local, grid, layout, playingId, likedIds)
            }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.trackItems(tracks: List<Track>, grid: Boolean, layout: LibraryLayout, playingId: com.arnav.music.domain.model.TrackId?, liked: Set<com.arnav.music.domain.model.TrackId>) {
    itemsIndexed(tracks, key = { _, t -> t.id.value }, span = { _, _ -> if (grid) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { i, t ->
        val app = LocalAppViewModel.current
        val nav = LocalNavigator.current
        if (grid) {
            Box(Modifier.padding(horizontal = Space.s)) {
                TrackCard(t, { app.play(tracks, i) }, width = 150.dp, onLongClick = { nav.openSheet(SheetRequest.TrackActions(t)) })
            }
        } else {
            TrackRow(t, { app.play(tracks, i) }, playing = t.id == playingId, liked = t.id in liked, compact = layout == LibraryLayout.COMPACT,
                onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) }, onMore = { nav.openSheet(SheetRequest.TrackActions(t)) })
        }
    }
}

private fun LazyGridScope.albumItems(albums: List<AlbumSummary>) {
    items(albums, key = { "album_${it.id}" }) { a ->
        val nav = LocalNavigator.current
        val c = ArnavTheme.colors
        Column(Modifier.padding(horizontal = Space.s).clip(RoundedCornerShape(Radius.m)).clickable { nav.go(Routes.album(a.id)) }) {
            Artwork(a.artworkUrl, a.id, Modifier.sharedArt(albumArtKey(a.id)).fillMaxWidth().aspectRatio(1f), RoundedCornerShape(Radius.m), contentDescription = a.title, decodeSize = 720)
            Spacer(Modifier.height(Space.s))
            Text(a.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(a.artist, a.year?.toString(), "${a.trackCount} ${if (a.trackCount == 1) "song" else "songs"}").joinToString(" · "),
                style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun LazyGridScope.playlistItems(playlists: List<com.arnav.music.domain.model.Playlist>, grid: Boolean, layout: LibraryLayout) {
    items(playlists, key = { it.id }, span = { if (grid) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { p ->
        val nav = LocalNavigator.current
        val c = ArnavTheme.colors
        val target = if (p.kind == PlaylistKind.YOUTUBE) CollectionKind.YOUTUBE_PLAYLIST else CollectionKind.PLAYLIST
        val artKey = ArtKeys.collection(target, p.id)
        val open = { nav.go(Routes.collection(target, p.id)) }
        if (grid) {
            Column(Modifier.padding(horizontal = Space.s).clip(RoundedCornerShape(Radius.m)).clickable(onClick = open)) {
                Artwork(p.artworkUrl, p.id, Modifier.sharedArt(artKey).fillMaxWidth().aspectRatio(1f), decodeSize = 300)
                Spacer(Modifier.height(Space.s))
                Text(p.name, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel(p.kind) + " · ${p.trackCount}", style = ArnavTheme.type.caption, color = c.contentMuted)
            }
        } else {
            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = Space.gutter, vertical = if (layout == LibraryLayout.COMPACT) 6.dp else Space.s), verticalAlignment = Alignment.CenterVertically) {
                Artwork(p.artworkUrl, p.id, Modifier.sharedArt(artKey).size(if (layout == LibraryLayout.COMPACT) 44.dp else 56.dp), RoundedCornerShape(Radius.s), decodeSize = 160)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.pinned) { Icon(Icons.Rounded.PushPin, "Pinned", tint = c.accent, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                        Text(p.name, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(kindLabel(p.kind) + " · ${p.trackCount} tracks", style = ArnavTheme.type.caption, color = c.contentMuted)
                }
            }
        }
    }
}

fun kindLabel(kind: PlaylistKind) = when (kind) {
    PlaylistKind.ARNAV -> "Arnav playlist"
    PlaylistKind.YOUTUBE -> "YouTube playlist"
    PlaylistKind.LOCAL -> "On-device playlist"
    PlaylistKind.SMART -> "Smart playlist"
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = ArnavTheme.type.title, color = ArnavTheme.colors.content, modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Space.s))
}

@Composable
private fun QuickTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, gradient: List<Color>, onClick: () -> Unit) {
    val interaction = rememberInteraction()
    Row(
        modifier.height(64.dp).pressScale(interaction, 0.97f).clip(RoundedCornerShape(Radius.m)).background(ArnavTheme.colors.surfaceRaised)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).background(Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.padding(horizontal = Space.m)) {
            Text(title, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content, maxLines = 1)
            Text(subtitle, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted, maxLines = 1)
        }
    }
}

