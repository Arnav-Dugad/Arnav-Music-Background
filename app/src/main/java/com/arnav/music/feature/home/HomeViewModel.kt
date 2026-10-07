package com.arnav.music.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.repo.HomeSection
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val greeting: String = "",
    val sections: List<HomeSection> = emptyList(),
)

class HomeViewModel(
    private val intelligence: IntelligenceRepository,
    library: LibraryRepository,
    network: NetworkMonitor,
    private val clock: Clock,
    private val settings: com.arnav.music.core.settings.SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(greeting = IntelligenceRepository.greeting(clock.now())))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var rawSections = emptyList<HomeSection>()
    private fun applyLayout() {
        val layout = settings.settings.value.studio
        fun category(key: String) = if (key.startsWith("tm_")) "tm" else key
        val sections = rawSections.filter { category(it.key) !in layout.hiddenHome }
            .sortedBy { layout.homeOrder.indexOf(category(it.key)).let { n -> if (n < 0) Int.MAX_VALUE else n } }
        _state.value = HomeUiState(false, IntelligenceRepository.greeting(clock.now()), sections)
    }
    init {
        viewModelScope.launch { settings.settings.collect { if (rawSections.isNotEmpty()) applyLayout() } }
        viewModelScope.launch {
            // Recompose the home when listening, likes, local files or connectivity change —
            // debounced so playback doesn't constantly reshuffle the page.
            combine(library.eventCount, library.likedIds, library.localTracks, network.isOnline) { a, b, c, d -> listOf(a, b.size, c.size, d) }
                .distinctUntilChanged()
                .debounce(600)
                .collect { refresh() }
        }
        viewModelScope.launch {
            // "Not interested" / "Don't recommend this artist" / "More like this" show up right away.
            intelligence.feedbackVersion.drop(1).collect { refresh() }
        }
    }

    fun refresh() = viewModelScope.launch {
        intelligence.invalidate()
        rawSections = runCatching { intelligence.composeHome() }.getOrDefault(rawSections)
        applyLayout()
    }
}
