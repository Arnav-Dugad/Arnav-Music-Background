package com.arnav.music.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.intelligence.ConstellationGraph
import com.arnav.music.domain.intelligence.Heatmap
import com.arnav.music.domain.intelligence.HeatmapGrid
import com.arnav.music.domain.intelligence.Recap
import com.arnav.music.domain.intelligence.RecapPeriod
import com.arnav.music.domain.intelligence.TasteDna
import com.arnav.music.domain.intelligence.TimeMachineInsight
import com.arnav.music.domain.intelligence.InsightsEngine
import com.arnav.music.domain.intelligence.Milestone
import com.arnav.music.domain.intelligence.Milestones
import com.arnav.music.domain.intelligence.StreakInfo
import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TimelineScale(val label: String) { DAY("Today"), WEEK("Week"), MONTH("Month"), YEAR("Year") }
data class TimelineBucket(val label: String, val minutes: Long, val from: Long, val to: Long)

class InsightsViewModel(private val intelligence: IntelligenceRepository, private val library: LibraryRepository) : ViewModel() {
    private val _dna = MutableStateFlow<TasteDna?>(null)
    val dna: StateFlow<TasteDna?> = _dna.asStateFlow()
    private val _recap = MutableStateFlow<Recap?>(null)
    val recap: StateFlow<Recap?> = _recap.asStateFlow()
    val period = MutableStateFlow(RecapPeriod.WEEK)
    private val _graph = MutableStateFlow<ConstellationGraph?>(null)
    val graph: StateFlow<ConstellationGraph?> = _graph.asStateFlow()
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val artistNames: StateFlow<Map<String, String>> = _names.asStateFlow()
    private val _topTracks = MutableStateFlow<List<Pair<Track, Int>>>(emptyList())
    val topTracks: StateFlow<List<Pair<Track, Int>>> = _topTracks.asStateFlow()
    private val _timeMachine = MutableStateFlow<List<Pair<TimeMachineInsight, List<Track>>>>(emptyList())
    val timeMachine: StateFlow<List<Pair<TimeMachineInsight, List<Track>>>> = _timeMachine.asStateFlow()

    val scale = MutableStateFlow(TimelineScale.WEEK)
    private val _buckets = MutableStateFlow<List<TimelineBucket>>(emptyList())
    val buckets: StateFlow<List<TimelineBucket>> = _buckets.asStateFlow()
    private val _selected = MutableStateFlow<Pair<TimelineBucket, List<Track>>?>(null)
    val selected: StateFlow<Pair<TimelineBucket, List<Track>>?> = _selected.asStateFlow()

    private val _streak = MutableStateFlow<StreakInfo?>(null)
    val streak: StateFlow<StreakInfo?> = _streak.asStateFlow()
    private val _milestones = MutableStateFlow<List<Milestone>>(emptyList())
    /** Every milestone in a fixed order; achieved ones carry the time they were reached. */
    val milestones: StateFlow<List<Milestone>> = _milestones.asStateFlow()

    private val _heatmap = MutableStateFlow<HeatmapGrid?>(null)
    /** Last 53 weeks of listening, one square per local day. */
    val heatmap: StateFlow<HeatmapGrid?> = _heatmap.asStateFlow()

    fun loadMilestones() = viewModelScope.launch {
        val events = library.events(0L)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val (s, m) = withContext(Dispatchers.Default) { Milestones.streak(events, now, zone) to Milestones.compute(events, zone) }
        _streak.value = s
        _milestones.value = m
        _heatmap.value = withContext(Dispatchers.Default) { Heatmap.build(events, LocalDate.now(zone), zone) }
    }

    fun loadDna() = viewModelScope.launch {
        val dna = intelligence.tasteDna()
        _dna.value = dna
        val keys = dna.topArtists.map { it.first } + dna.risingArtists + dna.fadingArtists
        _names.value = keys.associateWith { k -> library.tracksByArtist(k).firstOrNull()?.artist ?: k }
        val now = System.currentTimeMillis()
        _timeMachine.value = InsightsEngine.timeMachine(library.events(now - 400L * DAY), now)
            .map { it to library.tracks(it.trackIds) }.filter { it.second.isNotEmpty() }
        setPeriod(period.value)
    }

    fun setPeriod(p: RecapPeriod) = viewModelScope.launch {
        period.value = p
        val r = intelligence.recap(p)
        _recap.value = r
        val tracks = library.tracks(r.topTracks.map { it.first }).associateBy { it.id }
        _topTracks.value = r.topTracks.mapNotNull { (id, n) -> tracks[id]?.let { it to n } }
        val names = r.topArtists.map { it.first }.associateWith { k -> library.tracksByArtist(k).firstOrNull()?.artist ?: k }
        _names.value = _names.value + names
    }

    fun loadGraph() = viewModelScope.launch { _graph.value = intelligence.constellation() }

    fun setScale(s: TimelineScale) = viewModelScope.launch {
        scale.value = s
        _selected.value = null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets: List<Triple<String, Long, Long>> = when (s) {
            TimelineScale.DAY -> (0 until 24).map { h -> val f = start(today) + h * HOUR; Triple("%02d".format(h), f, f + HOUR) }
            TimelineScale.WEEK -> (6 downTo 0).map { i -> val d = today.minusDays(i.toLong()); Triple(d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, start(d), start(d) + DAY) }
            TimelineScale.MONTH -> (29 downTo 0).map { i -> val d = today.minusDays(i.toLong()); Triple(d.dayOfMonth.toString(), start(d), start(d) + DAY) }
            TimelineScale.YEAR -> (11 downTo 0).map { i ->
                val m = today.withDayOfMonth(1).minusMonths(i.toLong())
                Triple(m.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, start(m), start(m.plusMonths(1)))
            }
        }
        val events = library.eventsBetween(buckets.first().second, buckets.last().third)
        _buckets.value = buckets.map { (label, f, t) -> TimelineBucket(label, events.filter { it.startedAt in f until t }.sumOf { it.listenedMs } / 60_000, f, t) }
    }

    fun select(b: TimelineBucket) = viewModelScope.launch {
        val events: List<PlayEvent> = library.eventsBetween(b.from, b.to - 1)
        val ids: List<TrackId> = events.sortedBy { it.startedAt }.map { it.trackId }.distinct()
        _selected.value = b to library.tracks(ids)
    }

    companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        fun hourLabel(h: Int): String = Instant.ofEpochMilli(0).let { "%d %s".format(if (h % 12 == 0) 12 else h % 12, if (h < 12) "AM" else "PM") }
    }
}
