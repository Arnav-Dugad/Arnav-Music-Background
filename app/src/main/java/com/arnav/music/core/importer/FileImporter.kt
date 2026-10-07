package com.arnav.music.core.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arnav.music.domain.importer.ImportFormatException
import com.arnav.music.domain.importer.ImportSource
import com.arnav.music.domain.importer.ImportedPlaylist
import com.arnav.music.domain.importer.ParsedImport
import com.arnav.music.domain.importer.PlaylistFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a playlist export the user picked with the system file picker: a Spotify data export
 * (.zip, Playlist*.json, YourLibrary.json) or a CSV file. Only the picked file is read, once;
 * nothing is uploaded. Files over [MAX_BYTES] are refused.
 */
class FileImporter(private val context: Context) {

    suspend fun read(uri: Uri): ParsedImport = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni).orEmpty()
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        if (size > MAX_BYTES) throw ImportFormatException(TOO_LARGE)

        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_BYTES) }
            ?: throw ImportFormatException("Couldn't open this file. Try picking it again.")
        val isZip = name.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte())
        if (isZip) readZip(bytes) else PlaylistFiles.parse(name.ifBlank { "Imported playlist.csv" }, decode(bytes))
    }

    /** The picked file's name ("Playlist1.json", "my_spotify_data.zip"), for the import history; null when unknown. */
    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getString(i) else null
            }
        }.getOrNull()
        (fromProvider ?: uri.lastPathSegment?.substringAfterLast('/'))?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Spotify sends its data export as a zip (Playlist1.json, Playlist2.json, YourLibrary.json, …);
     * Exportify's "export all" is a zip of CSV files. Other entries are ignored.
     */
    private fun readZip(bytes: ByteArray): ParsedImport {
        val playlists = ArrayList<ImportedPlaylist>()
        var sawJson = false
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                val base = path.substringAfterLast('/')
                if (entry.isDirectory || path.startsWith("__MACOSX") || base.startsWith(".")) continue
                val lower = base.lowercase()
                val spotifyJson = lower.endsWith(".json") && (lower.startsWith("playlist") || lower == "yourlibrary.json")
                val csv = lower.endsWith(".csv")
                if (!spotifyJson && !csv) continue
                val data = readLimited(zip, MAX_BYTES)
                total += data.size
                if (total > MAX_UNZIPPED_BYTES) throw ImportFormatException(TOO_LARGE)
                val text = decode(data)
                if (spotifyJson) {
                    sawJson = true
                    runCatching { PlaylistFiles.parseSpotifyJson(text) }.getOrNull()?.let { playlists += it }
                } else {
                    runCatching { PlaylistFiles.parse(base, text) }.getOrNull()?.let { playlists += it.playlists }
                }
                if (playlists.size >= MAX_PLAYLISTS) break
            }
        }
        if (playlists.isEmpty()) throw ImportFormatException(PlaylistFiles.NOT_A_PLAYLIST)
        return ParsedImport(if (sawJson) ImportSource.SPOTIFY else ImportSource.CSV, playlists.take(MAX_PLAYLISTS))
    }

    /** UTF-8 (with or without BOM); UTF-16 when the file starts with a UTF-16 byte-order mark (Excel "Unicode text"). */
    private fun decode(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    /** Reads at most [limit] bytes; throws when the stream holds more. */
    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            read += n
            if (read > limit) throw ImportFormatException(TOO_LARGE)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        private const val MAX_UNZIPPED_BYTES = 60L * 1024 * 1024
        private const val MAX_PLAYLISTS = 200
        const val TOO_LARGE = "This file is larger than 20 MB. Export a single playlist, or pick the Playlist1.json file from your Spotify export."

        /** MIME types offered in the system picker. Some providers label CSV as text/plain or octet-stream. */
        val SPOTIFY_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/*", "application/octet-stream")
        val CSV_TYPES = arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/zip", "application/octet-stream")
    }
}
