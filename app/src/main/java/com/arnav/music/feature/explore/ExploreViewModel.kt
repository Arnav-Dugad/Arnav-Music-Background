package com.arnav.music.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.repo.SearchRepository
import com.arnav.music.core.repo.SearchState
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.domain.provider.SearchFilter
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.search.QueryNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrendingState(val loading: Boolean = true, val tracks: List<Track> = emptyList(), val error: MusicError? = null)

class ExploreViewModel(
    private val search: SearchRepository,
    private val youtube: YouTubeRepository,
    private val library: LibraryRepository,
    private val analytics: Analytics,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(SearchFilter.ALL)
    val recent = search.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _trending = MutableStateFlow(TrendingState())
    val trending: StateFlow<TrendingState> = _trending.asStateFlow()
    private val _paging = MutableStateFlow(false)
    val paging: StateFlow<Boolean> = _paging.asStateFlow()

    /** Remote search only after the user pauses typing; local suggestions are instant. */
    val results: StateFlow<SearchState> = combine(query, filter) { q, f -> q.trim() to f }
        .distinctUntilChanged { a, b -> QueryNormalizer.cacheKey(a.first, a.second.name) == QueryNormalizer.cacheKey(b.first, b.second.name) }
        .debounce { (q, _) -> if (q.isBlank()) 0L else QueryNormalizer.DEBOUNCE_MS }
        .flatMapLatest { (q, f) -> if (q.isBlank()) flowOf(SearchState.Idle) else search.search(q, f, remote = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState.Idle)

    val instant: StateFlow<List<Track>> = query
        .debounce(90)
        .flatMapLatest { q -> kotlinx.coroutines.flow.flow { emit(if (q.isBlank()) emptyList() else search.localMatches(q)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun quotaState(): QuotaState = youtube.quotaState()

    fun loadTrending() = viewModelScope.launch {
        if (_trending.value.tracks.isNotEmpty()) return@launch
        youtube.trending()
            .onSuccess { _trending.value = TrendingState(false, it) }
            .onFailure { _trending.value = TrendingState(false, emptyList(), it as? MusicError ?: MusicError.Unknown("")) }
    }

    fun submit() = viewModelScope.launch {
        val q = query.value
        if (q.isBlank()) return@launch
        search.remember(q)
        analytics.log(Analytics.Event.SEARCH, mapOf("filter" to filter.value.name))
    }

    fun loadMore() = viewModelScope.launch {
        val r = (results.value as? SearchState.Results)?.results ?: return@launch
        if (r.nextPageToken == null || _paging.value) return@launch
        _paging.value = true
        search.nextPage(r, filter.value)
        _paging.value = false
    }

    fun forget(q: String) = viewModelScope.launch { search.forget(q) }
    fun saveYouTubePlaylist(p: com.arnav.music.domain.model.Playlist) = viewModelScope.launch { library.saveYouTubePlaylist(p) }
}
