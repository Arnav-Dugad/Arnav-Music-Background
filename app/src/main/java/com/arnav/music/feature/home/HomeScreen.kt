package com.arnav.music.feature.home

import com.arnav.music.ui.ArtKeys
import com.arnav.music.ui.OnTabReselect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.repo.HomeSection
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.CollectionKind
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.CardSkeleton
import com.arnav.music.ui.components.MixCard
import com.arnav.music.ui.components.MomentCard
import com.arnav.music.ui.components.NoticeBanner
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.SectionHeader
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

@Composable
fun HomeScreen(vm: HomeViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalAppViewModel.current
    val nav = LocalNavigator.current
    val online by app.online.collectAsStateWithLifecycle()
    val user by app.user.collectAsStateWithLifecycle()
    val c = ArnavTheme.colors
    val chrome = LocalChromePadding.current

    val list = rememberLazyListState()
    OnTabReselect(Routes.HOME) { list.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item(key = "header") {
            Reveal(0) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter, end = Space.s, top = Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.greeting + (user?.displayName?.substringBefore(' ')?.let { ", $it" } ?: ""), style = ArnavTheme.type.display, color = c.content,
                            modifier = Modifier.semantics { heading() })
                    }
                    ArnavIconButton(Icons.Rounded.LibraryMusic, "Customize Home", { nav.go(Routes.settings("studio")) })
                    ArnavIconButton(Icons.Rounded.KeyboardCommandKey, "Command palette", nav.openPalette)
                    Box(
                        Modifier.size(Space.touch).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Profile") { nav.go(Routes.PROFILE) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val photo = user?.photoUrl
                        if (photo != null) Artwork(photo, "me", Modifier.size(34.dp), CircleShape, decodeSize = 96)
                        else Box(Modifier.size(34.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Person, null, tint = c.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        item(key = "search") {
            Reveal(1) { SearchBar { nav.go(Routes.search()) } }
        }
        item(key = "update") {
            com.arnav.music.ui.update.UpdateCard(Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
        }
        if (!online) item(key = "offline") {
            NoticeBanner(Icons.Rounded.CloudOff, "You're offline. Your library, history and on-device music still work.", Modifier.padding(top = Space.m))
        }
        if (state.loading && state.sections.isEmpty()) {
            item(key = "skeleton") {
                Column(Modifier.padding(top = Space.xl)) {
                    Box(Modifier.padding(horizontal = Space.gutter).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(Radius.xl)).then(Modifier.background(c.surfaceRaised)))
                    Spacer(Modifier.height(Space.xl))
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m), userScrollEnabled = false) {
                        items(4) { CardSkeleton() }
                    }
                }
            }
        }
        itemsIndexed(state.sections, key = { _, s -> s.key }) { i, section ->
            Reveal(i + 2) {
                Box(Modifier.padding(top = Space.xxl)) { Section(section, app::play, { nav.openSheet(SheetRequest.TrackActions(it)) }) }
            }
        }
    }
}

