package com.arnav.music.feature.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.library.Albums
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private val editionPattern = Regex("(?i)\\b(deluxe|expanded|anniversary|remaster(?:ed)?|live|acoustic|instrumental|remix)\\b")
private fun edition(track: Track): String = editionPattern.find(track.album.orEmpty() + " " + track.title)?.value
    ?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Standard / unlabelled"
private fun albumBase(title: String) = title.lowercase().replace(Regex("(?i)\\s*[\\[(].*(deluxe|expanded|anniversary|remaster).*?[\\])].*"), "").trim()

private data class DetailSnapshot(
    val listens: Int = 0, val listenedMs: Long = 0,
    val provenance: String = "", val related: List<com.arnav.music.domain.library.AlbumSummary> = emptyList(),
)

/** Shows evidence from library tags and actual play events; never invents release/credit facts. */
@Composable
fun LibraryDetails(tracks: List<Track>, albumId: String? = null) {
    if (tracks.isEmpty()) return
    val db: ArnavDatabase = koinInject()
    val library: LibraryRepository = koinInject()
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val c = ArnavTheme.colors
    var details by remember(tracks.map { it.id }) { mutableStateOf<DetailSnapshot?>(null) }
    val events by db.events().observeSince(0).collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(tracks.map { it.id }, events.size) {
        details = withContext(Dispatchers.IO) {
            val ids = tracks.map { it.id.value }.toSet()
            val listens = events.filter { it.trackId in ids && it.listenedMs >= 5_000 }
            val overrides = db.tagOverrides().all().filter { it.trackId in ids }
            val userEdits = overrides.count { it.source == "user" }
            val matched = overrides.count { it.source == "musicbrainz" }
            val related = if (albumId != null) {
                val first = tracks.first()
                Albums.group(library.localTracksSnapshot()).filter { it.id != albumId &&
                    ArtistKey.of(it.artist) == ArtistKey.of(first.albumArtist ?: first.artist) &&
                    albumBase(it.title) == albumBase(first.album.orEmpty()) }
            } else emptyList()
            DetailSnapshot(listens.size, listens.sumOf { it.listenedMs },
                listOfNotNull(userEdits.takeIf { it > 0 }?.let { "$it user-edited" },
                    matched.takeIf { it > 0 }?.let { "$it MusicBrainz matches" },
                    (tracks.size - overrides.size).takeIf { it > 0 }?.let { "$it source tags" }).joinToString(" · "), related)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(if (albumId == null) "Explore this artist" else "Inside this album", style = ArnavTheme.type.title, color = c.content)
        Column(Modifier.fillMaxWidth().glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            val d = details
            if (albumId != null && d != null) {
                Text("${d.listens} listens · ${Formatters.longDuration(d.listenedMs)} with this album",
                    style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            }
            val years = tracks.mapNotNull { it.year }.distinct().sorted()
            val genres = tracks.flatMap { it.genres }.distinct().take(6)
            if (years.isNotEmpty() || genres.isNotEmpty()) Text(
                (years.map { it.toString() } + genres).joinToString(" · "), style = ArnavTheme.type.caption, color = c.contentMuted)
            d?.provenance?.takeIf { it.isNotBlank() }?.let {
                Text("Metadata · $it", style = ArnavTheme.type.caption, color = c.contentSubtle)
            }
            val editions = remember(tracks) { tracks.groupBy(::edition) }
            if (editions.size > 1 || editions.keys.singleOrNull() != "Standard / unlabelled") {
                Text("Versions in your library", style = ArnavTheme.type.label, color = c.content)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    editions.forEach { (label, songs) ->
                        Pill("$label · ${songs.size}", selected = false, onClick = { app.play(songs, 0) })
                    }
                }
                Text("Version labels come from song and album titles; they are not verified recording identities.",
                    style = ArnavTheme.type.caption, color = c.contentSubtle)
            }
            d?.related?.forEach { album ->
                Text("Other edition · ${album.title} · ${album.trackCount} songs", style = ArnavTheme.type.bodySmall,
                    color = c.accent, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.go(Routes.album(album.id)) }.padding(vertical = Space.s))
            }
            Text("Credits & sources", style = ArnavTheme.type.label, color = c.content)
            tracks.take(4).forEach { t ->
                Text(t.title + " · View credits", style = ArnavTheme.type.bodySmall, color = c.accent,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Credits for ${t.title}") { nav.go(Routes.credits(t.id.value)) }.padding(vertical = Space.s))
            }
        }
    }
}
