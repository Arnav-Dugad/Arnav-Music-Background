package com.arnav.music.feature.explore

import com.arnav.music.ui.sharedMorph
import com.arnav.music.ui.ArtKeys
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.repo.SearchState
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.provider.SearchResults
import com.arnav.music.ui.CollectionKind
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.components.TrackRowSkeleton
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(initialQuery: String, vm: ExploreViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val instant by vm.instant.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val liked by app.liked.collectAsStateWithLifecycle()
    val current by app.playerState.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank() && vm.query.value.isBlank()) vm.query.value = initialQuery
        // Wait for the screen's enter transition so the keyboard rises smoothly instead of racing it.
        if (initialQuery.isBlank()) { kotlinx.coroutines.delay(120); runCatching { focus.requestFocus() } }
    }
    // Scrolling results means you're reading, not typing: get the keyboard out of the way.
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) keyboard?.hide() }
    // Paging: ask for the next page when the user nears the end of the list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 10 && last >= total - 3) vm.loadMore() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.gutter, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back)
            Row(
                Modifier.weight(1f).height(48.dp).glass(GlassMaterial.Thin, RoundedCornerShape(Radius.m)).padding(start = Space.l, end = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query, onValueChange = { vm.query.value = it.take(120) }, singleLine = true,
                    textStyle = ArnavTheme.type.body.copy(color = c.content), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submit(); keyboard?.hide() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner -> if (query.isEmpty()) Text("Songs, artists, playlists", style = ArnavTheme.type.body, color = c.contentSubtle); inner() },
                )
                if (query.isNotEmpty()) ArnavIconButton(Icons.Rounded.Close, "Clear", { vm.query.value = "" }, tint = c.contentSubtle, size = 18.dp)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(SearchFilter.entries) { f ->
                Pill(when (f) { SearchFilter.ALL -> "All"; SearchFilter.TRACKS -> "Songs"; SearchFilter.VIDEOS -> "Videos"; SearchFilter.ARTISTS -> "Artists"; SearchFilter.PLAYLISTS -> "Playlists" }, f == filter, { vm.filter.value = f })
            }
        }
        val playingId = current.current?.id
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    item { Label("Recent searches") }
                    items(recent, key = { "r_$it" }) { r ->
                        Row(Modifier.fillMaxWidth().clickable { vm.query.value = r; vm.submit(); keyboard?.hide() }.padding(start = Space.gutter, end = Space.s, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = c.contentSubtle, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.m))
                            Text(r, style = ArnavTheme.type.body, color = c.content, modifier = Modifier.weight(1f))
                            ArnavIconButton(Icons.Rounded.Close, "Remove $r from history", { vm.forget(r) }, tint = c.contentSubtle, size = 16.dp)
                        }
                    }
                } else item {
                    EmptyState(Icons.Rounded.History, "Your next obsession starts here", "Search songs and artists. Results you've seen are saved, so they load instantly next time.")
                }
                return@LazyColumn
            }
            if (instant.isNotEmpty()) {
                item { Label("In your library") }
                items(instant, key = { "i_" + it.id.value }) { t ->
                    TrackRow(t, { app.play(instant, instant.indexOf(t)) }, playing = t.id == playingId, liked = t.id in liked,
                        onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) }, onMore = { nav.openSheet(SheetRequest.TrackActions(t)) })
                }
            }
            item(key = "results") {
                AnimatedContent(results, contentKey = { it::class }, transitionSpec = { fadeIn(ArnavMotionFastSpec()) togetherWith fadeOut(ArnavMotionFastSpec()) }, label = "results") { s ->
                    when (s) {
                        SearchState.Idle, is SearchState.Instant -> Column { if (instant.isEmpty()) repeat(5) { TrackRowSkeleton() } }
                        is SearchState.Loading -> Column { Label("Searching YouTube…"); repeat(6) { TrackRowSkeleton() } }
                        is SearchState.Failed -> FailedState(s.error, onAsk = { nav.go(Routes.ai(query)) }, onKey = { nav.go(Routes.settings("sources")) })
                        is SearchState.Results -> Box {}
                    }
                }
            }
            (results as? SearchState.Results)?.let { r -> resultsSection(r.results, r.refreshing, playingId, liked) }
        }
    }
}

