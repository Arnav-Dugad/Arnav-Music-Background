package com.arnav.music.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.firebase.AuthRepository
import com.arnav.music.core.firebase.UserAccount
import com.arnav.music.core.perf.PerformanceManager
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.playback.SleepTimer
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.settings.ThemeMode
import com.arnav.music.domain.model.Playlist
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot UI messages (snackbars). */
data class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** App-wide state & actions shared by every screen: settings, player, likes, account. */
class AppViewModel(
    private val settingsRepo: SettingsRepository,
    val player: PlaybackController,
    private val library: LibraryRepository,
    perf: PerformanceManager,
    network: NetworkMonitor,
    private val auth: AuthRepository,
    private val analytics: Analytics,
    private val localSource: com.arnav.music.core.local.LocalMediaSource,
    private val youtube: com.arnav.music.core.youtube.YouTubeRepository,
    val updates: com.arnav.music.core.update.UpdateManager,
) : ViewModel() {
    private var downloadJob: kotlinx.coroutines.Job? = null

    fun checkForUpdate() = viewModelScope.launch { updates.check() }
    fun downloadUpdate(release: com.arnav.music.core.update.ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            if (updates.download(release) && settings.value.autoInstallUpdates) updates.install()
        }
    }
    fun cancelUpdateDownload() { downloadJob?.cancel() }
    fun installUpdate() = viewModelScope.launch { updates.install() }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val settingsLoaded: StateFlow<Boolean> = settingsRepo.loaded
    val budget = perf.budget
    val playerState = player.state
    val progress = player.progress
    val liked: StateFlow<Set<TrackId>> = library.likedIds
    val online: StateFlow<Boolean> = network.isOnline
    val user: StateFlow<UserAccount?> = auth.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, auth.current())
    val playlists: StateFlow<List<Playlist>> = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages

    fun message(text: String, action: String? = null, onAction: (() -> Unit)? = null) { _messages.tryEmit(UiMessage(text, action, onAction)) }

    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        player.playTracks(tracks, index, shuffle)
        analytics.log(Analytics.Event.PLAY_REQUESTED, mapOf("source" to tracks.getOrNull(index)?.source?.name.orEmpty()))
    }

    /** Opens a shared/deep-linked YouTube video in the player. */
    fun playVideo(videoId: String) = viewModelScope.launch {
        youtube.video(videoId)
            .onSuccess { play(listOf(it)) }
            .onFailure { e ->
                message(when (e) {
                    com.arnav.music.domain.provider.MusicError.MissingApiKey -> "Add a YouTube key in Settings → Sources to open YouTube links."
                    com.arnav.music.domain.provider.MusicError.QuotaExhausted -> "Today's YouTube allowance is used up. Try again tomorrow."
                    com.arnav.music.domain.provider.MusicError.Offline -> "You're offline."
                    else -> "That video can't be played here."
                })
            }
    }

    fun playNext(track: Track) { player.playNext(listOf(track)); message("Playing next") }
    fun addToQueue(track: Track) { player.addToQueue(listOf(track)); message("Added to queue", "Undo") { undoLastQueueAdd(track) } }
    private fun undoLastQueueAdd(track: Track) {
        val q = player.state.value.queue
        val idx = q.items.indexOfLast { it.track.id == track.id }
        if (idx > q.currentIndex) player.removeAt(idx)
    }

    fun toggleLike(track: Track) = viewModelScope.launch {
        val liked = library.toggleLike(track)
        analytics.log(Analytics.Event.FAVORITE_CHANGED, mapOf("liked" to liked.toString()))
    }

    fun addToPlaylist(playlistId: String, track: Track) = viewModelScope.launch {
        library.addToPlaylist(playlistId, listOf(track))
        message("Added to playlist")
    }

    fun createPlaylistWith(name: String, tracks: List<Track>) = viewModelScope.launch {
        library.createPlaylist(name, tracks = tracks)
        analytics.log(Analytics.Event.PLAYLIST_CREATED)
        message("Playlist “${name.take(30)}” created")
    }

    fun saveQueueAsPlaylist() {
        val tracks = player.state.value.queue.items.map { it.track }
        if (tracks.isEmpty()) return
        createPlaylistWith("Queue · ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())}", tracks)
    }

    fun setSleep(timer: SleepTimer?) {
        player.setSleepTimer(timer)
        message(if (timer == null) "Sleep timer off" else "Sleep timer set")
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = settings.value
        settingsRepo.update(transform)
        if (before.glass != settings.value.glass) analytics.log(Analytics.Event.GLASS_MODE_CHANGED, mapOf("level" to settings.value.glass.name))
    }

    fun setGlass(level: GlassLevel) = updateSettings { it.copy(glass = level) }
    fun setTheme(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }

    fun hasLocalPermission() = localSource.hasPermission()
    val localPermission: String get() = localSource.permission

    fun signOut(activity: Activity?) = viewModelScope.launch { auth.signOut(activity) }
}
