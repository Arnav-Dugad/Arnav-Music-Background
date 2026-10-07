package com.arnav.music.feature.explore

import com.arnav.music.ui.OnTabReselect
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.model.Moments
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.CardSkeleton
import com.arnav.music.ui.components.MomentCard
import com.arnav.music.ui.components.NoticeBanner
import com.arnav.music.ui.components.SourceBadge
import com.arnav.music.ui.components.TrackCard
import com.arnav.music.ui.components.pressScale
import com.arnav.music.ui.components.rememberInteraction
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

data class Genre(val name: String, val query: String, val a: Long, val b: Long)

val genres = listOf(
    Genre("Pop", "pop hits", 0xFFE85D9E, 0xFF7B2FF7), Genre("Hip-Hop", "hip hop", 0xFFF2994A, 0xFF8B3A0F),
    Genre("Lo-fi", "lofi beats", 0xFF6A82FB, 0xFF2E3A87), Genre("Indie", "indie music", 0xFF56AB91, 0xFF1F4F43),
    Genre("Electronic", "electronic music", 0xFF00C9FF, 0xFF0B4F6C), Genre("R&B", "r&b soul", 0xFFB06AB3, 0xFF4A1E4C),
    Genre("Rock", "rock classics", 0xFFE53935, 0xFF4A0E0E), Genre("Bollywood", "bollywood hits", 0xFFFF9966, 0xFF8E2F23),
    Genre("Jazz", "jazz", 0xFFD4A373, 0xFF5C4033), Genre("Classical", "classical music", 0xFFA8C0FF, 0xFF3F2B96),
    Genre("K-Pop", "kpop", 0xFFFF6FD8, 0xFF3813C2), Genre("Ambient", "ambient music", 0xFF83A4D4, 0xFF1D2B53),
)

@Composable
fun ExploreScreen(vm: ExploreViewModel = koinViewModel()) {
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val c = ArnavTheme.colors
    val trending by vm.trending.collectAsStateWithLifecycle()
    val online by app.online.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    LaunchedEffect(online) { if (online) vm.loadTrending() }

    val grid = rememberLazyGridState()
    OnTabReselect(Routes.EXPLORE) { grid.animateScrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        state = grid,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = chrome.calculateBottomPadding() + Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding().padding(top = Space.l)) {
                Text("Explore", style = ArnavTheme.type.display, color = c.content)
                Spacer(Modifier.height(Space.l))
                SearchEntry(onClick = { nav.go(Routes.search()) }, onPalette = nav.openPalette)
            }
        }
        if (!online) item(span = { GridItemSpan(maxLineSpan) }) {
            NoticeBanner(Icons.Rounded.CloudOff, "Offline — search shows results you've already looked up.")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = Space.l)) {
                SectionHeaderFlush("Moments")
                Spacer(Modifier.height(Space.m))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    items(Moments.all, key = { it.id }) { m -> MomentCard(m, { nav.go(Routes.moment(m.id)) }) }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = Space.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trending in music", style = ArnavTheme.type.title, color = c.content, modifier = Modifier.weight(1f))
                    SourceBadge(youtube = true)
                }
                Spacer(Modifier.height(Space.m))
                when {
                    trending.loading && online -> LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m), userScrollEnabled = false) { items(4) { CardSkeleton() } }
                    trending.tracks.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        itemsIndexed(trending.tracks, key = { _, t -> t.id.value }) { i, t ->
                            TrackCard(t, { app.play(trending.tracks, i) }, onLongClick = { nav.openSheet(SheetRequest.TrackActions(t)) })
                        }
                    }
                    else -> Text(
                        when (trending.error) {
                            MusicError.MissingApiKey -> "Add a YouTube Data API key in Settings → Sources to see what's trending."
                            MusicError.QuotaExhausted -> "Today's YouTube quota is used up. Trending returns tomorrow — everything else keeps working."
                            else -> "Trending isn't available right now."
                        },
                        style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeaderFlush("Browse genres", Modifier.padding(top = Space.l)) }
        items(genres, key = { it.name }) { g -> GenreTile(g) { nav.go(Routes.search(g.query)) } }
    }
}

@Composable
private fun SectionHeaderFlush(title: String, modifier: Modifier = Modifier) {
    Text(title, style = ArnavTheme.type.title, color = ArnavTheme.colors.content, modifier = modifier)
}

@Composable
fun SearchEntry(onClick: () -> Unit, onPalette: () -> Unit) {
    val c = ArnavTheme.colors
    val interaction = rememberInteraction()
    Row(
        Modifier.fillMaxWidth().height(52.dp).pressScale(interaction, 0.985f)
            .glass(GlassMaterial.Thin, RoundedCornerShape(Radius.m))
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Search", onClick = onClick)
            .padding(start = Space.l, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.contentMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text("Songs, artists, playlists", style = ArnavTheme.type.body, color = c.contentSubtle, modifier = Modifier.weight(1f))
        Box(Modifier.size(Space.touch).clip(CircleShape).clickable(onClickLabel = "Command palette", onClick = onPalette), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.KeyboardCommandKey, "Command palette", tint = c.contentSubtle, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun GenreTile(g: Genre, onClick: () -> Unit) {
    val interaction = rememberInteraction()
    Box(
        Modifier.fillMaxWidth().height(96.dp).pressScale(interaction, 0.96f).clip(RoundedCornerShape(Radius.l))
            .background(Brush.linearGradient(listOf(Color(g.a), Color(g.b))))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(Space.l),
    ) {
        Text(g.name, style = ArnavTheme.type.title, color = Color.White, modifier = Modifier.align(Alignment.BottomStart))
    }
}

