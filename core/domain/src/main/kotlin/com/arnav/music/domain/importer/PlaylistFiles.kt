package com.arnav.music.domain.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One song as written in an exported playlist file. Nothing here has been matched yet. */
data class ImportedSong(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
)

data class ImportedPlaylist(val name: String, val songs: List<ImportedSong>)

enum class ImportSource { SPOTIFY, CSV }

data class ParsedImport(val source: ImportSource, val playlists: List<ImportedPlaylist>) {
    val songCount: Int get() = playlists.sumOf { it.songs.size }
}

/** The file isn't a playlist export we understand. [message] is safe to show as-is. */
class ImportFormatException(message: String = PlaylistFiles.NOT_A_PLAYLIST) : Exception(message)

/**
 * Parsers for playlist files the user exported themselves and picked with the system file picker:
 * Spotify's "Download your data" JSON (Playlist1.json, YourLibrary.json) and CSV files such as
 * Exportify's or any spreadsheet with recognisable column names. Pure functions, no I/O.
 */
object PlaylistFiles {
    const val NOT_A_PLAYLIST = "This file doesn't look like a Spotify or CSV playlist export."
    const val LIKED_SONGS_NAME = "Liked Songs (Spotify)"

    /** Upper bound per playlist so one enormous file can't flood the matching queue. */
    const val MAX_SONGS_PER_PLAYLIST = 5_000

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses one file by content (JSON object → Spotify, otherwise CSV). [fileName] supplies the
     * playlist name for CSV files. Throws [ImportFormatException] when nothing usable is found.
     */
    fun parse(fileName: String, text: String): ParsedImport {
        val body = text.removePrefix("\uFEFF")
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val playlists = parseSpotifyJson(body)
            if (playlists.isEmpty()) throw ImportFormatException()
            return ParsedImport(ImportSource.SPOTIFY, playlists)
        }
        val playlist = parseCsv(body, nameFromFile(fileName))
        if (playlist.songs.isEmpty()) throw ImportFormatException()
        return ParsedImport(ImportSource.CSV, listOf(playlist))
    }

    /** "My Mix.final.csv" → "My Mix.final"; "folder/Road trip.csv" → "Road trip". */
    fun nameFromFile(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        val name = (if (dot >= 0) base.substring(0, dot) else base).replace('_', ' ').trim()
        return name.ifBlank { "Imported playlist" }.take(100)
    }

    // ---------------------------------------------------------------- Spotify JSON

    /**
     * Spotify "Download your data" files. `Playlist1.json` holds `{"playlists":[{"name", "items":[{"track":{…}}]}]}`
     * (items may instead be episodes, audiobooks or local files, which are skipped). `YourLibrary.json`
     * holds `{"tracks":[{"artist","album","track","uri"}]}`, imported as one "Liked Songs (Spotify)" playlist.
     * Returns an empty list for JSON that is neither; throws [ImportFormatException] for invalid JSON.
     */
    fun parseSpotifyJson(text: String): List<ImportedPlaylist> {
        val root = try {
            lenientJson.parseToJsonElement(text.removePrefix("\uFEFF"))
        } catch (e: Exception) {
            throw ImportFormatException()
        }
        val obj = root as? JsonObject ?: return emptyList()
        val out = ArrayList<ImportedPlaylist>()

        (obj["playlists"] as? JsonArray)?.forEach { p ->
            val pl = p as? JsonObject ?: return@forEach
            val name = pl.string("name")?.trim().orEmpty().ifBlank { "Spotify playlist" }
            val songs = (pl["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                val track = (item as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
                song(track.string("trackName"), track.string("artistName"), track.string("albumName"), null)
            }
            if (songs.isNotEmpty()) out += ImportedPlaylist(name.take(100), songs.take(MAX_SONGS_PER_PLAYLIST))
        }

        val libraryTracks = obj["tracks"] as? JsonArray
        if (libraryTracks != null) {
            val songs = libraryTracks.mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                song(o.string("track"), o.string("artist"), o.string("album"), null)
            }
            if (songs.isNotEmpty()) out += ImportedPlaylist(LIKED_SONGS_NAME, songs.take(MAX_SONGS_PER_PLAYLIST))
        }
        return out
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun song(title: String?, artist: String?, album: String?, durationMs: Long?): ImportedSong? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        return ImportedSong(
            title = t.take(200),
            artist = artist?.trim().orEmpty().take(200),
            album = album?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
            durationMs = durationMs?.takeIf { it > 0 },
        )
    }

    // ---------------------------------------------------------------- CSV

    private val titleExact = listOf("track name", "title", "track title", "song title", "song name", "song", "track", "name")
    private val artistExact = listOf("artist names", "artist name", "artists", "artist", "performer", "performers", "band")
    private val albumExact = listOf("album name", "album", "album title", "release")
    private val durationExact = listOf("duration ms", "duration", "length", "time", "duration s", "track duration ms")

    private val idLike = Regex("""\b(uri|url|id|ids|isrc|number|no|count|position|popularity|key|image|preview)\b""")

    /** Header names reduced to lowercase words: "Artist Name(s)" → "artist names", "Duration (ms)" → "duration ms". */
    fun normalizeHeader(h: String): String =
        h.removePrefix("\uFEFF").lowercase().replace(Regex("""[()\[\]]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    internal data class Columns(val title: Int, val artist: Int, val album: Int, val duration: Int, val durationHeader: String)

    internal fun detectColumns(header: List<String>): Columns? {
        val h = header.map(::normalizeHeader)
        fun exact(keys: List<String>): Int {
            for (k in keys) { val i = h.indexOf(k); if (i >= 0) return i }
            return -1
        }
        var title = exact(titleExact)
        if (title < 0) title = h.indexOfFirst { (it.contains("title") || it.contains("song") || it.contains("track")) && !idLike.containsMatchIn(it) && !it.contains("artist") && !it.contains("album") }
        if (title < 0) return null
        var artist = exact(artistExact)
        if (artist < 0) artist = h.indexOfFirst { it.contains("artist") && !it.contains("album") && !idLike.containsMatchIn(it) }
        var album = exact(albumExact)
        if (album < 0) album = h.indexOfFirst { it.contains("album") && !it.contains("artist") && !it.contains("date") && !idLike.containsMatchIn(it) }
        var duration = exact(durationExact)
        if (duration < 0) duration = h.indexOfFirst { it.contains("duration") || it.contains("length") }
        return Columns(title, artist, album, duration, if (duration >= 0) h[duration] else "")
    }

    /**
     * Reads a CSV playlist. The first row must be a header naming at least a title column; artist,
     * album and duration columns are optional. Rows without a title are skipped.
     */
    fun parseCsv(text: String, playlistName: String): ImportedPlaylist {
        val rows = readCsv(text)
        val headerIndex = rows.indexOfFirst { r -> r.any { it.isNotBlank() } }
        if (headerIndex < 0) throw ImportFormatException()
        val cols = detectColumns(rows[headerIndex]) ?: throw ImportFormatException()
        val songs = ArrayList<ImportedSong>()
        for (r in rows.subList(headerIndex + 1, rows.size)) {
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val artist = cell(cols.artist).split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            val s = song(cell(cols.title), artist, cell(cols.album), parseDuration(cell(cols.duration), cols.durationHeader)) ?: continue
            songs += s
            if (songs.size >= MAX_SONGS_PER_PLAYLIST) break
        }
        return ImportedPlaylist(playlistName.ifBlank { "Imported playlist" }.take(100), songs)
    }

    /**
     * Durations as milliseconds ("215000"), seconds ("215"), "3:35" or "1:02:03". A header that says
     * "ms" or "seconds" wins; otherwise bare numbers of 10,000 or more are milliseconds.
     */
    fun parseDuration(raw: String, header: String = ""): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains(':')) {
            val parts = s.split(':').map { it.trim() }
            if (parts.size !in 2..3) return null
            val nums = parts.map { it.toDoubleOrNull() ?: return null }
            val seconds = nums.fold(0.0) { acc, n -> acc * 60 + n }
            return (seconds * 1000).toLong().takeIf { it > 0 }
        }
        val n = s.replace(',', '.').toDoubleOrNull() ?: return null
        if (n <= 0) return null
        val words = header.split(' ')
        val ms = when {
            "ms" in words || header.contains("millis") -> n
            "s" in words || "sec" in words || header.contains("second") -> n * 1000
            n >= 10_000 -> n
            else -> n * 1000
        }
        return ms.toLong()
    }

    /**
     * RFC 4180 reader: quoted fields may contain the delimiter, line breaks and doubled quotes.
     * Handles a UTF-8 BOM, CRLF/LF/CR line endings, and auto-detects `,`, `;` or tab from the first line.
     */
    fun readCsv(text: String): List<List<String>> {
        val src = text.removePrefix("\uFEFF")
        val delimiter = detectDelimiter(src)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldStarted = false
        var wasQuoted = false
        fun endField() { row.add(field.toString()); field.setLength(0); fieldStarted = false; wasQuoted = false }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = ArrayList()
        }
        while (i < src.length) {
            val ch = src[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(ch)
            } else {
                when {
                    ch == '"' && !wasQuoted && field.isBlank() -> { field.setLength(0); inQuotes = true; wasQuoted = true; fieldStarted = true }
                    ch == delimiter -> endField()
                    ch == '\r' -> { endRow(); if (i + 1 < src.length && src[i + 1] == '\n') i++ }
                    ch == '\n' -> endRow()
                    else -> { field.append(ch); fieldStarted = true }
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) endRow()
        return rows
    }

    /** Most frequent of `,`, `;` and tab outside quotes on the first line; comma when tied or absent. */
    fun detectDelimiter(text: String): Char {
        var commas = 0; var semis = 0; var tabs = 0
        var inQuotes = false
        for (ch in text) {
            if (ch == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                when (ch) {
                    '\n', '\r' -> break
                    ',' -> commas++
                    ';' -> semis++
                    '\t' -> tabs++
                }
            }
        }
        return when {
            semis > commas && semis >= tabs -> ';'
            tabs > commas && tabs > semis -> '\t'
            else -> ','
        }
    }
}
