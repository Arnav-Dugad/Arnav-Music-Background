package com.arnav.music.domain.quota

/** Approximate YouTube Data API v3 unit costs (documented by Google; tunable via Remote Config). */
object YouTubeCosts {
    const val SEARCH = 100
    const val VIDEOS_LIST = 1
    const val PLAYLIST_ITEMS = 1
    const val CHANNELS_LIST = 1
}

enum class QuotaState { NORMAL, CONSERVE, EXHAUSTED }

/**
 * Local awareness of quota spend. Not authoritative — Google's console is — but lets the app
 * get conservative before hitting a hard 403, and explain itself when it does.
 */
data class QuotaLedger(
    val dayKey: String,
    val unitsUsed: Int = 0,
    val remoteSearches: Int = 0,
    val cacheHits: Int = 0,
    val serverExhausted: Boolean = false,
) {
    fun state(dailyBudget: Int, conserveAt: Float = 0.8f): QuotaState = when {
        serverExhausted || unitsUsed >= dailyBudget -> QuotaState.EXHAUSTED
        unitsUsed >= dailyBudget * conserveAt -> QuotaState.CONSERVE
        else -> QuotaState.NORMAL
    }

    val cacheHitRatio: Float get() = (cacheHits + remoteSearches).let { if (it == 0) 0f else cacheHits.toFloat() / it }

    fun rollover(today: String): QuotaLedger = if (today == dayKey) this else QuotaLedger(today)
}

/**
 * Freshness policy for cached search pages: reuse anything from today; in CONSERVE mode reuse
 * up to a week; when EXHAUSTED reuse anything we have.
 */
object CachePolicy {
    private const val HOUR = 3_600_000L
    fun maxAgeMs(state: QuotaState): Long = when (state) {
        QuotaState.NORMAL -> 24 * HOUR
        QuotaState.CONSERVE -> 7 * 24 * HOUR
        QuotaState.EXHAUSTED -> Long.MAX_VALUE
    }
    fun isFresh(fetchedAt: Long, now: Long, state: QuotaState) = now - fetchedAt <= maxAgeMs(state)
    /** Stale-while-revalidate: show cached instantly, refresh in background only past this age. */
    fun shouldRevalidate(fetchedAt: Long, now: Long, state: QuotaState) = state == QuotaState.NORMAL && now - fetchedAt > 6 * HOUR
}
