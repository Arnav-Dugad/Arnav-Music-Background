package com.arnav.music.core.importer

import android.content.Context
import com.arnav.music.core.common.Clock
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.ImportHistoryEntity
import com.arnav.music.core.db.PendingMatchEntity
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.catalog.rankForListening
import com.arnav.music.domain.importer.ImportSource
import com.arnav.music.domain.importer.ImportedSong
import com.arnav.music.domain.importer.LocalMatchIndex
import com.arnav.music.domain.importer.MatchScorer
import com.arnav.music.domain.importer.ParsedImport
import com.arnav.music.domain.model.MediaVariant
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.provider.SearchResults
import com.arnav.music.domain.quota.QuotaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Songs from Spotify/CSV imports still waiting for a YouTube match. */
data class MatchProgress(
    /** Rows still to search for (excludes "No match found"). */
    val open: Int,
    /** A background run is searching right now (this process only). */
    val running: Boolean = false,
    /** Today's share of the YouTube search allowance for imports ([ImportMatcher.DAILY_SEARCH_CAP]) is spent. */
    val dailyCapReached: Boolean = false,
    /** The YouTube quota is past NORMAL (conserving or exhausted); matching waits for the daily reset. */
    val quotaLow: Boolean = false,
) {
    val pausedForToday: Boolean get() = dailyCapReached || quotaLow
}

data class PlaylistImportResult(val playlistId: String, val name: String, val total: Int, val matched: Int, val pending: Int)

data class FileImportSummary(val source: ImportSource, val playlists: List<PlaylistImportResult>) {
    val total: Int get() = playlists.sumOf { it.total }
    val matched: Int get() = playlists.sumOf { it.matched }
    val pending: Int get() = playlists.sumOf { it.pending }
}

sealed interface MatchNowResult {
    data class Matched(val track: Track) : MatchNowResult
    data object NoMatch : MatchNowResult
    data object QuotaLimited : MatchNowResult
    data object Offline : MatchNowResult
    data object Gone : MatchNowResult
}

enum class RunOutcome { DONE, BUDGET_SPENT, QUOTA, OFFLINE }

/** [hidden] playlists were removed from the library; [kept] stayed because another import also brought them in. */
data class UndoOutcome(val hidden: Int, val kept: Int)

/**
 * Turns imported songs into playable tracks while spending as little YouTube quota as possible:
 *  1. at import time, every song is matched against songs on this device and tracks already known
 *     to the app (zero quota); the playlist is created right away with those;
 *  2. the rest wait in `pending_matches` and are searched by [MatchWorker] a few at a time
 *     (search.list costs 100 of the 10,000 free daily units), stopping at [PER_RUN_SEARCHES] per run,
 *     [DAILY_SEARCH_CAP] per day, or as soon as the quota leaves NORMAL — never the whole allowance.
 */
