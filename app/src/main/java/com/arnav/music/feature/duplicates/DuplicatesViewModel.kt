package com.arnav.music.feature.duplicates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.library.DuplicateGroup
import com.arnav.music.domain.library.Duplicates
import com.arnav.music.domain.library.TrackUsage
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DuplicateVersion(val track: Track, val usage: TrackUsage)

data class DuplicateEntry(val group: DuplicateGroup, val versions: List<DuplicateVersion>) {
    val key: String get() = group.key
    /** The version played most, when one clearly leads. */
    val mostPlayed: TrackId? get() {
        val sorted = versions.sortedByDescending { it.usage.plays }
        val top = sorted.firstOrNull() ?: return null
        return if (top.usage.plays > 0 && (sorted.getOrNull(1)?.usage?.plays ?: 0) < top.usage.plays) top.track.id else null
    }
}

/** What happened to a group on this visit; it stays in the list as a short confirmation. */
sealed interface GroupOutcome {
    data class Kept(val track: Track, val playlists: Int, val likeMoved: Boolean) : GroupOutcome
    data object Ignored : GroupOutcome
    data object Failed : GroupOutcome
}

sealed interface DuplicatesUi {
    data object Loading : DuplicatesUi
    data class Ready(val groups: List<DuplicateEntry>, val ignoredCount: Int) : DuplicatesUi
    data object Failed : DuplicatesUi
}

/**
 * Finds the same song saved more than once (YouTube upload, file on this phone, matched import) and
 * lets the listener keep one version everywhere. Ignored groups are remembered on this device.
 */
class DuplicatesViewModel(context: Context, private val library: LibraryRepository) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow<DuplicatesUi>(DuplicatesUi.Loading)
    val ui: StateFlow<DuplicatesUi> = _ui.asStateFlow()

    private val _outcomes = MutableStateFlow<Map<String, GroupOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<String, GroupOutcome>> = _outcomes.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** Key of the group being changed right now. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _ui.value = DuplicatesUi.Loading
        _outcomes.value = emptyMap()
        val result = runCatching {
            val (tracks, usage) = library.libraryUsage()
            withContext(Dispatchers.Default) { Duplicates.find(tracks) } to usage
        }.getOrNull()
        if (result == null) { _ui.value = DuplicatesUi.Failed; return@launch }
        val ignored = ignoredKeys()
        val (groups, usage) = result
        val visible = groups.filter { it.key !in ignored }.map { g ->
            DuplicateEntry(g, g.tracks.map { DuplicateVersion(it, usage[it.id] ?: TrackUsage.NONE) })
        }
        _ui.value = DuplicatesUi.Ready(visible, ignoredCount = groups.count { it.key in ignored })
    }

    /** Replaces every other version with [keep] in Arnav playlists and likes. */
    fun keep(entry: DuplicateEntry, keep: Track) {
        if (_busy.value != null) return
        _busy.value = entry.key
        viewModelScope.launch {
            val outcome = runCatching {
                var playlists = 0
                var likeMoved = false
                for (v in entry.versions) {
                    if (v.track.id == keep.id) continue
                    playlists += library.replaceEverywhere(v.track.id, keep)
                    if (v.usage.liked) likeMoved = true
                }
                // Versions on this phone can't be removed from it; remember the decision instead.
                addIgnored(entry.key)
                GroupOutcome.Kept(keep, playlists, likeMoved)
            }.getOrElse { GroupOutcome.Failed }
            _outcomes.value = _outcomes.value + (entry.key to outcome)
            _busy.value = null
        }
    }

    fun ignore(entry: DuplicateEntry) {
        addIgnored(entry.key)
        _outcomes.value = _outcomes.value + (entry.key to GroupOutcome.Ignored)
    }

    /** Takes back an "Ignore" made on this visit. */
    fun unignore(entry: DuplicateEntry) {
        val keys = HashSet(ignoredKeys())
        keys.remove(entry.key)
        prefs.edit().putStringSet(KEY_IGNORED, keys).apply()
        _outcomes.value = _outcomes.value - entry.key
    }

    /** Shows every ignored group again. */
    fun showIgnored() {
        prefs.edit().remove(KEY_IGNORED).apply()
        load()
    }

    private fun ignoredKeys(): Set<String> = prefs.getStringSet(KEY_IGNORED, null)?.toSet() ?: emptySet()

    private fun addIgnored(key: String) {
        // Copy: the set returned by SharedPreferences must not be modified in place.
        val keys = HashSet(ignoredKeys())
        keys.add(key)
        prefs.edit().putStringSet(KEY_IGNORED, keys).apply()
    }

    companion object {
        private const val PREFS = "duplicates"
        private const val KEY_IGNORED = "ignored_groups"
    }
}