private fun ArnavMotionFastSpec() = androidx.compose.animation.core.tween<Float>(160)

private fun androidx.compose.foundation.lazy.LazyListScope.resultsSection(
    r: SearchResults, refreshing: Boolean, playingId: com.arnav.music.domain.model.TrackId?, liked: Set<com.arnav.music.domain.model.TrackId>,
) {
    if (r.isEmpty) {
        item { EmptyState(Icons.Rounded.SearchOff, "No matches", "Try a different spelling, or ask Arnav AI to find something with that vibe.") }
        return
    }
    if (r.artists.isNotEmpty()) {
        item(key = "artists") {
            Column {
                Label("Artists")
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    items(r.artists, key = { it.key }) { a ->
                        val nav = LocalNavigator.current
                        Column(Modifier.width(92.dp).clip(RoundedCornerShape(Radius.m)).clickable { nav.go(Routes.artist(a.name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(a.artworkUrl, a.key, Modifier.sharedMorph(ArtKeys.artist(a.name), CircleShape).size(84.dp), CircleShape, decodeSize = 200)
                            Spacer(Modifier.height(Space.s))
                            Text(a.name, style = ArnavTheme.type.label, color = ArnavTheme.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.sharedMorph(ArtKeys.artistName(a.name)))
                        }
                    }
                }
            }
        }
    }
    if (r.tracks.isNotEmpty()) {
        item(key = "songs_label") {
            Row(Modifier.fillMaxWidth().padding(end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Songs") }
                SourceBadge(youtube = true)
            }
        }
        itemsIndexed(r.tracks, key = { _, t -> "t_" + t.id.value }) { i, t ->
            val app = LocalAppViewModel.current
            val nav = LocalNavigator.current
            TrackRow(t, { app.play(r.tracks, i) }, playing = t.id == playingId, liked = t.id in liked,
                onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) }, onMore = { nav.openSheet(SheetRequest.TrackActions(t)) })
        }
    }
    if (r.playlists.isNotEmpty()) {
        item(key = "playlists") {
            val nav = LocalNavigator.current
            Column {
                Label("Playlists on YouTube")
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    items(r.playlists, key = { it.id }) { p ->
                        Column(Modifier.width(148.dp).clip(RoundedCornerShape(Radius.m)).clickable { nav.go(Routes.collection(CollectionKind.YOUTUBE_PLAYLIST, p.id)) }) {
                            Artwork(p.artworkUrl, p.id, Modifier.size(148.dp), decodeSize = 300)
                            Spacer(Modifier.height(Space.s))
                            Text(p.name, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(p.description, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    item(key = "footer") {
        val c = ArnavTheme.colors
        Text(
            buildString {
                if (r.fromCache) append("Saved results · ${Formatters.relative(r.fetchedAt, System.currentTimeMillis())}") else append("Fresh from YouTube")
                if (refreshing) append(" · refreshing")
            },
            style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.fillMaxWidth().padding(Space.gutter), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = ArnavTheme.type.label, color = ArnavTheme.colors.contentMuted, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.m))
}

@Composable
private fun FailedState(error: MusicError, onAsk: () -> Unit, onKey: () -> Unit) {
    when (error) {
        MusicError.Offline -> EmptyState(Icons.Rounded.CloudOff, "You're offline", "We couldn't reach YouTube. Songs in your library and on your device still play.")
        MusicError.QuotaExhausted -> EmptyState(Icons.Rounded.HourglassEmpty, "Search is resting for today", "Arnav Music used today's free YouTube search allowance. Saved results still work, and Arnav AI can build sessions from your library.", action = "Ask Arnav AI", onAction = onAsk)
        MusicError.MissingApiKey -> EmptyState(Icons.Rounded.Key, "Connect YouTube search", "Add a free YouTube Data API key to search YouTube. Your library and on-device music work without it.", action = "Add key", onAction = onKey)
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            EmptyState(Icons.Rounded.SearchOff, "Search hit a snag", "Something went wrong on the way to YouTube. It's not you — try again in a moment.")
            SecondaryButton("Ask Arnav AI instead", onAsk, icon = Icons.Rounded.AutoAwesome)
        }
    }
}

@Suppress("unused") private fun unused(t: Track) = t
