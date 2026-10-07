package com.arnav.music.core.lyrics

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.arnav.music.domain.lyrics.LyricScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** Where a lyrics translation stands, plus whatever lines are translated so far. */
data class LyricsTranslation(
    val status: Status,
    /** Original line text → translation (only lines that need one). */
    val lines: Map<String, String> = emptyMap(),
) {
    sealed interface Status {
        /** Detecting the language or translating (models already on the phone). */
        data object Working : Status

        /** Downloading the language model(s), about [approxMb] MB in total. Cancellable. */
        data class Downloading(val approxMb: Int, val language: String) : Status

        /** A model is needed and the user cancelled (or hasn't started) the download. */
        data class NeedsDownload(val approxMb: Int, val language: String) : Status

        /** Translation isn't possible for these lyrics (unknown or unsupported language). */
        data class Unavailable(val message: String) : Status

        data class Failed(val message: String) : Status

        /** The lyrics are already in the phone's language. */
        data object SameLanguage : Status

        data object Done : Status
    }
}

/**
 * On-device lyrics translation with ML Kit: the song's language is detected with ML Kit Language
 * ID, then each line is translated with ML Kit Translation into the phone's language. Language
 * models (~30 MB each) are downloaded once, on demand, from Google; translating itself never leaves
 * the phone.
 *
 * Results are cached per song and target language as small JSON files in app storage, so a song
 * opens translated instantly the next time (and after edits only changed lines are translated).
 */
