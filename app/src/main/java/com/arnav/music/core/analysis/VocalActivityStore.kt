package com.arnav.music.core.analysis

import android.content.Context
import com.arnav.music.domain.lyrics.VocalActivityMeter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** A song's vocal-activity curve: 0..1 per [stepMs], from on-device analysis. */
class VocalActivity(val values: FloatArray, val stepMs: Long)

/**
 * Vocal-activity curves of on-device songs, one small file each in
 * `filesDir/vocal_activity/<sha1(trackId)>.bin` (written by [AnalysisWorker], read by auto-timed
 * lyrics). Format: "VAC1", step in ms (int), value count (int), one unsigned byte per step.
 * Blocking; call off the main thread. Never throws.
 */
class VocalActivityStore(context: Context) {
    private val dir = File(context.filesDir, DIR)

    fun write(trackId: String, curve: FloatArray, stepMs: Long) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val bytes = ByteArrayOutputStream(curve.size + 12).also { bos ->
                DataOutputStream(bos).use { out ->
                    out.write(MAGIC)
                    out.writeInt(stepMs.toInt())
                    out.writeInt(curve.size)
                    out.write(VocalActivityMeter.toBytes(curve))
                }
            }.toByteArray()
            val target = fileFor(trackId)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            // Auto-timing then falls back to the estimate; nothing else depends on this file.
        }
    }

    fun read(trackId: String): VocalActivity? = try {
        val f = fileFor(trackId)
        if (!f.isFile || f.length() > MAX_FILE_BYTES) null
        else DataInputStream(f.inputStream().buffered()).use { input ->
            val magic = ByteArray(4)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return@use null
            val step = input.readInt().toLong()
            val count = input.readInt()
            if (step <= 0 || count <= 0 || count > MAX_FILE_BYTES) return@use null
            val bytes = ByteArray(count)
            input.readFully(bytes)
            VocalActivity(VocalActivityMeter.fromBytes(bytes), step)
        }
    } catch (e: Exception) {
        null
    }

    fun delete(trackId: String) {
        runCatching { fileFor(trackId).delete() }
    }

    /** Removes every stored curve (e.g. with "Clear analysis"). */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(trackId: String): File = File(dir, sha1(trackId) + ".bin")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIR = "vocal_activity"
        val MAGIC = byteArrayOf('V'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
        const val MAX_FILE_BYTES = 64 * 1024
    }
}
