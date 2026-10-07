package com.arnav.music.domain.recommend

import com.arnav.music.domain.quota.QuotaState
import java.time.Instant
import java.time.ZoneId

/** Background discovery searches spent today (persisted by the app as one short line). */
data class DiscoveryLedger(val day: String = "", val searches: Int = 0, val lastAt: Long = 0L) {
    fun encode(): String = "$day|$searches|$lastAt"

    companion object {
        fun decode(raw: String?): DiscoveryLedger {
            val p = raw?.split('|') ?: return DiscoveryLedger()
            if (p.size != 3) return DiscoveryLedger()
            return DiscoveryLedger(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L)
        }
    }
}

/**
 * The quota rules for "fresh finds". A YouTube search costs 100 of the 10,000 free daily units,
 * so background discovery may spend at most [MAX_PER_DAY] searches a day, at least
 * [MIN_GAP_MS] apart, only while quota is NORMAL and the device is online — and always tries the
 * search cache first (a cached page costs nothing).
 */
object DiscoveryPolicy {
    const val MAX_PER_DAY = 2
    const val MIN_GAP_MS = 3 * 3_600_000L

    fun dayKey(now: Long, zone: ZoneId): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    fun mayQuery(ledger: DiscoveryLedger, quota: QuotaState, online: Boolean, now: Long, zone: ZoneId): Boolean {
        if (quota != QuotaState.NORMAL || !online) return false
        val today = dayKey(now, zone)
        val spent = if (ledger.day == today) ledger.searches else 0
        return spent < MAX_PER_DAY && now - ledger.lastAt >= MIN_GAP_MS
    }

    fun record(ledger: DiscoveryLedger, now: Long, zone: ZoneId): DiscoveryLedger {
        val today = dayKey(now, zone)
        return DiscoveryLedger(today, (if (ledger.day == today) ledger.searches else 0) + 1, now)
    }

    /**
     * Discovery queries, best first, rotating daily so the same few never repeat: artists related
     * to the listener's favourites (from session co-occurrence) they barely play yet, then their
     * own top artists ("<artist> new songs"), then recent searches. Blocked artists are never used.
     */
    fun queries(model: RecModel, max: Int = 4): List<String> {
        val taste = model.taste
        val blocked = model.input.feedback.blockedArtists
        val top = taste.topArtists(8).filter { it !in blocked }
        val related = top.flatMap { a -> model.index.artists.neighbours(a, 5).map { it.key } }
            .filter { it !in blocked && taste.artistAffinity(it) < 0.3 }.distinct()
        val out = ArrayList<String>()
        related.forEach { out += model.artistName(it) }
        top.forEach { out += model.artistName(it) + " new songs" }
        taste.intents.forEach { out += it.display }
        for (a in taste.coldArtists) if (a !in blocked) out += model.artistName(a)
        val unique = out.map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }
        if (unique.isEmpty()) return emptyList()
        val day = (model.now / 86_400_000L).toInt()
        val start = Math.floorMod(day, unique.size)
        return (unique.drop(start) + unique.take(start)).take(max)
    }
}
