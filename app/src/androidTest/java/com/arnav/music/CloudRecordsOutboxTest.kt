package com.arnav.music

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.PlayEventEntity
import com.arnav.music.core.firebase.CloudRecords
import com.arnav.music.core.firebase.LiveJournal
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class CloudRecordsOutboxTest {
    @Test fun settingsAreIndependentAndHistoryDeletionPersistsOffline() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = GlobalContext.get().get<ArnavDatabase>()
        val settings = GlobalContext.get().get<SettingsRepository>()
        settings.loaded.first { it }
        settings.update { it.copy(themeMode = ThemeMode.OLED, crossfadeMs = 6000) }
        settings.settings.first { it.crossfadeMs == 6000 }
        val sync = CloudRecords(ctx, db, settings, GlobalContext.get().get())
        val event = PlayEventEntity(trackId = "yt:offline", artistKey = "artist", startedAt = 10000,
            listenedMs = 35000, durationMs = 60000, completed = false, skipped = false, source = "YOUTUBE")
        val localId = db.events().insert(event)
        sync.stage("alice")
        val initial = db.kv().all().filter { it.key.startsWith("live/alice/") }
        assertTrue(initial.first { it.key.endsWith("s_crossfadeMs") }.dirty)
        assertTrue(initial.first { it.key.endsWith("s_themeMode") }.dirty)
        assertFalse(initial.any { it.key.endsWith("s_analytics") || it.key.endsWith("s_cloudSync") })
        val listen = initial.single { it.key.substringAfterLast('/').startsWith("h_") }
        assertEquals(localId, Json.decodeFromString<LiveJournal>(listen.json).localId)
        sync.stage("alice")
        assertEquals(1, db.kv().all().count { it.key.startsWith("live/alice/h_") })
        db.events().delete(localId)
        sync.stage("alice")
        assertTrue(Json.decodeFromString<LiveJournal>(db.kv().get(listen.key)!!.json).deleted)
        assertTrue(db.kv().get(listen.key)!!.dirty)
        assertFalse(db.kv().all().any { it.key.startsWith("live/bob/") })
    }
    @Test fun deviceSpecificLocalUrisNeverEnterSharedQueue() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = GlobalContext.get().get<ArnavDatabase>()
        val settings = GlobalContext.get().get<SettingsRepository>()
        settings.loaded.first { it }
        ctx.getSharedPreferences("playback_state", Context.MODE_PRIVATE).edit()
            .putString("queue", """[{"id":"local:7","title":"File","artist":"Artist","playbackRef":"content://media/external/audio/media/7"}]""").commit()
        CloudRecords(ctx, db, settings, GlobalContext.get().get()).stage("alice")
        assertNull(db.kv().get("live/alice/q_shared"))
    }
}
