package com.arnav.music.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable data class ArchiveValue(val type: String, val value: String = "")
@Serializable data class ArchiveTable(val name: String, val columns: List<String>, val rows: List<List<ArchiveValue>>)
@Serializable data class UserArchive(
    val schema: Int = 1, val roomVersion: Int = 6, val settings: String,
    val tables: List<ArchiveTable>, val preferences: Map<String, Map<String, ArchiveValue>>,
    val files: Map<String, String> = emptyMap(),
)

object ArchiveCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val CHUNK_BYTES = 300 * 1024
    val json = Json { encodeDefaults = true }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)
    fun encode(archive: UserArchive, maxBytes: Int = MAX_BYTES): ByteArray {
        require(maxBytes in 1..MAX_BYTES)
        val raw = json.encodeToString(archive).toByteArray(Charsets.UTF_8)
        require(raw.size <= maxBytes) { "Backup exceeds the size limit. No data was discarded." }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
    }
    fun decode(bytes: ByteArray, expectedHash: String = hash(bytes)): UserArchive {
        require(bytes.size <= MAX_BYTES && hash(bytes) == expectedHash) { "Backup integrity check failed" }
        val out = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= MAX_BYTES) { "Backup expands beyond 64 MB" }
                out.write(buffer, 0, n)
            }
        }
        return json.decodeFromString<UserArchive>(out.toString(Charsets.UTF_8.name())).also {
            require(it.schema == 1 && it.roomVersion == 6) { "Backup needs a compatible app version" }
        }
    }
    fun chunks(bytes: ByteArray): List<ByteArray> = bytes.indices.step(CHUNK_BYTES).map {
        bytes.copyOfRange(it, minOf(it + CHUNK_BYTES, bytes.size))
    }
}
