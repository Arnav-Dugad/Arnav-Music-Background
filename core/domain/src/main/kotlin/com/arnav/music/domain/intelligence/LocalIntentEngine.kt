package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.AestheticDescriptor
import com.arnav.music.domain.model.Mood

/**
 * Offline "Arnav AI". A small lexicon + rule interpreter that turns requests like
 * "45 minutes of energetic but not aggressive coding music" into [SessionConstraints].
 * It is the guaranteed fallback when Gemini quota, network or Firebase are unavailable.
 */
object LocalIntentEngine {

    private val moodLexicon: Map<Mood, List<String>> = mapOf(
        Mood.ENERGETIC to listOf("energetic", "energy", "hype", "pumped", "powerful", "intense", "fast"),
        Mood.UPBEAT to listOf("upbeat", "happy", "cheerful", "bright", "feel good", "feel-good", "fun", "sunny", "positive"),
        Mood.CALM to listOf("calm", "relax", "relaxing", "peaceful", "soothing", "gentle", "soft", "sleep", "unwind", "calmer"),
        Mood.FOCUS to listOf("focus", "coding", "code", "study", "studying", "work", "concentrate", "deep work", "reading", "programming"),
        Mood.NIGHT to listOf("night", "late-night", "late night", "midnight", "2am", "nocturnal", "after dark"),
        Mood.MELANCHOLY to listOf("sad", "melancholy", "melancholic", "heartbreak", "blue", "rainy", "moody", "lonely"),
        Mood.ROMANTIC to listOf("romantic", "love", "date", "romance"),
        Mood.PARTY to listOf("party", "dance", "club", "banger", "bangers"),
        Mood.WORKOUT to listOf("gym", "workout", "run", "running", "lifting", "training", "cardio"),
        Mood.CINEMATIC to listOf("cinematic", "epic", "movie", "soundtrack", "score", "drive", "driving"),
        Mood.ACOUSTIC to listOf("acoustic", "unplugged", "guitar", "folk"),
        Mood.CHILL to listOf("chill", "lofi", "lo-fi", "laid back", "laid-back", "mellow", "easy", "vibe"),
        Mood.AGGRESSIVE to listOf("aggressive", "angry", "heavy", "metal", "hard", "rage"),
        Mood.NOSTALGIC to listOf("nostalgic", "throwback", "old", "classic", "classics", "retro", "90s", "80s", "2000s"),
    )

    private val contextLexicon = mapOf(
        "coding" to listOf("coding", "programming", "code"),
        "study" to listOf("study", "studying", "exam", "exams", "homework"),
        "gym" to listOf("gym", "workout", "lifting", "training"),
        "run" to listOf("run", "running", "jog"),
        "drive" to listOf("drive", "driving", "road trip", "roadtrip"),
        "sleep" to listOf("sleep", "bedtime", "falling asleep"),
        "party" to listOf("party", "pregame"),
        "cooking" to listOf("cooking", "kitchen"),
        "commute" to listOf("commute", "train", "bus"),
    )

    private val negators = listOf("not", "no", "without", "avoid", "never", "less", "nothing", "but not")
    private val durationRegex = Regex("""(\d{1,3}(?:\.\d)?)\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes)\b""")
    private val wordDuration = mapOf(
        "half an hour" to 30, "half hour" to 30, "an hour" to 60, "one hour" to 60, "two hours" to 120,
        "quarter of an hour" to 15, "hour and a half" to 90, "couple of hours" to 120,
    )
    private val similarRegex = Regex("""(?:like|similar to|songs by|music by|by|from)\s+([\p{L}\p{N}][\p{L}\p{N} .&'-]{1,40})""", RegexOption.IGNORE_CASE)

