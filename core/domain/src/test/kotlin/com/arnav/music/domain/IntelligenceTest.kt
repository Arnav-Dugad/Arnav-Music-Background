package com.arnav.music.domain

import com.arnav.music.domain.intelligence.EnergyCurve
import com.arnav.music.domain.intelligence.LocalIntentEngine
import com.arnav.music.domain.intelligence.Recommender
import com.arnav.music.domain.intelligence.SessionBuilder
import com.arnav.music.domain.intelligence.SmartPlaylist
import com.arnav.music.domain.intelligence.SmartPlaylistEngine
import com.arnav.music.domain.intelligence.TasteProfileBuilder
import com.arnav.music.domain.intelligence.InsightsEngine
import com.arnav.music.domain.intelligence.RecapPeriod
import com.arnav.music.domain.intelligence.ConstellationBuilder
import com.arnav.music.domain.model.Mood
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class IntelligenceTest {
    private val tracks = (1..60).map { track(it) }
    private val byId = tracks.associateBy { it.id }

    @Test fun `profile favours artists listened to recently and fully`() {
        val loved = tracks.filter { it.artist == "Artist 1" }
        val skipped = tracks.filter { it.artist == "Artist 2" }
        val events = loved.flatMap { t -> (0..3).map { play(t, NOW - it * DAY) } } +
            skipped.map { play(it, NOW - DAY, completed = false, skipped = true) }
        val p = TasteProfileBuilder.build(events, byId, emptySet(), NOW, ZoneOffset.UTC)
        assertEquals(1f, p.artistAffinity["artist1"]!!, 0.001f)
        assertTrue(p.artistAffinity["artist2"] == null)
        assertTrue(p.skipRate > 0f)
    }

    @Test fun `recommender ranks affine artist above unknown and penalises just-played`() {
        val events = tracks.filter { it.artist == "Artist 3" }.map { play(it, NOW - 3 * DAY) }
        val p = TasteProfileBuilder.build(events, byId, emptySet(), NOW, ZoneOffset.UTC)
        val ranked = Recommender().rank(tracks, p, NOW, discovery = 0.1f)
        assertEquals("Artist 3", ranked.first().track.artist)
        val justPlayed = tracks.first { it.artist == "Artist 3" }
        val p2 = TasteProfileBuilder.build(events + play(justPlayed, NOW - 60_000), byId, emptySet(), NOW, ZoneOffset.UTC)
        val ranked2 = Recommender().rank(tracks, p2, NOW, discovery = 0.1f)
        assertTrue(ranked2.indexOfFirst { it.track.id == justPlayed.id } > 0)
    }

    @Test fun `rank deduplicates and excludes`() {
        val ranked = Recommender().rank(tracks + tracks, com.arnav.music.domain.intelligence.TasteProfile.Empty, NOW, exclude = setOf(tracks[0].id))
        assertEquals(tracks.size - 1, ranked.size)
    }

    @Test fun `intent engine parses duration, mood, negation and curve`() {
        val c = LocalIntentEngine.interpret("45 minutes of energetic music for coding but not aggressive, gradually increase in energy, a few surprises")
        assertEquals(45, c.durationMinutes)
        assertTrue("energetic" in c.moods)
        assertTrue("focus" in c.moods)
        assertTrue("aggressive" in c.avoidMoods)
        assertFalse("aggressive" in c.moods)
        assertEquals(EnergyCurve.RISING, c.energyCurve)
        assertEquals("coding", c.context)
        assertTrue(c.searchQueries.isNotEmpty())
    }

    @Test fun `intent engine handles hours, rediscovery and similar-to`() {
        assertEquals(90, LocalIntentEngine.interpret("an hour and a half... no, 1.5 hours of chill").durationMinutes)
        val r = LocalIntentEngine.interpret("Rediscover music I haven't played recently")
        assertTrue(r.rediscover)
        val s = LocalIntentEngine.interpret("songs similar to Daft Punk but calmer")
        assertEquals("Daft Punk but calmer", s.seedArtists.first().let { it })
            .let { } // seed captured verbatim; queries still searchable
        assertTrue(s.energyTarget < 0.6f)
    }

    @Test fun `upbeat maps to upbeat mood`() {
        val c = LocalIntentEngine.interpret("Give me something upbeat")
        assertTrue(c.moodSet.contains(Mood.UPBEAT))
    }

    @Test fun `session builder fills duration with diverse artists`() {
        val events = tracks.take(30).map { play(it, NOW - 5 * DAY) }
        val p = TasteProfileBuilder.build(events, byId, emptySet(), NOW, ZoneOffset.UTC)
        val c = LocalIntentEngine.interpret("30 minutes of focus music")
        val s = SessionBuilder().build(c, tracks, p, emptySet(), NOW)
        assertTrue(s.totalMs >= 30 * 60_000L)
        assertTrue(s.tracks.zipWithNext().none { (a, b) -> a.artistKey == b.artistKey })
        assertEquals(s.tracks.size, s.tracks.map { it.id }.toSet().size)
    }

    @Test fun `session builder is deterministic and handles empty input`() {
        val c = LocalIntentEngine.interpret("calm")
        val p = com.arnav.music.domain.intelligence.TasteProfile.Empty
        val a = SessionBuilder().build(c, tracks, p, emptySet(), NOW)
        val b = SessionBuilder().build(c, tracks, p, emptySet(), NOW)
        assertEquals(a.tracks, b.tracks)
        assertTrue(SessionBuilder().build(c, emptyList(), p, emptySet(), NOW).tracks.isEmpty())
    }

    @Test fun `rising curve increases energy`() {
        val c = LocalIntentEngine.interpret("build up energy").copy(energyCurve = EnergyCurve.RISING)
        assertTrue(SessionBuilder.energyAt(c, 0f) < SessionBuilder.energyAt(c, 1f))
    }

    @Test fun `smart playlists`() {
        val t = tracks[0]
        val events = listOf(play(t, NOW - 60 * DAY), play(t, NOW - 59 * DAY), play(t, NOW - 58 * DAY), play(t, NOW - 57 * DAY)) +
            listOf(play(tracks[1], NOW - DAY), play(tracks[1], NOW - 2 * DAY))
        val forgotten = SmartPlaylistEngine.compute(SmartPlaylist.FORGOTTEN_FAVORITES, events, emptySet(), NOW, ZoneOffset.UTC)
        assertEquals(listOf(t.id), forgotten)
        val heavy = SmartPlaylistEngine.compute(SmartPlaylist.HEAVY_ROTATION, events, emptySet(), NOW, ZoneOffset.UTC)
        assertEquals(listOf(tracks[1].id), heavy)
        assertTrue(SmartPlaylistEngine.compute(SmartPlaylist.MOST_REPLAYED, emptyList(), emptySet(), NOW).isEmpty())
    }

    @Test fun `recap only counts its period`() {
        val events = listOf(play(tracks[0], NOW - 2 * DAY), play(tracks[1], NOW - 40 * DAY))
        val week = InsightsEngine.recap(RecapPeriod.WEEK, events, NOW, ZoneOffset.UTC)
        assertEquals(3L, week.minutesListened)
        assertEquals(100, week.discoveryPercent)
        assertTrue(InsightsEngine.recap(RecapPeriod.WEEK, emptyList(), NOW).isEmpty)
    }

    @Test fun `constellation is deterministic`() {
        val events = tracks.mapIndexed { i, t -> play(t, NOW - i * 10 * 60_000L) }
        val a = ConstellationBuilder.build(events, emptyMap())
        val b = ConstellationBuilder.build(events, emptyMap())
        assertEquals(a.nodes.map { it.x }, b.nodes.map { it.x })
        assertTrue(a.edges.isNotEmpty())
    }
}
