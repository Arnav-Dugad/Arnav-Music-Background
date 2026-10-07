package com.arnav.music.feature.insights

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.stats.ClockPlay
import com.arnav.music.domain.stats.Discovery
import com.arnav.music.domain.stats.DiscoveryReport
import com.arnav.music.domain.stats.GenreFallback
import com.arnav.music.domain.stats.GenreSource
import com.arnav.music.domain.stats.HistoryRow
import com.arnav.music.domain.stats.ListenRecord
import com.arnav.music.domain.stats.ListeningClock
import com.arnav.music.domain.stats.SkipMark
import com.arnav.music.domain.stats.SkipSpot
import com.arnav.music.domain.stats.SkipSpots
import com.arnav.music.domain.stats.StatsCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Why "Trim it here" is or isn't offered for a skip spot. */
enum class TrimAvailability { AVAILABLE, NEEDS_SMART_TRANSITIONS, NOT_ANALYSED, TOO_EARLY, NOT_LOCAL }

data class SkipSpotItem(val spot: SkipSpot, val track: Track?, val trim: TrimAvailability)

sealed interface CsvExportState {
    data object Idle : CsvExportState
    data object Working : CsvExportState
    data class Done(val rows: Int) : CsvExportState
    data class Failed(val message: String) : CsvExportState
}

/**
 * Listening stats: genre clock, discovery score, songs skipped at the same second and CSV export.
 * Everything is computed from the on-device history; the CSV is written only to the file the user picks.
 */
class ListeningStatsViewModel(
    private val context: Context,
    private val db: ArnavDatabase,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _clock = MutableStateFlow<ListeningClock?>(null)
    val clock: StateFlow<ListeningClock?> = _clock.asStateFlow()
    private val _discovery = MutableStateFlow<DiscoveryReport?>(null)
    val discovery: StateFlow<DiscoveryReport?> = _discovery.asStateFlow()
    private val _skipSpots = MutableStateFlow<List<SkipSpotItem>>(emptyList())
    val skipSpots: StateFlow<List<SkipSpotItem>> = _skipSpots.asStateFlow()
    private val _export = MutableStateFlow<CsvExportState>(CsvExportState.Idle)
    val export: StateFlow<CsvExportState> = _export.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun load() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val events = runCatching { db.events().since(0L) }.getOrDefault(emptyList())
        val known = runCatching { db.tracks().all() }.getOrDefault(emptyList())
        _discovery.value = withContext(Dispatchers.Default) {
            Discovery.report(events.map { ListenRecord(it.trackId, it.artistKey, it.startedAt, it.listenedMs) }, now)
        }
        _clock.value = withContext(Dispatchers.Default) {
            val recent = events.filter { it.startedAt >= now - CLOCK_DAYS * DAY_MS }
            val sources = known.map { GenreSource(it.id, it.artistKey, if (it.genres.isBlank()) emptyList() else it.genres.split('|')) }
            val seen = sources.mapTo(HashSet<String>()) { it.trackId }
            val extra = recent.filter { seen.add(it.trackId) }.map { GenreSource(it.trackId, it.artistKey, emptyList()) }
            val genres = GenreFallback.resolve(sources + extra)
            ListeningClock.build(recent.map { ClockPlay(it.startedAt, it.listenedMs, genres[it.trackId].orEmpty()) }, zone)
        }
        loadSkipSpots()
        _loaded.value = true
    }

    private suspend fun loadSkipSpots() {
        val marks = runCatching { db.skipMarks().active() }.getOrDefault(emptyList())
        val spots = withContext(Dispatchers.Default) {
            SkipSpots.detect(marks.map { SkipMark(it.trackId, it.positionMs, it.durationMs, it.skippedAt) })
        }
        if (spots.isEmpty()) { _skipSpots.value = emptyList(); return }
        val tracks = runCatching { library.tracks(spots.map { TrackId(it.trackId) }) }.getOrDefault(emptyList()).associateBy { it.id.value }
        val smart = settings.settings.value.smartTransitions
        _skipSpots.value = spots.map { spot ->
            val id = TrackId(spot.trackId)
            val trim = when {
                id.source != SourceType.LOCAL -> TrimAvailability.NOT_LOCAL
                !spot.canTrim -> TrimAvailability.TOO_EARLY
                runCatching { db.audioFeatures().get(spot.trackId) }.getOrNull()?.ok != true -> TrimAvailability.NOT_ANALYSED
                !smart -> TrimAvailability.NEEDS_SMART_TRANSITIONS
                else -> TrimAvailability.AVAILABLE
            }
            SkipSpotItem(spot, tracks[spot.trackId], trim)
        }
    }

    /** "Trim it here": moves the song's outro mark to the usual skip point, so playback fades out there. */
    fun trim(item: SkipSpotItem) = viewModelScope.launch {
        val id = item.spot.trackId
        val updated = runCatching { db.audioFeatures().setOutro(id, item.spot.positionMs) }.getOrDefault(0)
        val title = item.track?.title ?: "This song"
        if (updated > 0) {
            runCatching { db.skipMarks().dismiss(id) }
            _skipSpots.value = _skipSpots.value.filterNot { it.spot.trackId == id }
            _notice.value = "Next time, $title fades out at ${SkipSpots.formatPosition(item.spot.positionMs)} and moves on."
        } else {
            _notice.value = "Couldn't trim $title — it hasn't been analysed on this device yet."
        }
    }

    fun dismiss(item: SkipSpotItem) = viewModelScope.launch {
        runCatching { db.skipMarks().dismiss(item.spot.trackId) }
        _skipSpots.value = _skipSpots.value.filterNot { it.spot.trackId == item.spot.trackId }
    }

    fun clearNotice() { _notice.value = null }

    fun suggestedFileName(): String = "arnav-music-listening-${LocalDate.now()}.csv"

    /** Writes the whole play history to [uri] (a document the user just created with the system file picker). */
    fun exportCsv(uri: Uri) = viewModelScope.launch {
        _export.value = CsvExportState.Working
        _export.value = try {
            val rows = withContext(Dispatchers.IO) {
                val events = db.events().since(0L)
                val marks = db.skipMarks().all().associateBy { it.trackId to it.playStartedAt }
                val tracks = library.tracks(events.map { it.trackId }.distinct().map { TrackId(it) }).associateBy { it.id.value }
                val history = events.map { e ->
                    val t = tracks[e.trackId]
                    val mark = marks[e.trackId to e.startedAt]
                    HistoryRow(
                        startedAt = e.startedAt,
                        title = t?.title ?: e.trackId,
                        artist = t?.artist ?: e.artistKey,
                        album = t?.album,
                        source = e.source,
                        listenedMs = e.listenedMs,
                        durationMs = e.durationMs,
                        completed = e.completed,
                        skipped = e.skipped || mark != null,
                        skipPositionMs = mark?.positionMs,
                    )
                }
                val resolver = context.contentResolver
                val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri)
                    ?: throw java.io.IOException("No output stream")
                stream.bufferedWriter(Charsets.UTF_8).use { w -> StatsCsv.writeHistory(history, ZoneId.systemDefault(), w) }
                history.size
            }
            CsvExportState.Done(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CsvExportState.Failed("Couldn't write the file. Try another location.")
        }
    }

    fun exportUnavailable() { _export.value = CsvExportState.Failed("No file picker is available on this device.") }

    companion object {
        private const val DAY_MS = 86_400_000L
        const val CLOCK_DAYS = 90
    }
}
