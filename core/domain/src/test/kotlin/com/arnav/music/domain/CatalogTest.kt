package com.arnav.music.domain

import com.arnav.music.domain.catalog.TrackClassifier
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.model.MediaVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    @Test fun `mixes, mashups and mood compilations are not singles`() {
        listOf(
            "Music Mix 2026 | Party Club Dance 2026 | Best Remixes",
            "Gym Motivation Music",
            "Remixes & Mashups of Popular Songs 2026",
            "2026 Top Hits Clean ♫ Trending Music",
            "Pop Party Hits Mix #04 | Early 2000s",
            "Arijit Singh Jukebox | Best of Arijit",
            "Lofi Music for Studying 1 Hour",
            "Nonstop Bollywood Dance Songs",
        ).forEach { assertTrue(it, TrackClassifier.isCompilation(it, 200_000)) }
        assertTrue(TrackClassifier.isCompilation("Some Song", 45 * 60_000L))
    }

    @Test fun `real singles pass, including remixes`() {
        listOf("Officially Blind (Remix)", "Starboy", "One Dance ft. Wizkid & Kyla", "Blinding Lights", "Narayanamma", "Mixed Feelings")
            .forEach { assertFalse(it, TrackClassifier.isCompilation(it, 230_000)) }
        assertFalse(TrackClassifier.isSingle("Intro", 20_000))
    }

    @Test fun `variant detection mirrors song vs video`() {
        assertEquals(MediaVariant.SONG, TrackClassifier.variant("The Weeknd - Topic", "Starboy"))
        assertEquals(MediaVariant.VIDEO, TrackClassifier.variant("TheWeekndVEVO", "Starboy"))
        assertEquals(MediaVariant.VIDEO, TrackClassifier.variant("Aditya Music", "Narayanamma Lyric Video I Aadarsha Kutumbam"))
        assertEquals(MediaVariant.SONG, TrackClassifier.variant("Label", "Song Name (Official Audio)"))
        assertNull(TrackClassifier.variant("Someone", "Song Name"))
        assertTrue(TrackClassifier.titleSimilarity("Blinding Lights", "The Weeknd - Blinding Lights (Official Video)") >= 0.99f)
    }

    @Test fun `label titles yield album and credits`() {
        val p = Formatters.parseYouTubeTitle("Samajavaragamana Lyrical | Ala Vaikunthapurramuloo Movie Songs | Allu Arjun, Pooja Hegde", "Aditya Music")
        assertEquals("Samajavaragamana", p.title)
        assertEquals("Ala Vaikunthapurramuloo", p.album)
        assertEquals("Allu Arjun, Pooja Hegde", p.credits)
        val q = Formatters.parseYouTubeTitle("Narayanamma Lyric Video I Aadarsha Kutumbam I Venkatesh, Shriya", "Aditya Music")
        assertEquals("Aadarsha Kutumbam", q.album)
        assertEquals("Venkatesh, Shriya", q.credits)
        assertEquals("Meesaya Murukku", Formatters.parseYouTubeTitle("Music Video | Meesaya Murukku | Hiphop Tamizha", "Vibe Venuma").title)
        assertNull(Formatters.parseYouTubeTitle("Backstreet Boys - I Want It That Way (Official HD Video)", "x").album)
    }
}
