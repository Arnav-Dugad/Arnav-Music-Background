package com.arnav.music.feature.credits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.metadata.CreditsRepository
import com.arnav.music.core.metadata.CreditsState
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.metadata.TrackCredits
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CreditsViewModel(
    private val credits: CreditsRepository,
    private val library: LibraryRepository,
    private val player: PlaybackController,
) : ViewModel() {
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()
    private val _state = MutableStateFlow<CreditsState>(CreditsState.Loading)
    val state: StateFlow<CreditsState> = _state.asStateFlow()
    private var loadedId: String? = null
    private var job: Job? = null

    fun load(trackId: String, force: Boolean = false) {
        if (!force && loadedId == trackId && job != null) return
        loadedId = trackId
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = CreditsState.Loading
            val id = TrackId(trackId)
            val track = resolve(id)
            _track.value = track
            _state.value = if (track == null) CreditsState.Ready(TrackCredits.Empty, false) else credits.load(track)
        }
    }

    fun retry() { loadedId?.let { load(it, force = true) } }

    /** The playing song, a song the app has seen, an on-device file, or a bare YouTube video id. */
    private suspend fun resolve(id: TrackId): Track? {
        player.state.value.current?.takeIf { it.id == id }?.let { return it }
        runCatching { library.tracks(listOf(id)).firstOrNull() }.getOrNull()?.let { return it }
        if (id.source == SourceType.YOUTUBE && id.nativeId.length == 11) {
            return Track(id = id, title = "", artist = "", playbackRef = id.nativeId)
        }
        return null
    }
}
