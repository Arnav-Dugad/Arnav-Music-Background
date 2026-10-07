package com.arnav.music.domain.ai

import com.arnav.music.domain.intelligence.EnergyCurve
import com.arnav.music.domain.intelligence.SessionConstraints
import com.arnav.music.domain.model.AestheticDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned prompts. Bumping a version invalidates cached AI answers made with the old one. */
object PromptLibrary {
    const val SESSION_VERSION = "session-v4"
    const val EXPLAIN_VERSION = "explain-v1"

    fun sessionPrompt(request: String, topArtists: List<String>, topGenres: List<String>, hourOfDay: Int): String = """
        You are Arnav AI, the music intelligence inside the Arnav Music app.
        Convert the listener's request into listening-session constraints. Do NOT invent song titles.
        seedArtists: 3-5 real, well-known artists whose individual songs fit the request and the listener's taste
        (mix in the listener's artists when it fits; match their language/region when obvious).
        searchQueries: 2-4 short phrases that find SINGLE songs on YouTube, e.g. "<artist> songs" or "<genre> <mood> song".
        Never use the words mix, playlist, mashup, jukebox, compilation, nonstop or hour.
        Listener context (opt-in, approximate): top artists=${topArtists.take(8).joinToString()}; top genres=${topGenres.take(5).joinToString()}; local hour=$hourOfDay.
        Request: "${request.take(300).replace("\"", "'")}"
        Respond ONLY with JSON matching:
        {"title":string<=40 chars,"durationMinutes":int 5-240,"energyTarget":0..1,"energyCurve":"FLAT|RISING|FALLING|WAVE|PEAK",
         "familiarity":0..1,"discoveryRatio":0..1,"artistDiversity":0..1,"moods":[string],"avoidMoods":[string],
         "context":string|null,"seedArtists":[string],"searchQueries":[2-4 short search phrases],"rediscover":bool,
         "aesthetic":{"mood":string,"energy":0..1,"warmth":0..1,"motion":0..1,"density":0..1,"paletteHints":[string]},
         "explanation":string<=120 chars}
    """.trimIndent()

    fun explainPrompt(track: String, artist: String, signals: List<String>): String = """
        In one short sentence (max 14 words), explain to a listener why "$track" by $artist was suggested.
        Use ONLY these facts: ${signals.joinToString("; ")}. Do not guess feelings or circumstances. Plain text.
    """.trimIndent()
}

@Serializable
data class AiSessionResponse(
    val title: String = "Your session",
    val durationMinutes: Int = 45,
    val energyTarget: Float = 0.55f,
    val energyCurve: String = "FLAT",
    val familiarity: Float = 0.6f,
    val discoveryRatio: Float = 0.3f,
    val artistDiversity: Float = 0.7f,
    val moods: List<String> = emptyList(),
    val avoidMoods: List<String> = emptyList(),
    val context: String? = null,
    val seedArtists: List<String> = emptyList(),
    val searchQueries: List<String> = emptyList(),
    val rediscover: Boolean = false,
    val aesthetic: AestheticDescriptor = AestheticDescriptor(),
    @SerialName("explanation") val explanation: String? = null,
) {
    fun toConstraints(): SessionConstraints = SessionConstraints(
        title = title,
        durationMinutes = durationMinutes,
        energyTarget = energyTarget,
        energyCurve = EnergyCurve.entries.firstOrNull { it.name.equals(energyCurve.trim(), true) } ?: EnergyCurve.FLAT,
        familiarity = familiarity,
        discoveryRatio = discoveryRatio,
        artistDiversity = artistDiversity,
        moods = moods,
        avoidMoods = avoidMoods,
        context = context,
        seedArtists = seedArtists,
        searchQueries = searchQueries,
        rediscover = rediscover,
        aesthetic = aesthetic,
    ).sanitized()
}

/** Tolerant parser: handles ```json fences, leading prose, trailing junk, unknown keys. */
object AiJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (inString) {
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return raw.substring(start, i + 1) }
            }
        }
        return null
    }

    fun parseSession(raw: String): Result<AiSessionResponse> = runCatching {
        val obj = extractObject(raw) ?: error("no json object")
        val parsed = json.decodeFromString(AiSessionResponse.serializer(), obj)
        require(parsed.searchQueries.isNotEmpty() || parsed.seedArtists.isNotEmpty() || parsed.moods.isNotEmpty()) { "empty session" }
        parsed
    }
}
