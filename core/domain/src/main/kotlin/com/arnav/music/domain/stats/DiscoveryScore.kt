package com.arnav.music.domain.stats

import kotlin.math.roundToInt

/** A listen reduced to what the discovery score needs. */
data class ListenRecord(val trackId: String, val artistKey: String, val startedAt: Long, val listenedMs: Long)

/**
 * How much of one window's listening was new to you. Every listen falls in exactly one bucket:
 * - new artist: an artist you had never played before the window started,
 * - new song: a song never played before, by an artist you already knew,
 * - familiar: a song you had played before the window.
 */
data class DiscoveryWindow(
    val from: Long,
    val to: Long,
    val totalMs: Long,
    val newArtistMs: Long,
    val newSongMs: Long,
    val familiarMs: Long,
    val plays: Int,
    /** Distinct artists heard for the first time. */
    val newArtists: Int,
    /** Distinct songs heard for the first time by artists you already knew. */
    val newSongs: Int,
    /** Distinct songs you had played before. */
    val familiarSongs: Int,
) {
    val isEmpty: Boolean get() = totalMs <= 0L
    /** Share of listening time that was new (new songs + new artists), 0..1. */
    val newShare: Float get() = if (totalMs <= 0) 0f else (newArtistMs + newSongMs).toFloat() / totalMs
    val percentNew: Int get() = (newShare * 100).roundToInt()
}

data class DiscoveryReport(val thisWeek: DiscoveryWindow, val lastWeek: DiscoveryWindow) {
    /** Percentage-point change from last week; null when last week had no listening. */
    val deltaPoints: Int? get() = if (lastWeek.isEmpty || thisWeek.isEmpty) null else thisWeek.percentNew - lastWeek.percentNew
}

object Discovery {
    private const val DAY_MS = 86_400_000L

    /** Scores [from, to) against everything played before [from]. [history] may be in any order. */
    fun window(history: List<ListenRecord>, from: Long, to: Long): DiscoveryWindow {
        val knownTracks = HashSet<String>()
        val knownArtists = HashSet<String>()
        for (r in history) if (r.startedAt < from) { knownTracks += r.trackId; knownArtists += r.artistKey }
        var total = 0L; var newArtistMs = 0L; var newSongMs = 0L; var familiarMs = 0L; var plays = 0
        val newArtists = HashSet<String>(); val newSongs = HashSet<String>(); val familiar = HashSet<String>()
        for (r in history) {
            if (r.startedAt < from || r.startedAt >= to || r.listenedMs <= 0) continue
            plays++
            total += r.listenedMs
            when {
                r.trackId in knownTracks -> { familiarMs += r.listenedMs; familiar += r.trackId }
                r.artistKey.isNotBlank() && r.artistKey !in knownArtists -> { newArtistMs += r.listenedMs; newArtists += r.artistKey }
                else -> { newSongMs += r.listenedMs; newSongs += r.trackId }
            }
        }
        return DiscoveryWindow(from, to, total, newArtistMs, newSongMs, familiarMs, plays, newArtists.size, newSongs.size, familiar.size)
    }

    /** The last [days] days up to [now], compared with the [days] days before that. */
    fun report(history: List<ListenRecord>, now: Long, days: Int = 7): DiscoveryReport {
        val span = days * DAY_MS
        val thisFrom = now - span
        return DiscoveryReport(
            thisWeek = window(history, thisFrom, now + 1),
            lastWeek = window(history, thisFrom - span, thisFrom),
        )
    }
}
