package com.arnav.music.core.firebase

import android.content.Context
import android.os.Build
import com.arnav.music.BuildConfig
import com.arnav.music.core.backup.ArchiveCodec
import com.arnav.music.core.backup.UserArchive
import com.arnav.music.core.backup.UserDataArchive
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class CloudBackup(val id: String, val device: String, val createdAt: Long, val bytes: Long,
    val counts: Map<String, Long>, val hash: String, val chunkIds: List<String>)

/** Publish an immutable manifest only after all content-addressed chunks have been uploaded. */
class CloudVault(val context: Context, val archive: UserDataArchive) {
    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    private val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).commit()
    }
    private val label = (Build.MANUFACTURER + " " + Build.MODEL).take(100)
    private fun root(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid)
    suspend fun save(uid: String): CloudBackup = withContext(Dispatchers.IO) {
        val snapshot = archive.capture()
        val bytes = ArchiveCodec.encode(snapshot)
        val hash = ArchiveCodec.hash(bytes)
        val id = deviceId + "_" + hash
        val user = root(uid)
        val existing = user.collection("backups").document(id).get().await()
        if (existing.exists()) return@withContext fromMap(existing.id, existing.data ?: error("Empty backup"))
        val chunks = ArchiveCodec.chunks(bytes)
        val ids = chunks.map(ArchiveCodec::hash)
        chunks.zip(ids).forEach { (chunk, chunkId) ->
            val ref = user.collection("vaultChunks").document(chunkId)
            if (!ref.get().await().exists()) {
                try {
                    ref.set(mapOf("hash" to chunkId, "payload" to ArchiveCodec.base64(chunk), "bytes" to chunk.size,
                        "schema" to 1, "createdAt" to FieldValue.serverTimestamp())).await()
                } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                    // Another device can win the identical-chunk creation race.
                    val winner = ref.get().await()
                    if (winner.getString("payload") != ArchiveCodec.base64(chunk) || winner.getLong("bytes") != chunk.size.toLong()) throw e
                }
            }
        }
        val now = System.currentTimeMillis()
        val counts = snapshot.tables.associate { it.name to it.rows.size.toLong() }
        val batch = FirebaseFirestore.getInstance().batch()
        batch.set(user.collection("backups").document(id), mapOf("schema" to 1, "roomVersion" to 6,
            "deviceId" to deviceId, "deviceLabel" to label, "appVersion" to BuildConfig.VERSION_NAME,
            "createdAt" to now, "publishedAt" to FieldValue.serverTimestamp(), "hash" to hash,
            "bytes" to bytes.size, "chunkIds" to ids, "counts" to counts))
        batch.set(user.collection("devices").document(deviceId), mapOf("deviceLabel" to label,
            "appVersion" to BuildConfig.VERSION_NAME, "latestBackup" to id,
            "updatedAt" to FieldValue.serverTimestamp(), "schema" to 1))
        batch.commit().await()
        CloudBackup(id, label, now, bytes.size.toLong(), counts, hash, ids)
    }
    suspend fun list(uid: String): List<CloudBackup> = root(uid).collection("backups")
        .orderBy("publishedAt", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(30).get().await()
        .documents.map { fromMap(it.id, it.data ?: error("Empty backup")) }
    suspend fun download(uid: String, backup: CloudBackup): UserArchive = withContext(Dispatchers.IO) {
        require(backup.bytes in 1..ArchiveCodec.MAX_BYTES.toLong() && backup.chunkIds.size in 1..256)
        val out = ByteArrayOutputStream()
        backup.chunkIds.forEach { hash ->
            require(hash.matches(Regex("[0-9a-f]{64}")))
            val doc = root(uid).collection("vaultChunks").document(hash).get().await()
            val data = ArchiveCodec.unbase64(doc.getString("payload") ?: error("Missing backup chunk"))
            require(data.size <= ArchiveCodec.CHUNK_BYTES && ArchiveCodec.hash(data) == hash && doc.getLong("bytes") == data.size.toLong()) { "Damaged backup chunk" }
            require(out.size() + data.size <= ArchiveCodec.MAX_BYTES)
            out.write(data)
        }
        require(out.size().toLong() == backup.bytes) { "Backup size mismatch" }
        ArchiveCodec.decode(out.toByteArray(), backup.hash).also { archive.validate(it) }
    }
    private fun fromMap(id: String, m: Map<String, Any>): CloudBackup {
        require(m["schema"] == 1L && m["roomVersion"] == 6L) { "Unsupported backup version" }
        val chunks = (m["chunkIds"] as? List<*>)?.map { it as? String ?: error("Invalid chunk list") } ?: error("Missing chunk list")
        val counts = (m["counts"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to (v as Number).toLong() }.orEmpty()
        return CloudBackup(id, m["deviceLabel"] as? String ?: "Device", (m["createdAt"] as? Number)?.toLong() ?: 0,
            (m["bytes"] as? Number)?.toLong() ?: 0, counts, m["hash"] as? String ?: error("Missing checksum"), chunks)
    }
}
