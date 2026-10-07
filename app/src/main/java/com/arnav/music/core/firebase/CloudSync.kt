package com.arnav.music.core.firebase

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.Log
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.db.LikeEntity
import com.arnav.music.core.db.PlaylistEntity
import com.arnav.music.core.db.TrackEntity
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.TrackId
import com.arnav.music.domain.sync.SyncMerge
import com.arnav.music.domain.sync.SyncRecord
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

enum class SyncStatus { DISABLED, IDLE, SYNCING, OFFLINE, ERROR, UP_TO_DATE, ACCOUNT_BLOCKED }

/**
 * Offline-first sync for likes and Arnav playlists:
 * Room is the source of truth → dirty flags → debounced batched writes → incremental pulls.
 * Additional private account snapshots preserve all app-owned records and preferences.
 */
class CloudSync(
    private val context: Context,
    private val gate: FirebaseGate,
    private val db: ArnavDatabase,
    private val settings: SettingsRepository,
    private val usage: UsageMeter,
    private val clock: Clock,
    private val scope: CoroutineScope,
    val vault: CloudVault,
) {
    private val mutex = Mutex()
    private var debounceJob: Job? = null
    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    val lastSyncedAt: Long get() = uid()?.let { prefs.getLong("last_sync_" + it, 0L) } ?: 0
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _backups = MutableStateFlow<List<CloudBackup>>(emptyList())
    val backups: StateFlow<List<CloudBackup>> = _backups.asStateFlow()
    private var observing = false
    private val listeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
    private fun bindAccount(uid: String) {
        val owner = prefs.getString("data_owner", null)
            ?: prefs.all.keys.firstOrNull { it.startsWith("likes_pulled_") }?.removePrefix("likes_pulled_")
        check(owner == null || owner == uid) {
            "This device contains another account's data. Restore this account's backup, or clear this app's storage before syncing."
        }
        if (!prefs.contains("data_owner")) prefs.edit().putString("data_owner", uid).commit()
    }
    fun startObserving() {
        if (observing || !gate.isAvailable) return
        observing = true
        db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(*com.arnav.music.core.backup.UserDataArchive.TABLES.toTypedArray()) {
            override fun onInvalidated(tables: Set<String>) { requestSync(30_000) }
        })
        scope.launch { settings.settings.collect { requestSync(30_000) } }
        com.arnav.music.core.backup.UserDataArchive.PREFS.filter { it !in listOf("usage_meter", "widget_snapshot") }.forEach { name ->
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestSync(30_000) }
            listeners += listener
            context.getSharedPreferences(name, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        }
        FirebaseAuth.getInstance().addAuthStateListener {
            _backups.value = emptyList()
            _error.value = null
            requestSync(0)
        }
    }

    private fun uid(): String? = if (gate.isAvailable) FirebaseAuth.getInstance().currentUser?.uid else null
    private fun firestore() = FirebaseFirestore.getInstance()

    /** Coalesces bursts of local edits into one write batch a few seconds later. */
    @Synchronized
    fun requestSync(delayMs: Long = 4_000) {
        if (debounceJob?.isActive == true && delayMs > 0) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    fun schedulePeriodic() {
        if (!gate.isAvailable) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "cloud-sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
        wm.enqueueUniqueWork(
            "cloud-sync-once", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
        )
    }

    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        settings.loaded.first { it }
        val uid = uid()
        if (uid == null || !settings.settings.value.cloudSync) {
            _status.value = SyncStatus.DISABLED
            return Result.success(Unit)
        }
        val binding = runCatching { bindAccount(uid) }
        if (binding.isFailure) {
            _error.value = binding.exceptionOrNull()?.message
            _status.value = SyncStatus.ACCOUNT_BLOCKED
            return binding
        }
        _error.value = null
        _status.value = SyncStatus.SYNCING
        val result = runCatching {
            syncLikes(uid)
            syncPlaylists(uid)
            check(uid() == uid) { "Account changed during sync" }
            vault.save(uid)
            refreshBackups().getOrThrow()
            prefs.edit().putLong("last_sync_" + uid, clock.now()).apply()
        }
        _status.value = if (result.isSuccess) SyncStatus.UP_TO_DATE else {
            _error.value = result.exceptionOrNull()?.message ?: "Cloud sync failed"
            Log.w("sync failed", result.exceptionOrNull())
            if (result.exceptionOrNull() is com.google.firebase.FirebaseNetworkException) SyncStatus.OFFLINE else SyncStatus.ERROR
        }
        result
    }

    private suspend fun syncLikes(uid: String) {
        val col = firestore().collection("users").document(uid).collection("likes")
        val since = prefs.getLong("likes_pulled_$uid", 0L)
        val snap = col.whereGreaterThan("updatedAt", since).get().await()
        usage.firestore(reads = snap.size().coerceAtLeast(1))
        val remote: List<SyncRecord<Map<*, *>>> = snap.documents.map { d ->
            SyncRecord(
                id = d.id,
                value = d.get("track") as? Map<*, *>,
                updatedAt = (d.getLong("updatedAt") ?: 0L),
                deleted = d.getBoolean("deleted") ?: false,
            )
        }
        val localDirty = db.likes().dirty()
        val localById = (localDirty + remote.mapNotNull { r -> db.likes().get(r.id) }).associateBy { it.trackId }
        val local = localById.values.map { l -> SyncRecord<Map<*, *>>(l.trackId, null, l.updatedAt, l.deleted, l.dirty) }
        val plan = SyncMerge.plan(local, remote)

        val now = clock.now()
        for (r in plan.applyLocally) {
            (r.value)?.let { m -> trackFromMap(r.id, m)?.let { db.tracks().upsert(listOf(it.copy(updatedAt = now))) } }
            db.likes().upsert(LikeEntity(r.id, r.updatedAt, r.updatedAt, r.deleted, dirty = false))
        }
        val toPush = plan.pushRemote.filter { TrackId(it.id).source == SourceType.YOUTUBE }
        if (toPush.isNotEmpty()) {
            toPush.chunked(400).forEach { chunk ->
                val batch = firestore().batch()
                for (rec in chunk) {
                    val like = localById[rec.id] ?: continue
                    val track = db.tracks().get(rec.id)
                    batch.set(col.document(rec.id), mapOf(
                        "trackId" to rec.id,
                        "likedAt" to like.likedAt,
                        "updatedAt" to like.updatedAt,
                        "deleted" to like.deleted,
                        "track" to track?.let { trackToMap(it) },
                    ), SetOptions.merge())
                }
                batch.commit().await()
                usage.firestore(writes = chunk.size)
            }
        }
        // Local-file likes never sync; mark them clean so they don't retry forever.
        db.likes().markClean(localDirty.map { it.trackId } + plan.discardedLocal)
        val maxRemote = remote.maxOfOrNull { it.updatedAt } ?: since
        prefs.edit().putLong("likes_pulled_$uid", maxOf(since, maxRemote)).apply()
    }

    private suspend fun syncPlaylists(uid: String) {
        val col = firestore().collection("users").document(uid).collection("playlists")
        val since = prefs.getLong("pl_pulled_$uid", 0L)
        val snap = col.whereGreaterThan("updatedAt", since).get().await()
        usage.firestore(reads = snap.size().coerceAtLeast(1))
        val remote: List<SyncRecord<Map<String, Any>>> = snap.documents.map { d -> SyncRecord(d.id, d.data, d.getLong("updatedAt") ?: 0L, d.getBoolean("deleted") ?: false) }
        val dirty = db.playlists().dirty().filter { it.kind == "ARNAV" }
        val local = (dirty + remote.mapNotNull { db.playlists().get(it.id) }).distinctBy { it.id }
            .map { SyncRecord<Map<String, Any>>(it.id, null, it.updatedAt, it.deleted, it.dirty) }
        val plan = SyncMerge.plan(local, remote)
        val now = clock.now()
        for (r in plan.applyLocally) {
            val m = r.value ?: continue
            db.playlists().upsert(
                PlaylistEntity(
                    id = r.id, name = (m["name"] as? String).orEmpty().ifBlank { "Playlist" },
                    description = (m["description"] as? String).orEmpty(), kind = "ARNAV",
                    artworkUrl = m["artworkUrl"] as? String, pinned = m["pinned"] as? Boolean ?: false,
                    createdAt = (m["createdAt"] as? Long) ?: r.updatedAt, updatedAt = r.updatedAt,
                    deleted = r.deleted, dirty = false,
                    // remoteRef isn't synced; keep the local link (e.g. an imported YouTube playlist).
                    remoteRef = db.playlists().get(r.id)?.remoteRef,
                ),
            )
            @Suppress("UNCHECKED_CAST")
            val tracks = (m["tracks"] as? List<Map<String, Any?>>).orEmpty()
            val entities = tracks.mapNotNull { t -> (t["id"] as? String)?.let { trackFromMap(it, t) } }
            db.tracks().upsert(entities.map { it.copy(updatedAt = now) })
            db.playlists().replaceTracks(r.id, entities.map { it.id }, now)
        }
        for (rec in plan.pushRemote) {
            val p = db.playlists().get(rec.id) ?: continue
            val ids = db.playlists().trackIds(p.id).filter { TrackId(it).source == SourceType.YOUTUBE }.take(500)
            val tracks = db.tracks().getAll(ids).associateBy { it.id }
            col.document(p.id).set(mapOf(
                "name" to p.name.take(100), "description" to p.description.take(500), "kind" to "ARNAV",
                "artworkUrl" to p.artworkUrl, "pinned" to p.pinned, "createdAt" to p.createdAt,
                "updatedAt" to p.updatedAt, "deleted" to p.deleted,
                "tracks" to ids.mapNotNull { tracks[it]?.let(::trackToMap) },
            )).await()
            usage.firestore(writes = 1)
        }
        db.playlists().markClean(dirty.map { it.id } + plan.discardedLocal)
        prefs.edit().putLong("pl_pulled_$uid", maxOf(since, remote.maxOfOrNull { it.updatedAt } ?: since)).apply()
    }

    /** Deletes everything Arnav Music stored in the cloud for this user. */
    suspend fun deleteCloudProfile(): Result<Unit> = mutex.withLock { runCatching {
        val uid = uid() ?: error("Sign in first")
        settings.update { it.copy(cloudSync = false) }
        settings.settings.first { !it.cloudSync }
        debounceJob?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork("cloud-sync-once")
        val userDoc = firestore().collection("users").document(uid)
        for (sub in listOf("likes", "playlists", "backups", "vaultChunks", "devices")) {
            while (true) {
                val docs = userDoc.collection(sub).limit(400).get().await()
                if (docs.isEmpty) break
                val batch = firestore().batch()
                docs.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
                usage.firestore(reads = docs.size(), writes = docs.size())
            }
        }
        userDoc.delete().await()
        prefs.edit().remove("likes_pulled_" + uid).remove("pl_pulled_" + uid).remove("last_sync_" + uid).apply()
        _backups.value = emptyList()
        _status.value = SyncStatus.DISABLED
    } }

    suspend fun refreshBackups(): Result<Unit> = runCatching {
        val uid = uid() ?: error("Sign in to view backups")
        val list = vault.list(uid)
        check(uid() == uid) { "Account changed" }
        _backups.value = list
    }
    suspend fun previewBackup(backup: CloudBackup): Result<com.arnav.music.core.backup.UserArchive> = runCatching {
        val uid = uid() ?: error("Sign in first")
        vault.download(uid, backup).also { check(uid() == uid) { "Account changed" } }
    }
    suspend fun restoreBackup(snapshot: com.arnav.music.core.backup.UserArchive, accountUid: String, beforeRestore: () -> Unit): Result<Unit> = mutex.withLock {
        runCatching {
            check(uid() == accountUid) { "Account changed. Open the preview again." }
            vault.archive.validate(snapshot)
            val previous = vault.archive.capture()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File(context.filesDir, "restore-checkpoint.gz").writeBytes(com.arnav.music.core.backup.ArchiveCodec.encode(previous))
            }
            beforeRestore()
            try { vault.archive.restore(snapshot) } catch (e: Exception) {
                runCatching { vault.archive.restore(previous) }
                throw e
            }
            prefs.edit().putString("data_owner", accountUid).remove("likes_pulled_" + accountUid).remove("pl_pulled_" + accountUid).commit()
            _error.value = null
            _status.value = SyncStatus.IDLE
        }
    }

    suspend fun writeProfile(displayName: String?, settingsJson: String) {
        val uid = uid() ?: return
        if (!settings.settings.value.cloudSync) return
        runCatching {
            bindAccount(uid)
            firestore().collection("users").document(uid).set(mapOf(
                "displayName" to displayName?.take(80),
                "settings" to settingsJson.take(8_000),
                "updatedAt" to clock.now(),
                "schema" to 1,
            ), SetOptions.merge()).await()
            usage.firestore(writes = 1)
        }
    }

    private fun trackToMap(t: TrackEntity) = mapOf(
        "id" to t.id, "title" to t.title.take(200), "artist" to t.artist.take(200), "album" to t.album?.take(200),
        "durationMs" to t.durationMs, "artworkUrl" to t.artworkUrl?.take(500), "playbackRef" to t.playbackRef.take(200),
        "channelId" to t.channelId, "genres" to t.genres.take(200),
    )

    private fun trackFromMap(id: String, m: Map<*, *>): TrackEntity? {
        val title = m["title"] as? String ?: return null
        val artist = m["artist"] as? String ?: return null
        val ref = m["playbackRef"] as? String ?: return null
        return TrackEntity(
            id, title, artist, com.arnav.music.domain.model.ArtistKey.of(artist), m["album"] as? String,
            (m["durationMs"] as? Number)?.toLong(), m["artworkUrl"] as? String, ref, m["channelId"] as? String,
            (m["genres"] as? String).orEmpty(), null, null, 0L,
        )
    }
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params), KoinComponent {
    private val sync: CloudSync by inject()
    override suspend fun doWork(): Result = if (sync.syncNow().isSuccess) Result.success() else Result.retry()
}
