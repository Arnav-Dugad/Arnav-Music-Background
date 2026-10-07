package com.arnav.music.domain

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId

const val DAY = 86_400_000L
const val NOW = 1_760_000_000_000L

fun track(n: Int, artist: String = "Artist ${n % 5}", energy: Float? = (n % 10) / 10f, genres: List<String> = listOf("g${n % 3}")) =
    Track(TrackId.youtube("v$n"), "Song $n", artist, durationMs = 200_000, playbackRef = "v$n", genres = genres, energy = energy)

fun play(t: Track, at: Long, completed: Boolean = true, skipped: Boolean = false) =
    PlayEvent(t.id, t.artistKey, at, if (completed) 200_000 else 20_000, 200_000, completed, skipped)
