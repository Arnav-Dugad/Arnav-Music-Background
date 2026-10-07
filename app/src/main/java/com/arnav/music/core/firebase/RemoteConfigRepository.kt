package com.arnav.music.core.firebase

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** Tunables that can change without an app release. Defaults are safe and zero-cost. */
data class RemoteTunables(
    val aiEnabled: Boolean = true,
    val aiModel: String = "gemini-2.5-flash-lite",
    val aiTemperature: Float = 0.6f,
    val aiMaxOutputTokens: Int = 600,
    val aiMinIntervalMs: Long = 4_000,
    val aiTimeoutMs: Long = 20_000,
    val youtubeDefaultBudget: Int = 10_000,
)

class RemoteConfigRepository(private val gate: FirebaseGate) {
    private val _tunables = MutableStateFlow(RemoteTunables())
    val tunables: StateFlow<RemoteTunables> = _tunables.asStateFlow()

    suspend fun refresh() {
        if (!gate.isAvailable) return
        runCatching {
            val rc = FirebaseRemoteConfig.getInstance()
            rc.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(12 * 3600).build()).await()
            val d = RemoteTunables()
            rc.setDefaultsAsync(
                mapOf(
                    "ai_enabled" to d.aiEnabled, "ai_model" to d.aiModel, "ai_temperature" to d.aiTemperature.toDouble(),
                    "ai_max_output_tokens" to d.aiMaxOutputTokens.toLong(), "ai_min_interval_ms" to d.aiMinIntervalMs,
                    "ai_timeout_ms" to d.aiTimeoutMs, "youtube_default_budget" to d.youtubeDefaultBudget.toLong(),
                ),
            ).await()
            rc.fetchAndActivate().await()
            _tunables.value = RemoteTunables(
                aiEnabled = rc.getBoolean("ai_enabled"),
                aiModel = rc.getString("ai_model").ifBlank { d.aiModel },
                aiTemperature = rc.getDouble("ai_temperature").toFloat().coerceIn(0f, 1.5f),
                aiMaxOutputTokens = rc.getLong("ai_max_output_tokens").toInt().coerceIn(128, 2048),
                aiMinIntervalMs = rc.getLong("ai_min_interval_ms").coerceIn(1_000, 60_000),
                aiTimeoutMs = rc.getLong("ai_timeout_ms").coerceIn(5_000, 60_000),
                youtubeDefaultBudget = rc.getLong("youtube_default_budget").toInt().coerceIn(500, 1_000_000),
            )
        }
    }
}
