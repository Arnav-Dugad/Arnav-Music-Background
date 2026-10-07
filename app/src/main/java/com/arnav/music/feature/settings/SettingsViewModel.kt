package com.arnav.music.feature.settings

import android.app.Activity
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.firebase.AuthRepository
import com.arnav.music.core.firebase.AuthResult
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.perf.PerformanceManager
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.repo.SearchRepository
import com.arnav.music.core.security.SecureStore
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@kotlinx.serialization.Serializable
data class SavedRule(val id: String, val name: String, val query: String)
data class CollectionPreview(val name: String, val query: String, val tracks: List<com.arnav.music.domain.model.Track>, val targetId: String? = null)
data class StorageInfo(val databaseKb: Long = 0, val artworkCacheKb: Long = 0, val httpCacheKb: Long = 0, val knownTracks: Int = 0, val aiCached: Int = 0, val searchCached: Int = 0)

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val secure: SecureStore,
    private val auth: AuthRepository,
    private val sync: CloudSync,
    private val library: LibraryRepository,
    private val search: SearchRepository,
    private val ai: AiGateway,
    val usage: UsageMeter,
    val perf: PerformanceManager,
    private val db: ArnavDatabase,
    private val analytics: Analytics,
    private val intelligence: IntelligenceRepository,
    val updates: com.arnav.music.core.update.UpdateManager,
    private val playback: com.arnav.music.core.playback.PlaybackController,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val budget = perf.budget
    val syncStatus = sync.status
    val syncError = sync.error
    val backups = sync.backups
    private val _restorePreview = MutableStateFlow<com.arnav.music.core.firebase.CloudBackup?>(null)
    val restorePreview = _restorePreview.asStateFlow()
    private var stagedArchive: com.arnav.music.core.backup.UserArchive? = null
    private var stagedUid: String? = null
    fun dismissRestore() { stagedArchive = null; stagedUid = null; _restorePreview.value = null }
    fun refreshBackups() = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Loading backups…"
        sync.refreshBackups().onFailure { _notice.value = it.message ?: "Couldn't load backups" }
        _busy.value = null
    }
    fun previewBackup(backup: com.arnav.music.core.firebase.CloudBackup) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Checking backup…"
        val uid = auth.current()?.uid
        sync.previewBackup(backup).onSuccess {
            if (uid != null && auth.current()?.uid == uid) {
                stagedArchive = it; stagedUid = uid; _restorePreview.value = backup
            }
        }.onFailure { _notice.value = it.message ?: "Couldn't verify backup" }
        _busy.value = null
    }
    fun restoreBackup() = viewModelScope.launch {
        val snapshot = stagedArchive ?: return@launch
        val uid = stagedUid ?: return@launch
        if (_busy.value != null) return@launch
        _busy.value = "Restoring your data…"
        val result = sync.restoreBackup(snapshot, uid) { playback.stopForRestore() }
        playback.reloadStoredQueue()
        reloadRules()
        intelligence.invalidate()
        _notice.value = if (result.isSuccess) "Backup restored. Restart the app to refresh all caches. Local audio files may need to be added again." else result.exceptionOrNull()?.message ?: "Restore failed"
        dismissRestore()
        _busy.value = null
    }
    fun exportBackup(context: android.content.Context, uri: android.net.Uri) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Exporting backup…"
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = com.arnav.music.core.backup.ArchiveCodec.encode(sync.vault.archive.capture())
                (context.contentResolver.openOutputStream(uri) ?: error("Couldn't open export file")).use { it.write(bytes) }
            }
        }
        _notice.value = if (result.isSuccess) "Backup exported" else result.exceptionOrNull()?.message ?: "Export failed"
        _busy.value = null
    }
    val user = auth.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, auth.current())
    val cloudAvailable: Boolean get() = auth.isAvailable
    val lastSyncedAt: Long get() = sync.lastSyncedAt
    val lastAiError: String? get() = ai.lastError
    fun clearAiBackoff() = ai.clearBackoff()

    private val _apiKeyPresent = MutableStateFlow(!secure.get(SecureStore.YOUTUBE_API_KEY).isNullOrBlank())
    val apiKeyPresent: StateFlow<Boolean> = _apiKeyPresent.asStateFlow()
    val builtInKey: Boolean = com.arnav.music.BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    fun resetRecommendations() = viewModelScope.launch { intelligence.resetRecommendations(); _notice.value = "Recommendations reset" }
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val rulePrefs = sync.vault.context.getSharedPreferences("smart_rules", android.content.Context.MODE_PRIVATE)
    private val ruleJson = kotlinx.serialization.json.Json
    private val _rules = MutableStateFlow<List<SavedRule>>(emptyList())
    val rules = _rules.asStateFlow()
    private val _collectionPreview = MutableStateFlow<CollectionPreview?>(null)
    val collectionPreview = _collectionPreview.asStateFlow()
    init { reloadRules() }
    private fun reloadRules() {
        _rules.value = runCatching { ruleJson.decodeFromString<List<SavedRule>>(rulePrefs.getString("rules", "[]") ?: "[]") }.getOrDefault(emptyList())
    }
    fun previewCollection(name: String, query: String, targetId: String? = null) = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Matching your library…"
        runCatching {
            val parsed = com.arnav.music.domain.search.LibraryQuery.parse(query)
            require(parsed.structured && parsed.error == null) { parsed.error ?: "Use at least one library filter" }
            _collectionPreview.value = CollectionPreview(name.trim().take(100).ifBlank { "My smart playlist" }, query.trim().take(300), search.localMatches(query, 500), targetId)
        }.onFailure { _notice.value = it.message ?: "Couldn't build preview" }
        _busy.value = null
    }
    fun dismissCollection() { _collectionPreview.value = null }
    fun saveCollection() = viewModelScope.launch {
        val preview = _collectionPreview.value ?: return@launch
        if (_busy.value != null) return@launch
        _busy.value = "Saving your playlist…"
        runCatching {
            val now = System.currentTimeMillis()
            val id = preview.targetId ?: library.createPlaylist(preview.name, "Saved library rule: " + preview.query, preview.tracks)
            if (preview.targetId != null) {
                val playlist = db.playlists().get(id) ?: error("Playlist no longer exists")
                check(!playlist.deleted) { "Playlist was deleted" }
                library.remember(preview.tracks)
                db.withTransaction {
                    db.playlists().replaceTracks(id, preview.tracks.map { it.id.value }, now)
                    db.playlists().upsert(playlist.copy(updatedAt = now, dirty = true))
                }
            }
            val newRules = _rules.value.filter { it.id != id } + SavedRule(id, preview.name, preview.query)
            rulePrefs.edit().putString("rules", ruleJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SavedRule.serializer()), newRules)).commit()
            reloadRules()
            sync.requestSync()
        }.onSuccess { _notice.value = "Playlist saved to Library"; dismissCollection() }
            .onFailure { _notice.value = it.message ?: "Couldn't save playlist" }
        _busy.value = null
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }
    fun consumeNotice() { _notice.value = null }

    fun saveApiKey(key: String) {
        val k = key.trim()
        secure.put(SecureStore.YOUTUBE_API_KEY, k.ifBlank { null })
        _apiKeyPresent.value = k.isNotBlank()
        _notice.value = if (k.isBlank()) "YouTube key removed" else "YouTube key saved securely on this device"
    }

    fun loadStorage(context: android.content.Context) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        fun sizeKb(f: java.io.File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024
        _storage.value = StorageInfo(
            databaseKb = context.getDatabasePath(ArnavDatabase.NAME).length() / 1024,
            artworkCacheKb = sizeKb(java.io.File(context.cacheDir, "artwork")),
            httpCacheKb = sizeKb(java.io.File(context.cacheDir, "http")),
            knownTracks = db.tracks().count(),
            aiCached = db.aiCache().count(),
            searchCached = db.search().count(),
        )
    }

    fun syncNow() = viewModelScope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Syncing your account…"
        val result = sync.syncNow()
        _notice.value = when {
            result.isFailure -> result.exceptionOrNull()?.message ?: "Sync failed"
            sync.status.value == com.arnav.music.core.firebase.SyncStatus.DISABLED -> "Sign in and enable cloud sync first"
            else -> "Sync and backup finished"
        }
        _busy.value = null
    }

    fun clearSearchHistory() = viewModelScope.launch { search.clearHistory(); search.clearCache(); _notice.value = "Search history and saved results cleared" }
    fun clearListeningHistory() = viewModelScope.launch { library.clearHistory(); intelligence.invalidate(); _notice.value = "Listening history cleared" }
    fun deleteAiPersonalization() = viewModelScope.launch {
        ai.clearCache()
        settingsRepo.update { it.copy(aiPersonalization = false) }
        _notice.value = "AI personalization deleted and turned off"
    }
    fun disconnectYouTube() { saveApiKey(""); _notice.value = "YouTube disconnected. Saved results remain until you clear them." }

    fun deleteCloudProfile() = viewModelScope.launch {
        _busy.value = "Deleting cloud data…"
        _notice.value = if (sync.deleteCloudProfile().isSuccess) "Cloud profile deleted" else "Couldn't reach the cloud. Try again when online."
        _busy.value = null
    }

    fun deleteAccount(activity: Activity?) = viewModelScope.launch {
        _busy.value = "Deleting your account…"
        val cloud = sync.deleteCloudProfile()
        val result = if (cloud.isSuccess) auth.deleteAccount() else AuthResult.Failure("Couldn't delete cloud data. Check your connection.")
        _notice.value = when (result) {
            AuthResult.Success -> { auth.signOut(activity); "Your account and cloud data are deleted" }
            is AuthResult.Failure -> result.message
            AuthResult.Cancelled -> null
        }
        _busy.value = null
    }

    fun signOut(activity: Activity?) = viewModelScope.launch { auth.signOut(activity); _notice.value = "Signed out. Your library stays on this device." }
    fun resendVerification() = viewModelScope.launch { _notice.value = if (auth.resendVerification() == AuthResult.Success) "Verification email sent" else "Couldn't send the email" }
    fun setAnalytics(on: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(analytics = on) }; analytics.setEnabled(on) }
    fun resetOnboarding() = viewModelScope.launch { settingsRepo.update { it.copy(onboardingDone = false) } }
}
