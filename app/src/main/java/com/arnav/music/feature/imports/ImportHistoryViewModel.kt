package com.arnav.music.feature.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.db.ImportHistoryEntity
import com.arnav.music.core.importer.ImportMatcher
import com.arnav.music.core.repo.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A playlist touched by an import, with its current name and size. */
data class ImportedPlaylistInfo(val id: String, val name: String, val songs: Int, val hidden: Boolean)

data class ImportRow(val entry: ImportHistoryEntity, val playlists: List<ImportedPlaylistInfo>, val matched: Int) {
    val id: String get() = entry.id
    val fromFile: Boolean get() = entry.source != ImportMatcher.SOURCE_YOUTUBE
}

class ImportHistoryViewModel(private val matcher: ImportMatcher, private val library: LibraryRepository) : ViewModel() {
    /** Null while loading. Re-reads when playlists change, so names and matched counts stay current. */
    val rows: StateFlow<List<ImportRow>?> = combine(matcher.history, library.playlists) { history, _ -> history }
        .map { history -> history.map { row(it) } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _notes = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Short confirmation shown under an entry after Undo/Redo on this visit. */
    val notes: StateFlow<Map<String, String>> = _notes.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun undo(id: String) = perform(id) {
        val r = matcher.undoImport(id) ?: return@perform "This import is no longer in the history."
        when {
            r.hidden == 0 && r.kept > 0 -> "Marked as undone. Its playlists stay, because another import also brought them in."
            r.hidden == 0 -> "Marked as undone. Its playlists were already gone."
            else -> buildString {
                append(if (r.hidden == 1) "Removed 1 playlist from your library." else "Removed ${r.hidden} playlists from your library.")
                if (r.kept > 0) append(if (r.kept == 1) " Kept 1 that another import also brought in." else " Kept ${r.kept} that another import also brought in.")
                append(" Redo brings them back.")
            }
        }
    }

    fun redo(id: String) = perform(id) {
        when (val n = matcher.redoImport(id)) {
            null -> "This import is no longer in the history."
            0 -> "Done. Its playlists were already in your library."
            1 -> "Restored 1 playlist."
            else -> "Restored $n playlists."
        }
    }

    private fun perform(id: String, block: suspend () -> String) {
        if (id in _busy.value) return
        _busy.value = _busy.value + id
        viewModelScope.launch {
            val note = runCatching { block() }.getOrElse { "That didn't work. Nothing was changed; try again." }
            _notes.value = _notes.value + (id to note)
            _busy.value = _busy.value - id
        }
    }

    private suspend fun row(e: ImportHistoryEntity): ImportRow {
        val ids = e.playlistIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val playlists = library.playlistsWithCounts(ids).map { (p, n) -> ImportedPlaylistInfo(p.id, p.name, n, p.deleted) }
        // Spotify/CSV songs keep matching in the background after the import; count what's there now.
        val matched = if (e.source != ImportMatcher.SOURCE_YOUTUBE && !e.undone) {
            maxOf(e.matchedCount, playlists.filter { !it.hidden }.sumOf { it.songs }).coerceAtMost(e.songCount)
        } else e.matchedCount
        return ImportRow(e, playlists, matched)
    }
}
