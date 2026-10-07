package com.arnav.music.core.firebase

import android.content.Context
import androidx.room.withTransaction
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.KvSyncEntity
import com.arnav.music.core.db.PlayEventEntity
import com.arnav.music.core.db.TrackEntity
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** Per-record outbox in Room; no credentials, onboarding or device permissions enter it. */
@Serializable
internal data class LiveJournal(
    val kind: String, val value: String, val revision: Long = 0,
    val deleted: Boolean = false, val localId: Long = 0,
)
@Serializable
internal data class SharedListen(
    val trackId: String, val artistKey: String, val startedAt: Long,
    val listenedMs: Long, val durationMs: Long?, val completed: Boolean,
    val skipped: Boolean, val source: String, val title: String = "", val artist: String = "",
    val album: String? = null,
)
@Serializable
internal data class SharedQueue(val tracks: List<Track>, val index: Int)

class CloudRecords(
    private val context: Context, private val db: ArnavDatabase,
    private val settings: SettingsRepository, private val usage: UsageMeter,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    private val queuePrefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
    private val deviceId = prefs.getString("live_device", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("live_device", it).commit()
    }
    private var listener: ListenerRegistration? = null
    private var listenerUid: String? = null
    private fun prefix(uid: String) = "live/$uid/"
    private fun col(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid).collection("liveRecords")
    private fun checkAccount(uid: String) {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid && settings.settings.value.cloudSync) { "Account or sync preference changed" }
    }
    fun observe(uid: String, changed: () -> Unit) {
        if (listenerUid == uid) return
        stopObserving()
        listenerUid = uid
        listener = col(uid).orderBy("updatedAt", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    usage.firestore(reads = snapshot.size().coerceAtLeast(1))
                    changed()
                }
            }
    }
    fun stopObserving() { listener?.remove(); listener = null; listenerUid = null }

    suspend fun sync(uid: String) {
        checkAccount(uid)
        settings.loaded.first { it }
        adoptPendingQueue(uid)
        stage(uid)
        pull(uid)
        push(uid)
        // Collect changes made while the network request was running for the next pass.
        stage(uid)
    }

    private suspend fun journals(uid: String) = db.kv().all().filter { it.key.startsWith(prefix(uid)) }
        .associate { it.key.removePrefix(prefix(uid)) to (it to json.decodeFromString<LiveJournal>(it.json)) }
    private suspend fun save(uid: String, id: String, record: LiveJournal, dirty: Boolean) {
        db.kv().put(KvSyncEntity(prefix(uid) + id, json.encodeToString(record), System.currentTimeMillis(), dirty))
    }
    private fun fields(s: AppSettings): Map<String, JsonElement> = json.encodeToJsonElement(s).jsonObject.filterKeys {
        it !in setOf("cloudSync", "analytics", "onboardingDone", "guestMode", "autoInstallUpdates")
    }
    internal suspend fun stage(uid: String) {
        val records = journals(uid)
        val defaults = fields(AppSettings())
        for ((field, value) in fields(settings.settings.value)) {
            val id = "s_$field"
            val current = records[id]
            val serialized = value.toString()
            if (current == null) save(uid, id, LiveJournal("setting", serialized), serialized != defaults[field].toString())
            else if (current.second.value != serialized) save(uid, id, current.second.copy(value = serialized), true)
        }
        val events = db.events().since(0)
        val byLocalId = records.filterValues { it.second.kind == "history" && it.second.localId > 0 }
            .entries.associateBy { it.value.second.localId }
        for (e in events) {
            if (e.id in byLocalId) continue
            val t = db.tracks().get(e.trackId)
            val listen = SharedListen(e.trackId, e.artistKey, e.startedAt, e.listenedMs, e.durationMs,
                e.completed, e.skipped, e.source, t?.title.orEmpty().take(200), t?.artist.orEmpty().take(200), t?.album?.take(200))
            val id = "h_" + hash("$deviceId:${e.id}:${e.startedAt}:${e.trackId}")
            save(uid, id, LiveJournal("history", json.encodeToString(listen), localId = e.id), true)
        }
        val present = events.map { it.id }.toSet()
        for ((id, pair) in records) {
            val r = pair.second
            if (r.kind == "history" && r.localId > 0 && r.localId !in present && !r.deleted) {
                save(uid, id, r.copy(deleted = true), true)
            }
        }
        val raw = queuePrefs.getString("queue", null)
        val tracks = raw?.let { runCatching { json.decodeFromString(ListSerializer(Track.serializer()), it) }.getOrNull() }
        // MediaStore ids and content URIs are device-specific. Never adopt another phone's URI.
        if (tracks != null && tracks.size <= 100 && tracks.all { it.id.source == com.arnav.music.domain.model.SourceType.YOUTUBE }) {
            val queue = json.encodeToString(SharedQueue(tracks, queuePrefs.getInt("index", 0)))
            val seen = prefs.getString("queue_seen_$uid", null)
            if (queue.length <= 200_000 && seen != queue) {
                val firstEmpty = seen == null && tracks.isEmpty()
                save(uid, "q_shared", (records["q_shared"]?.second ?: LiveJournal("queue", queue)).copy(value = queue), !firstEmpty)
                prefs.edit().putString("queue_seen_$uid", queue).commit()
            }
        }
    }

    private suspend fun pull(uid: String) {
        val since = prefs.getLong("live_cursor_$uid", 0)
        var query: Query = col(uid).whereGreaterThanOrEqualTo("updatedAt", Timestamp(java.util.Date(since)))
            .orderBy("updatedAt").limit(300)
        var newest = since
        while (true) {
            val snapshot = query.get(Source.SERVER).await()
            usage.firestore(reads = snapshot.size().coerceAtLeast(1))
            for (doc in snapshot.documents) {
                checkAccount(uid)
                val kind = doc.getString("kind") ?: continue
                val revision = doc.getLong("revision") ?: continue
                val value = doc.getString("value") ?: continue
                val deleted = doc.getBoolean("deleted") ?: false
                val old = db.kv().get(prefix(uid) + doc.id)
                val journal = old?.let { json.decodeFromString<LiveJournal>(it.json) }
                if (journal != null && revision <= journal.revision) continue
                if (old?.dirty == true) {
                    save(uid, doc.id, journal!!.copy(revision = revision), true)
                    continue
                }
                var localId = journal?.localId ?: 0
                when (kind) {
                    "setting" -> {
                        val field = doc.id.removePrefix("s_")
                        if (field !in fields(settings.settings.value)) continue
                        val element = json.parseToJsonElement(value)
                        settings.update { current ->
                            val now = json.encodeToJsonElement(current).jsonObject
                            // A user edit during pull wins; stage() will put it in the outbox.
                            if (journal != null && now[field].toString() != journal.value) current
                            else json.decodeFromJsonElement<AppSettings>(JsonObject(now + (field to element)))
                        }
                        settings.settings.first { fields(it)[field]?.toString() == value || (journal != null && fields(it)[field]?.toString() != journal.value) }
                    }
                    "history" -> {
                        val event = json.decodeFromString<SharedListen>(value)
                        db.withTransaction {
                            if (deleted) {
                                if (localId > 0) db.events().delete(localId)
                            } else if (localId == 0L) {
                                val original = TrackId(event.trackId)
                                val ownDevice = doc.getString("deviceId") == deviceId
                                val id = if (original.source == com.arnav.music.domain.model.SourceType.LOCAL && !ownDevice)
                                    "local:cloud_" + doc.id.removePrefix("h_") else event.trackId
                                // Listening totals travel between devices; a local file reference cannot.
                                // Keep its metadata in the journal without exposing a playable phantom song.
                                if (original.source == com.arnav.music.domain.model.SourceType.YOUTUBE && event.title.isNotBlank() && db.tracks().get(id) == null) {
                                    db.tracks().upsert(listOf(TrackEntity(id, event.title, event.artist, event.artistKey,
                                        event.album, event.durationMs, null, if (original.source == com.arnav.music.domain.model.SourceType.YOUTUBE) original.nativeId else "", null, "", null, null, event.startedAt)))
                                }
                                localId = db.events().insert(PlayEventEntity(trackId = id, artistKey = event.artistKey,
                                    startedAt = event.startedAt, listenedMs = event.listenedMs, durationMs = event.durationMs,
                                    completed = event.completed, skipped = event.skipped, source = event.source))
                            }
                            save(uid, doc.id, LiveJournal(kind, value, revision, deleted, localId), false)
                        }
                    }
                    "queue" -> {
                        val q = json.decodeFromString<SharedQueue>(value)
                        require(q.tracks.size <= 100 && q.tracks.all { it.source == com.arnav.music.domain.model.SourceType.YOUTUBE })
                        applyQueueWhenIdle(uid, value, q)

                    }
                    else -> continue
                }
                if (kind != "history") save(uid, doc.id, LiveJournal(kind, value, revision, deleted, localId), false)
            }
            newest = maxOf(newest, snapshot.documents.maxOfOrNull { it.getTimestamp("updatedAt")?.toDate()?.time ?: 0 } ?: 0)
            if (snapshot.size() < 300) break
            query = query.startAfter(snapshot.documents.last())
        }
        prefs.edit().putLong("live_cursor_$uid", newest).commit()
    }

    private suspend fun adoptPendingQueue(uid: String) {
        val row = db.kv().get(prefix(uid) + "q_shared") ?: return
        if (row.dirty) return
        val record = json.decodeFromString<LiveJournal>(row.json)
        if (record.value != prefs.getString("queue_seen_$uid", null)) {
            applyQueueWhenIdle(uid, record.value, json.decodeFromString<SharedQueue>(record.value))
        }
    }
    private suspend fun applyQueueWhenIdle(uid: String, value: String, q: SharedQueue) {
        require(q.tracks.size <= 100 && q.tracks.all { it.source == com.arnav.music.domain.model.SourceType.YOUTUBE })
        withContext(Dispatchers.Main) {
            checkAccount(uid)
            val player = org.koin.core.context.GlobalContext.get().get<com.arnav.music.core.playback.PlaybackController>()
            val s = player.state.value
            if (!s.isPlaying && !s.isBuffering && s.queue.items.none { it.track.source == com.arnav.music.domain.model.SourceType.LOCAL }) {
                queuePrefs.edit().putString("queue", json.encodeToString(ListSerializer(Track.serializer()), q.tracks))
                    .putInt("index", q.index.coerceIn(0, (q.tracks.size - 1).coerceAtLeast(0))).commit()
                prefs.edit().putString("queue_seen_$uid", value).commit()
                player.reloadStoredQueue()
            }
        }
    }

    private suspend fun push(uid: String) {
        val dirty = db.kv().dirty().filter { it.key.startsWith(prefix(uid)) }
        for (row in dirty) {
            checkAccount(uid)
            val id = row.key.removePrefix(prefix(uid))
            val r = json.decodeFromString<LiveJournal>(row.json)
            val revision = FirebaseFirestore.getInstance().runTransaction { transaction ->
                val ref = col(uid).document(id)
                val existing = transaction.get(ref)
                val rev = (existing.getLong("revision") ?: 0) + 1
                val value = if (r.kind == "history" && existing.exists()) existing.getString("value") ?: r.value else r.value
                val owner = if (r.kind == "history" && existing.exists()) existing.getString("deviceId") ?: deviceId else deviceId
                transaction.set(ref, mapOf("kind" to r.kind, "value" to value,
                    "revision" to rev, "deleted" to r.deleted, "deviceId" to owner,
                    "updatedAt" to FieldValue.serverTimestamp(), "schema" to 1))
                rev
            }.await()
            usage.firestore(reads = 1, writes = 1)
            checkAccount(uid)
            // Compare-and-clean: do not erase a newer outbox edit.
            db.withTransaction {
                if (db.kv().get(row.key)?.json == row.json) save(uid, id, r.copy(revision = revision), false)
            }
        }
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