class ImportMatcher(
    private val context: Context,
    private val db: ArnavDatabase,
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
    private val clock: Clock,
) {
    private val dao = db.pendingMatches()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val runLock = Mutex()
    private val running = MutableStateFlow(false)
    private val _runs = MutableStateFlow(0)

    val isRunning: StateFlow<Boolean> = running.asStateFlow()

    val progress: Flow<MatchProgress> = combine(dao.openCount(), running, _runs) { open, isRunning, _ ->
        if (open == 0) MatchProgress(0, isRunning)
        else MatchProgress(open, isRunning, dailyCapReached = dailyRemaining() <= 0, quotaLow = youtube.quotaState() != QuotaState.NORMAL)
    }

    fun pendingFor(playlistId: String): Flow<List<PendingMatchEntity>> = dao.forPlaylist(playlistId)

    // ------------------------------------------------------------------ import

    /**
     * Creates one Arnav playlist per imported playlist, matching what it can for free, and queues the
     * rest. The import is recorded in the import history under [label] (usually the file name).
     */
    suspend fun import(parsed: ParsedImport, label: String? = null): FileImportSummary = withContext(Dispatchers.Default) {
        val index = LocalMatchIndex(library.localTracksSnapshot())
        val description = when (parsed.source) {
            ImportSource.SPOTIFY -> "Imported from Spotify"
            ImportSource.CSV -> "Imported from CSV"
        }
        val knownCache = HashMap<String, List<Track>>()
        val results = parsed.playlists.map { pl ->
            val placed = ArrayList<Pair<Int, Track>>()
            val pending = ArrayList<Pair<Int, ImportedSong>>()
            pl.songs.forEachIndexed { i, song ->
                val hit = (if (index.isEmpty) null else index.match(song)) ?: matchKnown(song, knownCache)
                if (hit != null) placed += i to hit else pending += i to song
            }
            val id = library.createImportedPlaylist(pl.name, description, placed)
            if (pending.isNotEmpty()) {
                val now = clock.now()
                dao.insertAll(
                    pending.map { (i, s) ->
                        PendingMatchEntity(
                            playlistId = id, position = i, title = s.title, artist = s.artist, album = s.album,
                            durationMs = s.durationMs ?: 0L, createdAt = now,
                        )
                    },
                )
            }
            PlaylistImportResult(id, pl.name, pl.songs.size, placed.size, pending.size)
        }
        if (results.any { it.pending > 0 }) {
            MatchWorker.schedule(context)
            MatchWorker.runOnce(context)
        }
        val summary = FileImportSummary(parsed.source, results)
        runCatching {
            val historyLabel = label?.takeIf { it.isNotBlank() }
                ?: results.singleOrNull()?.name
                ?: "${results.size} playlists"
            library.recordImport(
                source = if (parsed.source == ImportSource.SPOTIFY) SOURCE_SPOTIFY else SOURCE_CSV,
                label = historyLabel, playlistIds = results.map { it.playlistId },
                songCount = summary.total, matchedCount = summary.matched,
            )
        }
        summary
    }

    /** Free: tracks already stored in Room (played, liked, searched or imported before). */
    private suspend fun matchKnown(song: ImportedSong, cache: MutableMap<String, List<Track>>? = null): Track? {
        val q = MatchScorer.cleanTitle(song.title).take(80)
        if (q.length < 2) return null
        val candidates = cache?.get(q) ?: runCatching { library.searchKnown(q) }.getOrDefault(emptyList()).also { cache?.put(q, it) }
        return MatchScorer.pick(song, candidates)
    }

    // ------------------------------------------------------------------ background matching

    /**
     * Searches YouTube for queued songs in import order. [manual] runs (the user tapped "Match more
     * now") skip the daily import cap but still stop at the per-run budget and outside NORMAL quota.
     */
    suspend fun runPending(manual: Boolean = false): RunOutcome = runLock.withLock {
        running.value = true
        try {
            var searches = 0
            var outcome: RunOutcome? = null
            while (outcome == null) {
                val batch = dao.next(BATCH)
                if (batch.isEmpty()) { outcome = RunOutcome.DONE; break }
                for (row in batch) {
                    if (youtube.quotaState() != QuotaState.NORMAL) { outcome = RunOutcome.QUOTA; break }
                    if (searches >= PER_RUN_SEARCHES || (!manual && dailyRemaining() <= 0)) { outcome = RunOutcome.BUDGET_SPENT; break }
                    when (val r = resolve(row)) {
                        is Resolved.Remote -> if (r.spent) searches++
                        is Resolved.Free, Resolved.Skipped -> Unit
                        Resolved.Quota -> { outcome = RunOutcome.QUOTA; break }
                        Resolved.Offline -> { outcome = RunOutcome.OFFLINE; break }
                    }
                }
            }
            outcome ?: RunOutcome.DONE
        } finally {
            running.value = false
            _runs.value = _runs.value + 1
        }
    }

    /** One row, right now (the user tapped it). Allowed until the quota is exhausted. */
    suspend fun matchNow(id: Long): MatchNowResult {
        val row = dao.get(id) ?: return MatchNowResult.Gone
        if (youtube.quotaState() == QuotaState.EXHAUSTED && youtube.cached(MatchScorer.query(row.song()), SearchFilter.TRACKS) == null) {
            return MatchNowResult.QuotaLimited
        }
        return when (val r = resolve(row)) {
            is Resolved.Remote -> r.track?.let { MatchNowResult.Matched(it) } ?: MatchNowResult.NoMatch
            is Resolved.Free -> MatchNowResult.Matched(r.track)
            Resolved.Skipped -> MatchNowResult.Gone
            Resolved.Quota -> MatchNowResult.QuotaLimited
            Resolved.Offline -> MatchNowResult.Offline
        }
    }

    /** Removes a pending row ("No match found" → remove). */
    suspend fun dismiss(id: Long) = dao.delete(id)

    suspend fun hasOpenRows(): Boolean = dao.openCountNow() > 0

    /** "Match more now": one extra run right away, still capped per run and by quota state. */
    fun matchMoreNow() = MatchWorker.runOnce(context, manual = true)

    private sealed interface Resolved {
        /** Searched YouTube; [spent] when it cost quota (not served from cache). */
        data class Remote(val track: Track?, val spent: Boolean) : Resolved
        data class Free(val track: Track) : Resolved
        data object Skipped : Resolved
        data object Quota : Resolved
        data object Offline : Resolved
    }

    private suspend fun resolve(row: PendingMatchEntity): Resolved {
        if (!library.playlistExists(row.playlistId)) {
            dao.deleteForPlaylist(row.playlistId)
            return Resolved.Skipped
        }
        val song = row.song()
        // Songs played or searched since the import may already be known — still free.
        matchKnown(song)?.let { hit ->
            library.insertImportedTrack(row.playlistId, hit, row.position)
            dao.delete(row.id)
            return Resolved.Free(hit)
        }
        val query = MatchScorer.query(song)
        val cached = youtube.cached(query, SearchFilter.TRACKS)
        val results: SearchResults
        var spent = false
        if (cached != null) {
            results = cached
        } else {
            val r = youtube.search(query, SearchFilter.TRACKS, null)
            val e = r.exceptionOrNull()
            if (e != null) {
                return when (e) {
                    MusicError.QuotaExhausted -> Resolved.Quota
                    MusicError.Offline -> Resolved.Offline
                    else -> {
                        // Unexpected API error: count the attempt; give up on this row after a few.
                        dao.markAttempt(row.id, failed = row.attempts + 1 >= MAX_ATTEMPTS)
                        Resolved.Offline
                    }
                }
            }
            results = r.getOrNull() ?: return Resolved.Offline
            spent = !results.fromCache
            if (spent) countSearch()
        }
        val ranked = rankForListening(results.tracks, MediaVariant.SONG)
        val pick = MatchScorer.pick(song, ranked)
        if (pick != null) {
            library.insertImportedTrack(row.playlistId, pick, row.position)
            dao.delete(row.id)
        } else {
            dao.markAttempt(row.id, failed = true)
        }
        return Resolved.Remote(pick, spent)
    }

    private fun PendingMatchEntity.song() = ImportedSong(title, artist, album, durationMs.takeIf { it > 0 })

    // ------------------------------------------------------------------ import history

    val history: Flow<List<ImportHistoryEntity>> = library.importHistory()

    /**
     * Undoes an import: hides the playlists it created (songs kept, so it can be redone) and parks
     * their songs still waiting for a match. A playlist that another, still-active import also brought
     * in stays. Never touches playlists the user made: history only lists ids made by the import flows.
     */
    suspend fun undoImport(id: String): UndoOutcome? {
        val entry = library.importHistoryEntry(id) ?: return null
        if (entry.undone) return UndoOutcome(0, 0)
        val ids = entry.ids()
        val claimedElsewhere = library.importHistory().first()
            .filter { it.id != id && !it.undone }
            .flatMap { it.ids() }.toSet()
        val candidates = ids.filter { it !in claimedElsewhere }
        val parked = candidates.flatMap { pid -> dao.forPlaylist(pid).first() }
        val hidden = library.hideImportedPlaylists(candidates)
        val parkedForHidden = parked.filter { it.playlistId in hidden }
        saveUndoSnapshot(id, hidden, parkedForHidden)
        hidden.forEach { dao.deleteForPlaylist(it) }
        library.saveImportHistory(entry.copy(undone = true))
        return UndoOutcome(hidden = hidden.size, kept = ids.count { it in claimedElsewhere && library.playlistExists(it) })
    }

    /** Redoes an undone import: the hidden playlists come back and parked songs resume matching. */
    suspend fun redoImport(id: String): Int? {
        val entry = library.importHistoryEntry(id) ?: return null
        if (!entry.undone) return 0
        val snapshot = loadUndoSnapshot(id)
        val restored = library.restoreImportedPlaylists(snapshot?.first ?: entry.ids())
        val parked = snapshot?.second.orEmpty()
        if (parked.isNotEmpty()) {
            val restoredIds = parked.map { it.playlistId }.distinct().filter { library.playlistExists(it) }.toSet()
            val rows = parked.filter { it.playlistId in restoredIds }
            if (rows.isNotEmpty()) {
                dao.insertAll(rows)
                MatchWorker.schedule(context)
                MatchWorker.runOnce(context)
            }
        }
        prefs.edit().remove(KEY_UNDO_PREFIX + id).apply()
        library.saveImportHistory(entry.copy(undone = false))
        return restored
    }

    private fun ImportHistoryEntity.ids(): List<String> = playlistIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveUndoSnapshot(id: String, hidden: List<String>, parked: List<PendingMatchEntity>) {
        val rows = JSONArray()
        parked.forEach { r ->
            rows.put(
                JSONObject()
                    .put("playlistId", r.playlistId).put("position", r.position).put("title", r.title).put("artist", r.artist)
                    .put("album", r.album ?: "").put("durationMs", r.durationMs).put("attempts", r.attempts)
                    .put("failed", r.failed).put("createdAt", r.createdAt),
            )
        }
        val json = JSONObject().put("hidden", JSONArray(hidden)).put("pending", rows)
        prefs.edit().putString(KEY_UNDO_PREFIX + id, json.toString()).apply()
    }

    private fun loadUndoSnapshot(id: String): Pair<List<String>, List<PendingMatchEntity>>? = runCatching {
        val raw = prefs.getString(KEY_UNDO_PREFIX + id, null) ?: return null
        val json = JSONObject(raw)
        val hiddenJson = json.getJSONArray("hidden")
        val hidden = (0 until hiddenJson.length()).map { hiddenJson.getString(it) }
        val rowsJson = json.getJSONArray("pending")
        val rows = (0 until rowsJson.length()).map { i ->
            val o = rowsJson.getJSONObject(i)
            PendingMatchEntity(
                playlistId = o.getString("playlistId"), position = o.getInt("position"), title = o.getString("title"),
                artist = o.getString("artist"), album = o.optString("album").takeIf { it.isNotEmpty() },
                durationMs = o.optLong("durationMs"), attempts = o.optInt("attempts"), failed = o.optBoolean("failed"),
                createdAt = o.optLong("createdAt"),
            )
        }
        hidden to rows
    }.getOrNull()

    // ------------------------------------------------------------------ daily cap

    private fun dailyRemaining(): Int {
        val today = clock.today()
        val used = if (prefs.getString(KEY_DAY, null) == today) prefs.getInt(KEY_SEARCHES, 0) else 0
        return DAILY_SEARCH_CAP - used
    }

    private fun countSearch() {
        val today = clock.today()
        val used = if (prefs.getString(KEY_DAY, null) == today) prefs.getInt(KEY_SEARCHES, 0) else 0
        prefs.edit().putString(KEY_DAY, today).putInt(KEY_SEARCHES, used + 1).apply()
    }

    companion object {
        const val PREFS = "import_prefs"
        const val SOURCE_SPOTIFY = "spotify"
        const val SOURCE_CSV = "csv"
        const val SOURCE_YOUTUBE = "youtube"
        private const val KEY_UNDO_PREFIX = "undo_snapshot_"
        private const val KEY_DAY = "match_day"
        private const val KEY_SEARCHES = "match_searches"
        private const val BATCH = 20
        private const val MAX_ATTEMPTS = 3

        /** 25 searches ≈ 2,500 of the 10,000 free daily units. */
        const val PER_RUN_SEARCHES = 25
        /** Imports never take more than half of the daily allowance (≈ 5,000 units). */
        const val DAILY_SEARCH_CAP = 50
    }
}
