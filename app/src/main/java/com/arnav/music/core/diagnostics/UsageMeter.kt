package com.arnav.music.core.diagnostics

import android.content.Context
import com.arnav.music.core.common.Clock
import com.arnav.music.domain.quota.QuotaLedger
import com.arnav.music.domain.quota.QuotaState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Approximate per-day usage counters shown in Settings → Usage & Quotas. Not billing-authoritative. */
data class UsageSnapshot(
    val day: String = "",
    val youtube: QuotaLedger = QuotaLedger(""),
    val aiRequests: Int = 0,
    val aiCacheHits: Int = 0,
    val aiFallbacks: Int = 0,
    val firestoreReads: Int = 0,
    val firestoreWrites: Int = 0,
    val httpRequests: Int = 0,
) {
    val aiCacheRatio: Float get() = (aiRequests + aiCacheHits).let { if (it == 0) 0f else aiCacheHits.toFloat() / it }
}

class UsageMeter(context: Context, private val clock: Clock) {
    private val prefs = context.getSharedPreferences("usage_meter", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<UsageSnapshot> = _state.asStateFlow()

    private fun load(): UsageSnapshot {
        val today = clock.today()
        val day = prefs.getString("day", today) ?: today
        if (day != today) return UsageSnapshot(today, QuotaLedger(today))
        return UsageSnapshot(
            day = day,
            youtube = QuotaLedger(
                day, prefs.getInt("yt_units", 0), prefs.getInt("yt_searches", 0),
                prefs.getInt("yt_cache_hits", 0), prefs.getBoolean("yt_exhausted", false),
            ),
            aiRequests = prefs.getInt("ai_requests", 0),
            aiCacheHits = prefs.getInt("ai_cache", 0),
            aiFallbacks = prefs.getInt("ai_fallbacks", 0),
            firestoreReads = prefs.getInt("fs_reads", 0),
            firestoreWrites = prefs.getInt("fs_writes", 0),
            httpRequests = prefs.getInt("http", 0),
        )
    }

    @Synchronized
    private fun mutate(block: (UsageSnapshot) -> UsageSnapshot) {
        val today = clock.today()
        _state.update { cur ->
            val base = if (cur.day != today) UsageSnapshot(today, QuotaLedger(today)) else cur
            block(base)
        }
        val s = _state.value
        prefs.edit()
            .putString("day", s.day)
            .putInt("yt_units", s.youtube.unitsUsed)
            .putInt("yt_searches", s.youtube.remoteSearches)
            .putInt("yt_cache_hits", s.youtube.cacheHits)
            .putBoolean("yt_exhausted", s.youtube.serverExhausted)
            .putInt("ai_requests", s.aiRequests)
            .putInt("ai_cache", s.aiCacheHits)
            .putInt("ai_fallbacks", s.aiFallbacks)
            .putInt("fs_reads", s.firestoreReads)
            .putInt("fs_writes", s.firestoreWrites)
            .putInt("http", s.httpRequests)
            .apply()
    }

    fun youtubeCall(units: Int, isSearch: Boolean) = mutate {
        it.copy(youtube = it.youtube.copy(unitsUsed = it.youtube.unitsUsed + units, remoteSearches = it.youtube.remoteSearches + if (isSearch) 1 else 0), httpRequests = it.httpRequests + 1)
    }
    fun youtubeCacheHit() = mutate { it.copy(youtube = it.youtube.copy(cacheHits = it.youtube.cacheHits + 1)) }
    fun youtubeExhausted() = mutate { it.copy(youtube = it.youtube.copy(serverExhausted = true)) }
    fun aiRequest() = mutate { it.copy(aiRequests = it.aiRequests + 1) }
    fun aiCacheHit() = mutate { it.copy(aiCacheHits = it.aiCacheHits + 1) }
    fun aiFallback() = mutate { it.copy(aiFallbacks = it.aiFallbacks + 1) }
    fun firestore(reads: Int = 0, writes: Int = 0) = mutate { it.copy(firestoreReads = it.firestoreReads + reads, firestoreWrites = it.firestoreWrites + writes) }

    fun youtubeState(budget: Int): QuotaState = state.value.let { s ->
        if (s.day != clock.today()) QuotaState.NORMAL else s.youtube.state(budget)
    }
}