    fun interpret(input: String, recentArtists: List<String> = emptyList()): SessionConstraints {
        val text = " " + input.lowercase().replace(Regex("""[!?,;:]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "

        val wanted = LinkedHashSet<Mood>()
        val avoided = LinkedHashSet<Mood>()
        for ((mood, words) in moodLexicon) {
            for (w in words) {
                val idx = text.indexOf(" $w ")
                if (idx < 0) continue
                if (isNegated(text, idx)) avoided += mood else wanted += mood
            }
        }
        avoided.forEach { wanted.remove(it) }

        val context = contextLexicon.entries.firstOrNull { (_, ws) -> ws.any { text.contains(" $it ") } }?.key
        if (context == "coding" || context == "study") wanted += Mood.FOCUS
        if (context == "gym" || context == "run") wanted += Mood.WORKOUT
        if (context == "sleep") wanted += Mood.CALM
        if (context == "drive") wanted += Mood.CINEMATIC

        val minutes = parseDuration(text)

        val calmer = text.contains(" calmer ") || text.contains(" slower ") || text.contains(" softer ")
        val harder = text.contains(" harder ") || text.contains(" faster ") || text.contains(" more energetic ")
        var energy = if (wanted.isEmpty()) 0.55f else wanted.map { it.energy }.average().toFloat()
        if (calmer) energy -= 0.2f
        if (harder) energy += 0.15f
        if (Mood.AGGRESSIVE in avoided) energy = energy.coerceAtMost(0.8f)

        val curve = when {
            listOf("gradually increase", "build up", "builds up", "ramp up", "increase in energy", "warm up", "rising").any { text.contains(it) } -> EnergyCurve.RISING
            listOf("wind down", "cool down", "calm down", "decrease", "fade out", "winding down").any { text.contains(it) } -> EnergyCurve.FALLING
            listOf("peak", "climax").any { text.contains(it) } -> EnergyCurve.PEAK
            listOf("waves", "ups and downs", "mix it up").any { text.contains(it) } -> EnergyCurve.WAVE
            else -> EnergyCurve.FLAT
        }

        val rediscover = listOf("rediscover", "haven't played", "havent played", "forgotten", "old favorites", "old favourites", "haven't heard").any { text.contains(it) }
        val wantsNew = listOf("new", "discover", "surprise", "surprises", "fresh", "never heard", "something different").any { text.contains(" $it ") }
        val mostlyFamiliar = listOf("familiar", "favorites", "favourites", "know", "comfort").any { text.contains(" $it") }

        val familiarity = when {
            rediscover -> 0.9f
            wantsNew && mostlyFamiliar -> 0.7f
            wantsNew -> 0.25f
            mostlyFamiliar -> 0.85f
            else -> 0.6f
        }
        val discovery = when {
            rediscover -> 0.05f
            wantsNew && mostlyFamiliar -> 0.2f
            wantsNew -> 0.7f
            mostlyFamiliar -> 0.1f
            else -> 0.3f
        }

        val seedArtists = similarRegex.findAll(input).map { it.groupValues[1].trim().trimEnd('.') }
            .filter { it.length >= 2 && it.lowercase() !in setOf("this", "that", "me", "something", "music", "songs") }
            .toList()
            .ifEmpty { if (text.contains(" related ") || text.contains(" similar ")) recentArtists.take(2) else emptyList() }

        val queries = buildList {
            seedArtists.forEach { add(it) }
            val moodWords = wanted.take(2).joinToString(" ") { it.label.lowercase() }
            val ctx = context?.let { " $it" }.orEmpty()
            // "songs" (not "music"): generic "… music" queries mostly return mixes and compilations.
            if (moodWords.isNotBlank() || ctx.isNotBlank()) add("${moodWords}${ctx} songs".trim())
            if (isEmpty()) add("popular songs")
        }

        val title = buildTitle(wanted, context, curve, rediscover)
        val primary = wanted.firstOrNull()
        return SessionConstraints(
            title = title,
            durationMinutes = minutes,
            energyTarget = energy.coerceIn(0.05f, 0.98f),
            energyCurve = curve,
            familiarity = familiarity,
            discoveryRatio = discovery,
            artistDiversity = if (seedArtists.isNotEmpty()) 0.45f else 0.75f,
            moods = wanted.map { it.name.lowercase() },
            avoidMoods = avoided.map { it.name.lowercase() },
            context = context,
            seedArtists = seedArtists,
            searchQueries = queries,
            rediscover = rediscover,
            aesthetic = AestheticDescriptor(
                mood = (context ?: primary?.name?.lowercase() ?: "open"),
                energy = energy.coerceIn(0f, 1f),
                warmth = primary?.valence ?: 0.5f,
                motion = (energy * 0.8f).coerceIn(0.1f, 0.9f),
                density = if (Mood.FOCUS in wanted || Mood.CALM in wanted) 0.2f else 0.5f,
            ),
        ).sanitized()
    }

    private fun isNegated(text: String, idx: Int): Boolean {
        val window = text.substring((idx - 18).coerceAtLeast(0), idx)
        return negators.any { window.contains(" $it ") || window.endsWith(" $it") }
    }

    internal fun parseDuration(text: String): Int {
        // Explicit numbers win ("1.5 hours"); then the longest matching phrase ("hour and a half" before "an hour").
        val m = durationRegex.find(text) ?: run {
            wordDuration.entries.sortedByDescending { it.key.length }.firstOrNull { text.contains(it.key) }?.let { return it.value }
            return 45
        }
        val value = m.groupValues[1].toFloatOrNull() ?: return 45
        val unit = m.groupValues[2]
        val minutes = if (unit.startsWith("h")) value * 60 else value
        return minutes.toInt().coerceIn(5, 240)
    }

    private fun buildTitle(moods: Set<Mood>, context: String?, curve: EnergyCurve, rediscover: Boolean): String {
        if (rediscover) return "Rediscovery"
        val ctx = context?.replaceFirstChar { it.uppercase() }
        val mood = moods.firstOrNull()?.label
        val base = when {
            mood != null && ctx != null -> "$mood $ctx"
            ctx != null -> "$ctx session"
            mood != null -> "$mood mix"
            else -> "Your session"
        }
        return if (curve == EnergyCurve.RISING) "$base · building" else base
    }
}
