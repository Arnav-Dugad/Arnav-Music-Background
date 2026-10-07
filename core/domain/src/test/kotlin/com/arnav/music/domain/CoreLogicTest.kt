package com.arnav.music.domain

import com.arnav.music.domain.ai.AiJson
import com.arnav.music.domain.color.ArtworkCorrector
import com.arnav.music.domain.color.ColorMath
import com.arnav.music.domain.color.SurfaceMode
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.intelligence.EnergyCurve
import com.arnav.music.domain.model.ArtistKey
import com.arnav.music.domain.quota.CachePolicy
import com.arnav.music.domain.quota.QuotaLedger
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.domain.queue.QueueState
import com.arnav.music.domain.search.QueryNormalizer
import com.arnav.music.domain.sync.Backoff
import com.arnav.music.domain.sync.SyncMerge
import com.arnav.music.domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CoreLogicTest {
    @Test fun `query normalisation collapses equivalent queries`() {
        assertEquals(QueryNormalizer.cacheKey("  Daft PUNK!! "), QueryNormalizer.cacheKey("daft punk"))
        assertEquals(QueryNormalizer.cacheKey("Beyoncé official video"), QueryNormalizer.cacheKey("beyonce"))
        assertFalse(QueryNormalizer.isRemoteWorthy("a"))
        assertFalse(QueryNormalizer.isRemoteWorthy("!!"))
        assertTrue(QueryNormalizer.isRemoteWorthy("ab"))
        assertTrue(QueryNormalizer.matchScore("daft", "Daft Punk") > QueryNormalizer.matchScore("punk", "Daft Punk"))
    }

    @Test fun `artist key strips topic, vevo and features`() {
        assertEquals(ArtistKey.of("Daft Punk - Topic"), ArtistKey.of("daft punk"))
        assertEquals(ArtistKey.of("TaylorSwiftVEVO"), ArtistKey.of("Taylor Swift"))
        assertEquals(ArtistKey.of("Drake feat. Rihanna"), ArtistKey.of("Drake"))
        assertEquals("daftpunk", ArtistKey.of("Daft Punk"))
        assertEquals(ArtistKey.of("Drake (ft. Rihanna)"), ArtistKey.of("Drake"))
    }

    @Test fun `sync merge last write wins and tombstones`() {
        val local = listOf<SyncRecord<String>>(
            SyncRecord("a", "local-new", 200, dirty = true),
            SyncRecord("b", "local-old", 100, dirty = true),
            SyncRecord("c", "clean", 100),
            SyncRecord("d", null, 100, deleted = true, dirty = true),
        )
        val remote = listOf<SyncRecord<String>>(
            SyncRecord("a", "remote-old", 150),
            SyncRecord("b", "remote-new", 180),
            SyncRecord("c", "remote-newer", 120),
            SyncRecord("d", "remote", 100),
            SyncRecord("e", "remote-only", 50),
            SyncRecord("e", "remote-only-newer", 60),
        )
        val plan = SyncMerge.plan(local, remote)
        assertEquals(setOf("a", "d"), plan.pushRemote.map { it.id }.toSet())
        assertEquals(setOf("b", "c", "e"), plan.applyLocally.map { it.id }.toSet())
        assertEquals("remote-only-newer", plan.applyLocally.first { it.id == "e" }.value)
        assertEquals(listOf("b"), plan.discardedLocal)
    }

    @Test fun `backoff grows and caps`() {
        assertEquals(1000L, Backoff.delayMs(0) { 1.0 })
        assertEquals(8000L, Backoff.delayMs(3) { 1.0 })
        assertEquals(300_000L, Backoff.delayMs(30) { 1.0 })
        assertEquals(500L, Backoff.delayMs(0) { 0.0 })
    }

    @Test fun `artwork palette always readable`() {
        val samples = listOf(0xFF000000, 0xFFFFFFFF, 0xFFFFFF00, 0xFF0000FF, 0xFF808080, 0xFF102030).map { it.toInt() }
        for (mode in SurfaceMode.entries) for (d in samples) for (s in samples) {
            val p = ArtworkCorrector.correct(d, s, null, mode)
            assertTrue("text $mode ${d.toString(16)}", ColorMath.contrast(p.onBackdrop, p.backdrop) >= 7.0)
            assertTrue("muted $mode", ColorMath.contrast(p.onBackdropMuted, p.backdrop) >= 4.5)
            assertTrue("accent $mode", ColorMath.contrast(p.accent, p.backdrop) >= 3.0)
        }
        val oled = ArtworkCorrector.correct(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), null, SurfaceMode.OLED)
        assertTrue(ColorMath.luminance(oled.backdrop) < 0.01)
    }

    @Test fun `hsl round trip`() {
        val c = ColorMath.argb(200, 100, 50)
        val h = ColorMath.toHsl(c)
        val back = ColorMath.fromHsl(h[0], h[1], h[2])
        assertTrue(kotlin.math.abs(ColorMath.red(back) - 200) <= 2)
        assertTrue(ColorMath.temperature(c) > 0.5f)
        assertTrue(ColorMath.temperature(ColorMath.argb(30, 60, 220)) < 0.5f)
    }

    @Test fun `queue operations keep current track stable`() {
        val ts = (1..5).map { track(it) }
        var q = QueueState().replace(ts, 1)
        assertEquals(ts[1], q.current!!.track)
        q = q.move(3, 0)
        assertEquals(ts[1], q.current!!.track)
        q = q.playNext(listOf(track(99)))
        assertEquals("Song 99", q.upNext.first().track.title)
        q = q.removeAt(q.currentIndex)
        assertNotNull(q.current)
        val shuffled = q.shuffle(42)
        assertEquals(q.current, shuffled.current)
        val restored = shuffled.unshuffle()
        assertEquals(q.current, restored.current)
        assertEquals(q.items.size, restored.items.size)
        assertEquals(-1, QueueState().replace(emptyList()).currentIndex)
        val swapped = q.replaceAt(q.currentIndex, track(500))
        assertEquals("Song 500", swapped.current!!.track.title)
        assertEquals(q.current!!.uid, swapped.current!!.uid)
        assertEquals(QueueState().move(0, 1), QueueState())
    }

    @Test fun `quota state and cache policy`() {
        val l = QuotaLedger("2026-10-06", unitsUsed = 8500)
        assertEquals(QuotaState.CONSERVE, l.state(10_000))
        assertEquals(QuotaState.EXHAUSTED, l.copy(serverExhausted = true).state(10_000))
        assertEquals(0, l.rollover("2026-10-07").unitsUsed)
        assertTrue(CachePolicy.isFresh(0, 30L * 86_400_000, QuotaState.EXHAUSTED))
        assertFalse(CachePolicy.isFresh(0, 2L * 86_400_000, QuotaState.NORMAL))
    }

    @Test fun `ai json parsing is tolerant`() {
        val raw = "Sure! Here you go:\n```json\n{\"title\":\"Night {drive}\",\"durationMinutes\":999,\"energyCurve\":\"rising\",\"searchQueries\":[\"synthwave\"],\"unknown\":1}\n```"
        val r = AiJson.parseSession(raw).getOrThrow().toConstraints()
        assertEquals("Night {drive}", r.title)
        assertEquals(240, r.durationMinutes)
        assertEquals(EnergyCurve.RISING, r.energyCurve)
        assertTrue(AiJson.parseSession("not json").isFailure)
        assertTrue(AiJson.parseSession("{\"title\": \"x\"}").isFailure)
        assertNull(AiJson.extractObject("{ unterminated"))
    }

    @Test fun `formatters`() {
        assertEquals("3:05", Formatters.duration(185_000))
        assertEquals("1:01:01", Formatters.duration(3_661_000))
        assertEquals(253_000L, Formatters.parseIsoDuration("PT4M13S"))
        assertEquals(3_600_000L, Formatters.parseIsoDuration("PT1H"))
        assertNull(Formatters.parseIsoDuration("garbage"))
        assertEquals("1.2M", Formatters.compactCount(1_200_000))
        assertEquals("Daft Punk" to "Get Lucky", Formatters.splitYouTubeTitle("Daft Punk - Get Lucky (Official Video)", "DaftPunkVEVO"))
        assertEquals("Adele" to "Hello", Formatters.splitYouTubeTitle("Hello", "Adele - Topic"))
        assertEquals("Aditya Music" to "Narayanamma", Formatters.splitYouTubeTitle("Narayanamma Lyric Video I Aadarsha Kutumbam I Venkatesh, Shriya", "Aditya Music"))
        assertEquals("T-Series" to "Butta Bomma", Formatters.splitYouTubeTitle("Butta Bomma Full Video Song | Ala Vaikunthapurramuloo", "T-Series"))
        assertEquals("Sid Sriram" to "Inkem Inkem", Formatters.splitYouTubeTitle("Sid Sriram - Inkem Inkem (Lyrical Video)", "Sid Sriram"))
        assertEquals("Imagine Dragons" to "Believer", Formatters.splitYouTubeTitle("Imagine Dragons - Believer (Official Music Video)", "ImagineDragonsVEVO"))
        assertEquals("Backstreet Boys" to "I Want It That Way", Formatters.splitYouTubeTitle("Backstreet Boys - I Want It That Way (Official HD Video)", "Backstreet Boys"))
        assertEquals("Madonna" to "Music", Formatters.splitYouTubeTitle("Madonna - Music", "Madonna"))
        assertEquals("6.1 MB", Formatters.bytes(6_075_202))
        assertEquals("950 B", Formatters.bytes(950))
    }
}
