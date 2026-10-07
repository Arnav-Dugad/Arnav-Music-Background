package com.arnav.music.domain

import com.arnav.music.domain.lyrics.LyricScripts
import com.arnav.music.domain.lyrics.RatePacer
import com.arnav.music.domain.lyrics.RomanizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricScriptsTest {
    @Test
    fun latinLinesNeedNoRomanisation() {
        assertTrue(LyricScripts.isLatin("Hello, it's me — 2024!"))
        assertTrue(LyricScripts.isLatin("Déjà vu, señor"))
        assertTrue(LyricScripts.isLatin("♪ ... ♪"))
        assertEquals(RomanizationMode.NONE, LyricScripts.modeFor("Despacito", japaneseSong = false))
        assertEquals(RomanizationMode.NONE, LyricScripts.modeFor("   ", japaneseSong = false))
    }

    @Test
    fun scriptsMapToTheRightMode() {
        assertEquals(RomanizationMode.GENERAL, LyricScripts.modeFor("तुम ही हो", false))
        assertEquals(RomanizationMode.GENERAL, LyricScripts.modeFor("Привет, мир", false))
        assertEquals(RomanizationMode.GENERAL, LyricScripts.modeFor("사랑해 baby", false))
        assertEquals(RomanizationMode.GENERAL, LyricScripts.modeFor("حبيبي", false))
        assertEquals(RomanizationMode.GENERAL, LyricScripts.modeFor("ความรัก", false))
        assertEquals(RomanizationMode.JAPANESE, LyricScripts.modeFor("君の名は", false))
        assertEquals(RomanizationMode.CHINESE, LyricScripts.modeFor("我爱你", false))
        // Kanji-only line in a Japanese song is Japanese, not Chinese.
        assertEquals(RomanizationMode.JAPANESE, LyricScripts.modeFor("愛", japaneseSong = true))
    }

    @Test
    fun japaneseSongDetection() {
        assertTrue(LyricScripts.isJapaneseSong(listOf("Hello", "夢を見た")))
        assertFalse(LyricScripts.isJapaneseSong(listOf("我爱你", "Hello")))
    }

    @Test
    fun usefulnessOfSecondaryLines() {
        assertTrue(LyricScripts.isUseful("तुम ही हो", "tuma hi ho"))
        assertTrue(LyricScripts.isUseful("愛してる", "愛shiteru"))
        assertFalse(LyricScripts.isUseful("愛", "愛"))
        assertFalse(LyricScripts.isUseful("Hello", null))
        assertTrue(LyricScripts.differs("Te quiero", "I love you"))
        assertFalse(LyricScripts.differs("Oh oh oh!", "oh, oh, oh"))
    }

    @Test
    fun pacerSpacesRequests() {
        val p = RatePacer(250)
        assertEquals(0L, p.acquire(1_000))
        assertEquals(250L, p.acquire(1_000))
        assertEquals(400L, p.acquire(1_100))
        // After a long gap there's no waiting.
        assertEquals(0L, p.acquire(10_000))
    }
}
