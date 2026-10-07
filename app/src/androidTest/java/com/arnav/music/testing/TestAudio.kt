package com.arnav.music.testing

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Writes a mono 16-bit PCM WAV: a slow sine sweep with a soft amplitude burst on every beat (120 BPM). */
object WavWriter {
    const val SAMPLE_RATE = 44_100

    fun write(out: OutputStream, seconds: Int) {
        val samples = SAMPLE_RATE * seconds
        val dataLen = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataLen)
        }
        out.write(header.array())

        val f0 = 220.0
        val f1 = 660.0
        val k = ln(f1 / f0) / seconds
        val beat = SAMPLE_RATE / 2 // 120 BPM
        val chunk = ByteBuffer.allocate(SAMPLE_RATE * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < samples) {
            chunk.clear()
            val end = minOf(samples, i + SAMPLE_RATE)
            while (i < end) {
                val t = i.toDouble() / SAMPLE_RATE
                // Exponential sweep: phase = 2π f0 (e^{kt} - 1) / k
                val phase = 2 * PI * f0 * (exp(k * t) - 1) / k
                val sinceBeat = (i % beat).toDouble() / SAMPLE_RATE
                val envelope = 0.25 + 0.55 * exp(-sinceBeat * 9.0)
                chunk.putShort((sin(phase) * envelope * 0.8 * Short.MAX_VALUE).toInt().toShort())
                i++
            }
            out.write(chunk.array(), 0, chunk.position())
        }
        out.flush()
    }
}

/**
 * Inserts a short generated song into MediaStore (Music/ArnavTest) before the activity starts and
 * deletes it afterwards. [title] is read back from MediaStore after the media scan, exactly as the
 * app will show it.
 */
class TestAudioRule(private val seconds: Int = 45) : ExternalResource() {
    lateinit var title: String
        private set
    var uri: Uri? = null
        private set

    private val resolver: ContentResolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    override fun before() {
        assumeTrue("Inserting into MediaStore without storage permission needs API 29+", Build.VERSION.SDK_INT >= 29)
        val stamp = (System.currentTimeMillis() / 1000 % 100_000).toString()
        val name = "Arnav Test Tone $stamp"
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.wav")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/ArnavTest")
            put(MediaStore.MediaColumns.TITLE, name)
            put(MediaStore.Audio.Media.ARTIST, "Arnav Test Artist")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val inserted = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        uri = inserted
        resolver.openOutputStream(inserted, "w").use { out ->
            requireNotNull(out) { "No output stream for $inserted" }
            WavWriter.write(out, seconds)
        }
        resolver.update(inserted, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)

        // The app only lists IS_MUSIC files of at least 20 s, so wait until the scan filled in the duration.
        var scanned = awaitScanned(inserted, 15_000)
        if (scanned == null) {
            dataPath(inserted)?.let { path -> MediaScannerConnection.scanFile(InstrumentationRegistry.getInstrumentation().targetContext, arrayOf(path), arrayOf("audio/x-wav"), null) }
            scanned = awaitScanned(inserted, 20_000)
        }
        title = scanned ?: error("MediaStore never reported a duration ≥ 20 s for $inserted (${describe(inserted)})")
        Log.i(TAG, "Inserted test song '$title' at $inserted (${describe(inserted)})")
    }

    override fun after() {
        val u = uri ?: return
        runCatching { resolver.delete(u, null, null) }
            .onFailure { Log.w(TAG, "Could not delete test song $u", it) }
    }

    private fun awaitScanned(u: Uri, timeoutMs: Long): String? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            val id = u.lastPathSegment ?: return null
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.TITLE),
                "${MediaStore.Audio.Media._ID} = ? AND ${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000",
                arrayOf(id),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it } }
            SystemClock.sleep(250)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun dataPath(u: Uri): String? = runCatching {
        resolver.query(u, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun describe(u: Uri): String = runCatching {
        resolver.query(
            u,
            arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.IS_MUSIC, MediaStore.MediaColumns.RELATIVE_PATH),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) "row missing"
            else "title=${c.getString(0)} duration=${c.getString(1)} isMusic=${c.getString(2)} path=${c.getString(3)}"
        } ?: "query returned null"
    }.getOrElse { "query failed: ${it.message}" }
}
