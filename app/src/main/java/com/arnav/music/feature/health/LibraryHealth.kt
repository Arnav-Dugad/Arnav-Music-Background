package com.arnav.music.feature.health

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.library.Duplicates
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HealthIssue(val title: String, val detail: String, val tracks: List<Track> = emptyList(), val route: String? = null)
data class HealthReport(val scanning: Boolean = true, val total: Int = 0, val issues: List<HealthIssue> = emptyList(), val error: String? = null)

/** Read-only inventory scan. No file deletion, network probes or guessed YouTube availability. */
class LibraryHealthViewModel(
    private val context: Context, private val db: ArnavDatabase,
    private val library: LibraryRepository, private val local: LocalMediaSource,
) : ViewModel() {
    private val _report = MutableStateFlow(HealthReport())
    val report = _report.asStateFlow()
    init { scan() }
    fun scan() {
        if (_report.value.scanning && _report.value.total > 0) return
        _report.value = _report.value.copy(scanning = true, error = null)
        viewModelScope.launch {
            _report.value = try {
                withContext(Dispatchers.IO) {
                    val (saved, _) = library.libraryUsage()
                    val available = library.localTracksSnapshot()
                    val all = (available + saved).distinctBy { it.id }
                    val availableIds = available.map { it.id.value }.toSet()
                    val stored = db.tracks().all().associateBy { it.id }
                    val entries = db.playlists().activeEntries()
                    val referenced = (entries.map { it.trackId } + db.likes().all().filter { !it.deleted }.map { it.trackId }).toSet()
                    val issues = buildList {
                        if (!local.hasPermission()) {
                            add(HealthIssue("Audio access is off", "Grant audio access before checking whether local files are missing.", route = "settings/library"))
                        } else {
                            val ids = available.map { it.id.value }.toSet()
                            val missing = referenced.filter { TrackId(it).source == SourceType.LOCAL && it !in ids }
                                .mapNotNull { stored[it]?.toDomain() }
                            if (missing.isNotEmpty()) add(HealthIssue("${missing.size} missing local files",
                                "Saved references were not found in MediaStore. Files may have moved, been removed or belong to another phone.", missing))
                            val artByAlbum = available.mapNotNull { it.albumId }.distinct().associateWith { local.hasAlbumArt(it) }
                            val withoutArt = all.filter { t -> t.artworkUrl.isNullOrBlank() ||
                                (t.source == SourceType.LOCAL && t.artworkUrl?.startsWith("content:") == true && artByAlbum[t.albumId] == false) }
                            if (withoutArt.isNotEmpty()) add(HealthIssue("${withoutArt.size} songs need artwork",
                                "Local album artwork was checked on this phone. Remote URLs are not downloaded during this scan.", withoutArt))
                        }
                        val incomplete = all.filter { it.artist.isBlank() || it.artist.equals("Unknown artist", true) || it.album.isNullOrBlank() || it.title.isBlank() }
                        if (incomplete.isNotEmpty()) add(HealthIssue("${incomplete.size} incomplete song tags", "Review artist, album and title metadata. Editing tags in Arnav leaves the audio file unchanged.", incomplete))
                        val groups = Duplicates.find(all)
                        if (groups.isNotEmpty()) add(HealthIssue("${groups.size} possible duplicate groups", "Compare versions before keeping one. Live and studio recordings may be intentionally different.", route = com.arnav.music.ui.Routes.DUPLICATES))
                        val pending = db.pendingMatches().openCountNow()
                        if (pending > 0) add(HealthIssue("$pending imports waiting to match", "Open import history to review and finish matching songs.", route = com.arnav.music.ui.Routes.IMPORTS))
                        val dangling = entries.count { it.trackId !in stored && it.trackId !in availableIds }
                        if (dangling > 0) add(HealthIssue("$dangling playlist references need review", "Their song metadata is absent. Reimport the source playlist or restore a verified backup.", route = "settings/sync"))
                    }
                    HealthReport(false, all.size, issues)
                }
            } catch (e: Exception) { HealthReport(false, error = e.message ?: "Library scan failed") }
        }
    }
}
