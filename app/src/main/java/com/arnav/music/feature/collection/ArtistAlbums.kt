package com.arnav.music.feature.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arnav.music.domain.library.AlbumSummary
import com.arnav.music.ui.ArtKeys
import com.arnav.music.ui.ArtOrigins
import com.arnav.music.ui.ArtRoutes
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.sharedArt
import com.arnav.music.ui.sharedMorph
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space

private val CardWidth = 140.dp

/**
 * "Albums" on an artist page: the artist's on-device albums as cover cards. Tapping one morphs the
 * card into the album page (container → page, cover → hero cover, title → hero title); Back
 * reverses it. Keys are scoped to this row ([ArtOrigins.ARTIST_ALBUMS]) and the album page reads
 * the same origin from its route, so the cover can appear elsewhere on screen without clashing.
 */
@Composable
fun ArtistAlbumsSection(albums: List<AlbumSummary>, modifier: Modifier = Modifier) {
    if (albums.isEmpty()) return
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    Column(modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s)) {
        Text("Albums", style = ArnavTheme.type.title, color = c.content, modifier = Modifier.padding(horizontal = Space.gutter))
        Spacer(Modifier.height(Space.s))
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            items(albums, key = { it.id }) { a ->
                AlbumCard(a) { nav.go(ArtRoutes.album(a.id, ArtOrigins.ARTIST_ALBUMS)) }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumSummary, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    val from = ArtOrigins.ARTIST_ALBUMS
    val shape = RoundedCornerShape(Radius.m)
    Column(
        Modifier.width(CardWidth)
            .sharedMorph(ArtKeys.albumCard(album.id, from), shape)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "Open ${album.title}", onClick = onClick),
    ) {
        Artwork(
            album.artworkUrl, album.id,
            // Same decode size as the album hero, so the page finds this cover in the memory cache
            // and the morph lands on a sharp image instead of a placeholder.
            Modifier.sharedArt(ArtKeys.album(album.id, from), zIndex = 1f).size(CardWidth),
            shape, contentDescription = album.title, decodeSize = 720,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            album.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sharedMorph(ArtKeys.albumTitle(album.id, from), zIndex = 1f),
        )
        Text(
            listOfNotNull(album.year?.toString(), "${album.trackCount} ${if (album.trackCount == 1) "song" else "songs"}").joinToString(" · "),
            style = ArnavTheme.type.caption, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
