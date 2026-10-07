package com.arnav.music.core.backup

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.settings.AppSettings
import com.arnav.music.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/** App-owned data only. Auth tokens, API credentials and caches are deliberately excluded. */
class UserDataArchive(private val context: Context, private val db: ArnavDatabase, private val settings: SettingsRepository) {
    companion object {
        val TABLES = listOf("tracks", "likes", "playlists", "playlist_tracks", "play_events", "search_cache",
            "recent_searches", "ai_cache", "kv_sync", "lyrics", "audio_features", "pending_matches",
            "import_history", "tag_overrides", "rec_feedback", "skip_marks")
        val PREFS = listOf("playback_state", "usage_meter", "recommender", "ai_lyrics", "lyrics_online_misses",
            "lyrics_auto_timing", "chapters_youtube", "credits_youtube", "auto_tag_attempts", "import_prefs",
            "duplicates", "shortcuts", "widget_snapshot", "ai_prompt_history", "smart_rules")
        val FILE_DIRS = listOf("lyrics_translations", "vocal_activity")
    }
    suspend fun capture(): UserArchive = withContext(Dispatchers.IO) {
        settings.loaded.first { it }
        val tables = db.withTransaction {
            TABLES.map { table ->
                db.openHelper.readableDatabase.query("SELECT * FROM " + table + " ORDER BY rowid").use { c ->
                    val rows = mutableListOf<List<ArchiveValue>>()
                    while (c.moveToNext()) rows += c.columnNames.indices.map { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> ArchiveValue("null")
                            Cursor.FIELD_TYPE_INTEGER -> ArchiveValue("long", c.getLong(i).toString())
                            Cursor.FIELD_TYPE_FLOAT -> ArchiveValue("double", c.getDouble(i).toString())
                            Cursor.FIELD_TYPE_BLOB -> ArchiveValue("blob", ArchiveCodec.base64(c.getBlob(i)))
                            else -> ArchiveValue("string", c.getString(i))
                        }
                    }
                    ArchiveTable(table, c.columnNames.toList(), rows)
                }
            }
        }
        val preferences = PREFS.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().mapValues { (_, v) ->
                when (v) {
                    is Boolean -> ArchiveValue("boolean", v.toString())
                    is Int -> ArchiveValue("int", v.toString())
                    is Long -> ArchiveValue("long", v.toString())
                    is Float -> ArchiveValue("float", v.toString())
                    is Set<*> -> ArchiveValue("set", ArchiveCodec.json.encodeToString(v.filterIsInstance<String>().sorted()))
                    is String -> ArchiveValue("string", v)
                    else -> error("Unsupported preference type")
                }
            }
        }
        val files = sortedMapOf<String, String>()
        FILE_DIRS.forEach { name ->
            File(context.filesDir, name).walkTopDown().filter { it.isFile }.forEach { file ->
                require(file.length() <= 16 * 1024 * 1024) { "Analysis file exceeds backup limit" }
                files[file.relativeTo(context.filesDir).invariantSeparatorsPath] = ArchiveCodec.base64(file.readBytes())
            }
        }
        UserArchive(settings = ArchiveCodec.json.encodeToString(settings.settings.value), tables = tables, preferences = preferences, files = files)
    }
    suspend fun validate(archive: UserArchive): AppSettings = withContext(Dispatchers.IO) {
        require(archive.schema == 1 && archive.roomVersion == 6)
        require(archive.tables.map { it.name } == TABLES) { "Backup tables don't match this app" }
        archive.tables.forEach { table ->
            val columns = db.openHelper.readableDatabase.query("SELECT * FROM " + table.name + " LIMIT 0").use { it.columnNames.toList() }
            require(table.columns == columns) { "Incompatible backup columns" }
            table.rows.forEach { row -> require(row.size == columns.size); row.forEach(::sqlValue) }
        }
        require(archive.preferences.keys == PREFS.toSet()) { "Unknown preference store" }
        archive.preferences.values.forEach { entries -> entries.values.forEach { v ->
            when (v.type) {
                "boolean" -> v.value.toBooleanStrict()
                "int" -> v.value.toInt()
                "long" -> v.value.toLong()
                "float" -> require(v.value.toFloat().isFinite())
                "set" -> ArchiveCodec.json.decodeFromString<List<String>>(v.value)
                "string" -> Unit
                else -> error("Unknown preference type")
            }
        } }
        archive.files.forEach { (path, data) ->
            val parts = path.split('/')
            require(parts.size >= 2 && parts.first() in FILE_DIRS && parts.none { it.isBlank() || it == "." || it == ".." || '\\' in it }) { "Invalid backup path" }
            require(ArchiveCodec.unbase64(data).size <= 16 * 1024 * 1024)
        }
        ArchiveCodec.json.decodeFromString<AppSettings>(archive.settings)
    }
    suspend fun restore(archive: UserArchive) = withContext(Dispatchers.IO) {
        val newSettings = validate(archive)
        val files = archive.files.mapValues { ArchiveCodec.unbase64(it.value) }
        val values = archive.tables.associate { table -> table.name to table.rows.map { row -> row.map(::sqlValue).toTypedArray() } }
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            TABLES.asReversed().forEach { sql.execSQL("DELETE FROM " + it) }
            archive.tables.forEach { table ->
                val columns = table.columns.joinToString(",") { "\u0060" + it + "\u0060" }
                val placeholders = table.columns.joinToString(",") { "?" }
                values.getValue(table.name).forEach { row -> sql.execSQL("INSERT INTO " + table.name + " (" + columns + ") VALUES (" + placeholders + ")", row) }
            }
        }
        archive.preferences.forEach { (name, entries) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            entries.forEach { (key, v) ->
                when (v.type) {
                    "boolean" -> editor.putBoolean(key, v.value.toBooleanStrict())
                    "int" -> editor.putInt(key, v.value.toInt())
                    "long" -> editor.putLong(key, v.value.toLong())
                    "float" -> editor.putFloat(key, v.value.toFloat())
                    "string" -> editor.putString(key, v.value)
                    "set" -> editor.putStringSet(key, ArchiveCodec.json.decodeFromString<List<String>>(v.value).toSet())
                }
            }
            check(editor.commit()) { "Couldn't restore preferences" }
        }
        FILE_DIRS.forEach { File(context.filesDir, it).deleteRecursively() }
        files.forEach { (path, bytes) -> File(context.filesDir, path).also { it.parentFile?.mkdirs() }.writeBytes(bytes) }
        settings.update { newSettings.copy(guestMode = it.guestMode, onboardingDone = it.onboardingDone, cloudSync = it.cloudSync) }
    }
    private fun sqlValue(v: ArchiveValue): Any? = when (v.type) {
        "null" -> null
        "long" -> v.value.toLong()
        "double" -> v.value.toDouble().also { require(it.isFinite()) }
        "blob" -> ArchiveCodec.unbase64(v.value)
        "string" -> v.value
        else -> error("Unknown SQL value type")
    }
}