class LyricsTranslator private constructor(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File get() = File(context.filesDir, "lyrics_translations")

    /** The phone's language as an ML Kit translation language, or null when ML Kit doesn't support it. */
    fun targetLanguage(): String? {
        val locale = Locale.getDefault()
        return toTranslateCode(locale.toLanguageTag()) ?: toTranslateCode(locale.language)
    }

    /**
     * Translations of [lines] for the song [trackId]. Emits progress; completes with a final status.
     * With [allowDownload] false a missing model ends in [LyricsTranslation.Status.NeedsDownload].
     * Cancelling the collector stops waiting for a download (and the model is removed again if the
     * system finishes downloading it anyway).
     */
    fun translate(trackId: String, lines: List<String>, allowDownload: Boolean): Flow<LyricsTranslation> = flow {
        val target = targetLanguage()
        if (target == null) {
            emit(LyricsTranslation(LyricsTranslation.Status.Unavailable("Translation into your phone's language isn't available.")))
            return@flow
        }
        val unique = lines.map { it.trim() }.filter { it.isNotEmpty() && it.any(Char::isLetter) }.distinct()
        if (unique.isEmpty()) {
            emit(LyricsTranslation(LyricsTranslation.Status.Done))
            return@flow
        }
        val cached = withContext(Dispatchers.IO) { readCache(trackId, target) }
        val known = HashMap(cached?.lines.orEmpty())
        fun shown(): Map<String, String> = known.filterValues { it.isNotEmpty() }
        val missing = unique.filter { it !in known }
        if (cached != null && cached.source == target) {
            emit(LyricsTranslation(LyricsTranslation.Status.SameLanguage))
            return@flow
        }
        if (missing.isEmpty()) {
            emit(LyricsTranslation(LyricsTranslation.Status.Done, shown()))
            return@flow
        }
        emit(LyricsTranslation(LyricsTranslation.Status.Working, shown()))

        val identifier = LanguageIdentification.getClient()
        try {
            val detected = cached?.source ?: identify(identifier, unique.joinToString("\n").take(DETECT_CHARS))
            if (detected == null) {
                emit(LyricsTranslation(LyricsTranslation.Status.Unavailable("Couldn't tell which language these lyrics are in."), shown()))
                return@flow
            }
            if (detected.endsWith("-Latn", ignoreCase = true)) {
                val name = displayName(detected.substringBefore('-'))
                emit(LyricsTranslation(LyricsTranslation.Status.Unavailable("These lyrics are $name written in Latin letters, which can't be translated on the device."), shown()))
                return@flow
            }
            val source = toTranslateCode(detected)
            if (source == null) {
                emit(LyricsTranslation(LyricsTranslation.Status.Unavailable("Translation from ${displayName(detected)} isn't available on the device."), shown()))
                return@flow
            }
            if (source == target) {
                withContext(Dispatchers.IO) { writeCache(trackId, target, CacheFile(source = source, lines = emptyMap())) }
                emit(LyricsTranslation(LyricsTranslation.Status.SameLanguage))
                return@flow
            }

            val needed = missingModels(listOf(source, target).distinct())
            if (needed.isNotEmpty()) {
                val mb = needed.size * MODEL_MB
                val name = displayName(needed.firstOrNull { it != target } ?: needed.first())
                if (!allowDownload) {
                    emit(LyricsTranslation(LyricsTranslation.Status.NeedsDownload(mb, name), shown()))
                    return@flow
                }
                emit(LyricsTranslation(LyricsTranslation.Status.Downloading(mb, name), shown()))
                try {
                    download(needed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(LyricsTranslation(LyricsTranslation.Status.Failed("Couldn't download the translation model. Check your connection and try again."), shown()))
                    return@flow
                }
                emit(LyricsTranslation(LyricsTranslation.Status.Working, shown()))
            }

            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
            try {
                var sinceEmit = 0
                for (line in missing) {
                    // Lines in another language than the song (e.g. English lines in K-pop) that are
                    // already in the phone's language are left alone.
                    val lineLang = if (line.count(Char::isLetter) >= LINE_DETECT_MIN_LETTERS) identify(identifier, line) else null
                    val result = if (lineLang != null && toTranslateCode(lineLang) == target) {
                        ""
                    } else {
                        val t = try { translator.translate(line).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                        if (t != null && LyricScripts.differs(line, t)) t.trim() else ""
                    }
                    known[line] = result
                    if (++sinceEmit >= EMIT_EVERY) {
                        sinceEmit = 0
                        emit(LyricsTranslation(LyricsTranslation.Status.Working, shown()))
                    }
                }
            } finally {
                translator.close()
            }
            withContext(Dispatchers.IO) { writeCache(trackId, target, CacheFile(source = source, lines = known)) }
            emit(LyricsTranslation(LyricsTranslation.Status.Done, shown()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LyricsTranslation(LyricsTranslation.Status.Failed("Translation didn't work this time."), shown()))
        } finally {
            identifier.close()
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun identify(identifier: LanguageIdentifier, text: String): String? {
        val tag = try { identifier.identifyLanguage(text).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return tag?.takeIf { it.isNotBlank() && it != LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG }
    }

    private suspend fun missingModels(codes: List<String>): List<String> {
        val manager = RemoteModelManager.getInstance()
        return codes.filter { code ->
            val downloaded = try {
                manager.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            !downloaded
        }
    }

    /**
     * Downloads the models. ML Kit downloads can't be stopped once started, so on cancellation we
     * stop waiting and delete the model when the download finishes, unless it's wanted again.
     */
    private suspend fun download(codes: List<String>) {
        val manager = RemoteModelManager.getInstance()
        val conditions = DownloadConditions.Builder().build()
        for (code in codes) {
            unwanted.remove(code)
            val model = TranslateRemoteModel.Builder(code).build()
            val task = manager.download(model, conditions)
            try {
                task.await()
            } catch (e: CancellationException) {
                unwanted.add(code)
                task.addOnSuccessListener {
                    if (unwanted.remove(code)) manager.deleteDownloadedModel(model)
                }
                throw e
            }
        }
    }

    private fun cacheFile(trackId: String, target: String): File = File(dir, "${sha1(trackId)}_$target.json")

    private fun readCache(trackId: String, target: String): CacheFile? = try {
        val f = cacheFile(trackId, target)
        if (f.exists()) json.decodeFromString(CacheFile.serializer(), f.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun writeCache(trackId: String, target: String, data: CacheFile) {
        try {
            dir.mkdirs()
            val f = cacheFile(trackId, target)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(CacheFile.serializer(), data))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // A cache is a nice-to-have.
        }
    }

    /** Deletes cached translations (e.g. from a "clear cache" action). */
    fun clearCache() {
        try { dir.deleteRecursively() } catch (e: Exception) { }
    }

    /** source = detected song language (ML Kit code); lines: original → translation ("" = none needed). */
    @Serializable
    private data class CacheFile(val v: Int = 1, val source: String, val lines: Map<String, String>)

    companion object {
        private const val MODEL_MB = 30
        private const val DETECT_CHARS = 2_000
        private const val LINE_DETECT_MIN_LETTERS = 12
        private const val EMIT_EVERY = 8

        /** Languages whose download the user cancelled: deleted if the download completes anyway. */
        private val unwanted: MutableSet<String> = Collections.synchronizedSet(HashSet())

        @Volatile
        private var instance: LyricsTranslator? = null

        fun get(context: Context): LyricsTranslator =
            instance ?: synchronized(this) { instance ?: LyricsTranslator(context.applicationContext).also { instance = it } }

        /** BCP-47 tag (possibly a legacy Java code like "iw") → ML Kit translate code, or null. */
        fun toTranslateCode(tag: String): String? {
            if (tag.isBlank()) return null
            val base = when (val b = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
                "iw" -> "he"
                "in" -> "id"
                "ji" -> "yi"
                "fil" -> "tl"
                else -> b
            }
            return TranslateLanguage.fromLanguageTag(base)
        }

        fun displayName(code: String): String {
            val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            return name.ifBlank { code }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
