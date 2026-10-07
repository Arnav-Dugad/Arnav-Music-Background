package com.arnav.music.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.ai.AiUnavailableReason
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.SessionResult
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlin.coroutines.coroutineContext

enum class BuildStep(val label: String) { UNDERSTAND("Understanding your request"), FIND("Finding real, playable songs"), SHAPE("Shaping the energy curve") }
sealed interface AiUi {
    data object Idle : AiUi
    data class Building(val prompt: String, val step: BuildStep) : AiUi
    data class Ready(val prompt: String, val result: SessionResult) : AiUi
    data class Empty(val prompt: String, val result: SessionResult) : AiUi
}
class ArnavAiViewModel(
    private val intelligence: IntelligenceRepository,
    private val ai: AiGateway,
    private val analytics: Analytics,
    settings: SettingsRepository,
    context: android.content.Context,
) : ViewModel() {
    private val _ui = MutableStateFlow<AiUi>(AiUi.Idle)
    val ui: StateFlow<AiUi> = _ui.asStateFlow()
    private val prefs = context.getSharedPreferences("ai_prompt_history", android.content.Context.MODE_PRIVATE)
    private val json = kotlinx.serialization.json.Json
    val history = MutableStateFlow(runCatching { json.decodeFromString<List<String>>(prefs.getString("prompts", "[]") ?: "[]") }.getOrDefault(emptyList()))
    val settings = settings.settings
    private var buildJob: Job? = null
    private var generation = 0L
    private var previous: AiUi.Ready? = null
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    fun mode(): AiUnavailableReason? = ai.availability()
    fun build(prompt: String) {
        val p = prompt.trim().take(300)
        if (p.isBlank()) return
        buildJob?.cancel()
        val token = ++generation
        (_ui.value as? AiUi.Ready)?.let { previous = it; _canUndo.value = true }
        analytics.log(Analytics.Event.AI_INVOKED)
        history.value = (listOf(p) + history.value.filter { it != p }).take(20)
        prefs.edit().putString("prompts", json.encodeToString(history.value)).apply()
        _ui.value = AiUi.Building(p, BuildStep.UNDERSTAND)
        buildJob = viewModelScope.launch {
            val result = try {
                intelligence.buildSession(p) { phase ->
                    if (token == generation) _ui.value = AiUi.Building(p, BuildStep.entries[phase.coerceIn(0, 2)])
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            coroutineContext.ensureActive()
            if (token != generation) return@launch
            _ui.value = when {
                result == null -> previous ?: AiUi.Idle
                result.session.tracks.isEmpty() -> AiUi.Empty(p, result)
                else -> AiUi.Ready(p, result)
            }
        }
    }
    fun cancel() { generation++; buildJob?.cancel(); _ui.value = previous ?: AiUi.Idle }
    fun undo() {
        val old = previous ?: return
        generation++; buildJob?.cancel()
        val current = _ui.value as? AiUi.Ready
        _ui.value = old; previous = current; _canUndo.value = current != null
    }
    fun reset() { generation++; buildJob?.cancel(); previous = null; _canUndo.value = false; _ui.value = AiUi.Idle }
    fun explain(reason: com.arnav.music.domain.intelligence.Reason, track: com.arnav.music.domain.model.Track) = intelligence.explain(reason, track)
}
