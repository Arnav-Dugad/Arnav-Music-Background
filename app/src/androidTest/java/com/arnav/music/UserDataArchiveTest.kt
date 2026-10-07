package com.arnav.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arnav.music.core.backup.*
import com.arnav.music.core.db.*
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.settings.ThemeMode
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class UserDataArchiveTest {
    @Test fun databaseSettingsPreferencesAndBinaryFilesRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ArnavDatabase::class.java).build()
        try {
            val settings = GlobalContext.get().get<SettingsRepository>()
            settings.loaded.first { it }
            settings.update { it.copy(themeMode = ThemeMode.OLED, studio = it.studio.copy(hiddenHome = setOf("trending"))) }
            settings.settings.first { it.themeMode == ThemeMode.OLED }
            db.tracks().upsert(listOf(TrackEntity.from(Track(TrackId.youtube("test"), "音乐 🎵", "Artist", playbackRef = "test"), 123L)))
            db.likes().upsert(LikeEntity("yt:test", 123L, 123L))
            db.lyrics().upsert(LyricsEntity("yt:test", "[00:01.00]Hello", true, "pasted", 123L))
            db.audioFeatures().upsert(AudioFeaturesEntity("yt:test", 120f, 1, -16f, 0.5f, byteArrayOf(0, -1, 8), 123, 1, true))
            val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
            prefs.edit().putLong("long", Long.MAX_VALUE).putStringSet("set", setOf("a", "b")).commit()
            val file = File(context.filesDir, "lyrics_translations/test.json")
            file.parentFile?.mkdirs(); file.writeText("{\"text\":\"音乐\"}")
            val store = UserDataArchive(context, db, settings)
            val snapshot = ArchiveCodec.decode(ArchiveCodec.encode(store.capture()))
            assertEquals(16, snapshot.tables.size)
            db.tracks().upsert(listOf(db.tracks().get("yt:test")!!.copy(title = "Changed")))
            prefs.edit().clear().commit(); file.delete()
            settings.update { it.copy(themeMode = ThemeMode.LIGHT) }
            store.restore(snapshot)
            settings.settings.first { it.themeMode == ThemeMode.OLED }
            assertEquals("音乐 🎵", db.tracks().get("yt:test")!!.title)
            assertArrayEquals(byteArrayOf(0, -1, 8), db.audioFeatures().get("yt:test")!!.envelope)
            assertEquals(Long.MAX_VALUE, prefs.getLong("long", 0))
            assertEquals(setOf("a", "b"), prefs.getStringSet("set", emptySet()))
            assertTrue(file.readText().contains("音乐"))
            assertEquals(setOf("trending"), settings.settings.value.studio.hiddenHome)
            assertFalse(snapshot.preferences.containsKey("secure_store"))
        } finally { db.close() }
    }
    @Test fun incompatibleTablesUnsafePathsAndCredentialStoresFailBeforeWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ArnavDatabase::class.java).build()
        try {
            val store = UserDataArchive(context, db, GlobalContext.get().get<SettingsRepository>())
            db.tracks().upsert(listOf(TrackEntity.from(Track(TrackId.youtube("kept"), "Keep me", "Artist", playbackRef = "kept"), 0)))
            val snapshot = store.capture()
            val invalid = listOf(snapshot.copy(tables = snapshot.tables.drop(1)),
                snapshot.copy(files = mapOf("lyrics_translations/../escape" to "YQ==")),
                snapshot.copy(preferences = snapshot.preferences + ("secure_store" to emptyMap())))
            invalid.forEach { bad ->
                var rejected = false
                try { store.restore(bad) } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue(rejected)
                assertEquals("Keep me", db.tracks().get("yt:kept")!!.title)
            }
        } finally { db.close() }
    }
}