@Composable
private fun Section(section: HomeSection, play: (List<Track>, Int, Boolean) -> Unit, more: (Track) -> Unit) {
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    when (section) {
        is HomeSection.ContinueListening -> Column {
            SectionHeader("Continue listening", action = "History", onAction = { nav.go(Routes.collection(CollectionKind.HISTORY)) })
            Spacer(Modifier.height(Space.m))
            ResumeCard(section.tracks.first(), { play(section.tracks, 0, false) }, { more(section.tracks.first()) })
            if (section.tracks.size > 1) {
                Spacer(Modifier.height(Space.m))
                LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    itemsIndexed(section.tracks.drop(1), key = { _, t -> t.id.value }) { i, t ->
                        TrackCard(t, { play(section.tracks, i + 1, false) }, width = 120.dp, onLongClick = { more(t) })
                    }
                }
            }
        }
        is HomeSection.MomentsRow -> Column {
            SectionHeader("Moments", subtitle = "Step into a feeling")
            Spacer(Modifier.height(Space.m))
            MomentCard(section.featured, { nav.go(Routes.moment(section.featured.id)) }, Modifier.padding(horizontal = Space.gutter), large = true)
            Spacer(Modifier.height(Space.m))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                items(section.moments.drop(1), key = { it.id }) { m -> MomentCard(m, { nav.go(Routes.moment(m.id)) }) }
            }
        }
        is HomeSection.MadeForYou -> Column {
            SectionHeader("Made for you", subtitle = "Built on your device from what you play")
            Spacer(Modifier.height(Space.m))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                items(section.mixes, key = { it.kind.name }) { mix ->
                    MixCard(mix.kind.title, mix.kind.blurb, mix.artwork, mix.kind.name, { nav.go(Routes.collection(CollectionKind.SMART, mix.kind.name)) }, sharedKey = ArtKeys.smart(mix.kind.name))
                }
            }
        }
        is HomeSection.TrackShelf -> Column {
            SectionHeader(section.title, subtitle = section.subtitle)
            Spacer(Modifier.height(Space.m))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                itemsIndexed(section.tracks, key = { _, t -> t.id.value }) { i, t ->
                    // Artist on the first line, the honest reason ("Often follows …") on the second.
                    val why = section.captions[t.id]
                    TrackCard(t, { play(section.tracks, i, false) }, caption = why?.let { "${t.artist}\n$it" }, onLongClick = { more(t) })
                }
            }
        }
        is HomeSection.DailyMixes -> Column {
            SectionHeader("Your daily mixes", subtitle = "Favourites grouped by what you play together, plus a few new songs")
            Spacer(Modifier.height(Space.m))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                items(section.mixes, key = { it.id }) { mix ->
                    val tracks = mix.tracks.map { it.track }
                    MixCard(mix.title, mix.subtitle, tracks.mapNotNull { it.artworkUrl }.distinct().take(4), mix.id, { play(tracks, 0, false) })
                }
            }
        }
        is HomeSection.TimeMachine -> TimeMachineCard(section) { play(section.tracks, 0, false) }
        is HomeSection.StartHere -> Column {
            SectionHeader("Start here", subtitle = "Pick a feeling — Arnav AI builds the session")
            Spacer(Modifier.height(Space.m))
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(section.moods, key = { it.name }) { mood ->
                    Pill(mood.label, false, { nav.go(Routes.ai("${mood.label} music")) })
                }
            }
            Spacer(Modifier.height(Space.m))
            Text(
                "Your home gets more personal as you listen. Everything is computed on this device.",
                style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(horizontal = Space.gutter),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(track: Track, onPlay: () -> Unit, onMore: () -> Unit) {
    val c = ArnavTheme.colors
    val interaction = rememberInteraction()
    Row(
        Modifier.padding(horizontal = Space.gutter).fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .glass(GlassMaterial.Regular, RoundedCornerShape(Radius.l))
            .combinedClickable(interaction, indication = null, onClick = onPlay, onLongClick = onMore)
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, track.id.value, Modifier.size(72.dp), RoundedCornerShape(Radius.m))
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f)) {
            Text("PICK UP WHERE YOU LEFT OFF", style = ArnavTheme.type.overline, color = c.accent)
            Spacer(Modifier.height(2.dp))
            Text(track.title, style = ArnavTheme.type.title, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.content), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = c.background)
        }
    }
}

@Composable
private fun TimeMachineCard(section: HomeSection.TimeMachine, onPlay: () -> Unit) {
    val c = ArnavTheme.colors
    val interaction = rememberInteraction()
    Column(
        Modifier.padding(horizontal = Space.gutter).fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clip(RoundedCornerShape(Radius.xl))
            .background(c.surfaceRaised)
            .clickable(interaction, indication = null, onClick = onPlay)
            .padding(Space.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.s))
            Text("TIME MACHINE", style = ArnavTheme.type.overline, color = c.accent)
        }
        Spacer(Modifier.height(Space.s))
        Text(section.insight.headline, style = ArnavTheme.type.headline, color = c.content)
        Spacer(Modifier.height(Space.l))
        Row {
            section.tracks.take(5).forEachIndexed { i, t ->
                Artwork(
                    t.artworkUrl, t.id.value,
                    Modifier.size(64.dp).graphicsLayer { translationX = -i * 18.dp.toPx() }.clip(RoundedCornerShape(Radius.s)),
                    RoundedCornerShape(Radius.s), decodeSize = 160,
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        Text("Based on your Arnav Music listening activity.", style = ArnavTheme.type.caption, color = c.contentSubtle)
    }
}

/** A plain search field (songs, artists, playlists). It opens Search with the keyboard up. */
@Composable
private fun SearchBar(onClick: () -> Unit) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    val interaction = rememberInteraction()
    Row(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.m).fillMaxWidth().height(52.dp)
            .pressScale(interaction, 0.98f)
            .glass(GlassMaterial.Thin, CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Search") { haptics.press(); onClick() }
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.contentMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text("Songs, artists, playlists", style = ArnavTheme.type.body, color = c.contentSubtle, maxLines = 1)
    }
}

/** Staggered launch reveal: each block rises 16dp and fades in, 40ms apart. Once per process. */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val motion = ArnavTheme.motion
    val alreadyRevealed = remember { RevealMemory.done }
    val a = remember { Animatable(if (alreadyRevealed || motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (a.value < 1f) {
            kotlinx.coroutines.delay(40L * index.coerceAtMost(8))
            a.animateTo(1f, motion.expressive())
            RevealMemory.done = true
        }
    }
    Box(Modifier.graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 16.dp.toPx() * motion.travel }) { content() }
}

private object RevealMemory { var done = false }

@Suppress("unused") private val transparent = Color.Transparent
