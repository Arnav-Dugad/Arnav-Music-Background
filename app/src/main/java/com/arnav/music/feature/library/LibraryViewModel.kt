package com.arnav.music.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.repo.SmartMix
import com.arnav.music.core.settings.LibraryLayout
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.library.AlbumSummary
import com.arnav.music.domain.library.Albums
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { ALL("All"), PLAYLISTS("Playlists"), LIKED("Liked"), ARTISTS("Artists"), ALBUMS("Albums"), LOCAL("On device") }
enum class LibrarySort(val label: String) { RECENT("Recently added"), TITLE("Title"), ARTIST("Artist") }

data class ArtistEntry(val key: String, val name: String, val artworkUrl: String?, val tracks: Int)

class LibraryViewModel(
    private val library: LibraryRepository,
    private val intelligence: IntelligenceRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val tab = MutableStateFlow(LibraryTab.ALL)
    val sort = MutableStateFlow(LibrarySort.RECENT)
    val filter = MutableStateFlow("")
    val layout: StateFlow<LibraryLayout> = settings.settings.map { it.libraryLayout }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryLayout.LIST)

    val playlists: StateFlow<List<Playlist>> = combine(library.playlists, filter) { list, q ->
        list.filter { q.isBlank() || it.name.contains(q, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liked: StateFlow<List<Track>> = combine(library.likedTracks, filter, sort) { list, q, s -> list.applyFilterSort(q, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val local: StateFlow<List<Track>> = combine(library.localTracks, filter, sort) { list, q, s -> list.applyFilterSort(q, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<ArtistEntry>> = combine(library.likedTracks, library.localTracks, filter) { a, b, q ->
        (a + b).groupBy { it.artistKey }.map { (k, v) -> ArtistEntry(k, v.first().artist, v.firstNotNullOfOrNull { it.artworkUrl }, v.size) }
            .filter { q.isBlank() || it.name.contains(q, true) }
            .sortedByDescending { it.tracks }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Albums of on-device songs (tag overrides applied), filtered by title or artist. */
    val albums: StateFlow<List<AlbumSummary>> = combine(library.localTracks, filter) { list, q ->
        Albums.group(list).filter { q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _mixes = MutableStateFlow<List<SmartMix>>(emptyList())
    val mixes: StateFlow<List<SmartMix>> = _mixes.asStateFlow()

    init { viewModelScope.launch { _mixes.value = runCatching { intelligence.smartMixes() }.getOrDefault(emptyList()) } }

    fun setLayout(l: LibraryLayout) = viewModelScope.launch { settings.update { it.copy(libraryLayout = l) } }
    fun createPlaylist(name: String) = viewModelScope.launch { library.createPlaylist(name) }

    private fun List<Track>.applyFilterSort(q: String, s: LibrarySort): List<Track> {
        val f = if (q.isBlank()) this else filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album?.contains(q, true) == true }
        return when (s) {
            LibrarySort.RECENT -> f
            LibrarySort.TITLE -> f.sortedBy { it.title.lowercase() }
            LibrarySort.ARTIST -> f.sortedBy { it.artist.lowercase() }
        }
    }
}
