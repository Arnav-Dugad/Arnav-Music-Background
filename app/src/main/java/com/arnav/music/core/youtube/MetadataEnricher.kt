package com.arnav.music.core.youtube

/**
 * Lightweight, transparent genre/energy hints derived from public titles and tags. Values are
 * estimates used only for ordering; when no signal exists we return null instead of guessing.
 */
object MetadataEnricher {
    private val genreLexicon = mapOf(
        "lofi" to listOf("lofi", "lo-fi", "lo fi"),
        "edm" to listOf("edm", "house", "techno", "trance", "dubstep", "electronic", "drum and bass", "dnb"),
        "hip hop" to listOf("hip hop", "hip-hop", "rap", "trap", "drill"),
        "rock" to listOf("rock", "punk", "grunge", "alt rock"),
        "metal" to listOf("metal", "metalcore", "hardcore"),
        "pop" to listOf("pop", "k-pop", "kpop", "dance pop"),
        "r&b" to listOf("r&b", "rnb", "soul", "neo soul"),
        "jazz" to listOf("jazz", "bossa", "swing"),
        "classical" to listOf("classical", "symphony", "orchestra", "sonata", "concerto"),
        "ambient" to listOf("ambient", "drone", "meditation", "sleep music"),
        "acoustic" to listOf("acoustic", "unplugged", "piano cover", "guitar cover"),
        "indie" to listOf("indie", "bedroom pop", "dream pop", "shoegaze"),
        "bollywood" to listOf("bollywood", "hindi", "filmi", "punjabi"),
        "latin" to listOf("reggaeton", "latin", "bachata", "salsa"),
        "synthwave" to listOf("synthwave", "retrowave", "outrun", "vaporwave"),
        "soundtrack" to listOf("soundtrack", "ost", "score", "theme"),
        "country" to listOf("country", "bluegrass"),
        "folk" to listOf("folk"),
    )
    private val genreEnergy = mapOf(
        "lofi" to 0.3f, "edm" to 0.85f, "hip hop" to 0.7f, "rock" to 0.75f, "metal" to 0.92f, "pop" to 0.65f,
        "r&b" to 0.5f, "jazz" to 0.4f, "classical" to 0.3f, "ambient" to 0.15f, "acoustic" to 0.3f, "indie" to 0.5f,
        "bollywood" to 0.6f, "latin" to 0.75f, "synthwave" to 0.6f, "soundtrack" to 0.45f, "country" to 0.55f, "folk" to 0.35f,
    )
    private val energyUp = listOf("remix", "workout", "gym", "hype", "party", "bass boosted", "festival", "anthem", "sped up", "nightcore")
    private val energyDown = listOf("slowed", "reverb", "sleep", "calm", "relax", "chill", "piano", "acoustic", "lullaby", "study")

    fun genres(title: String, tags: List<String>, description: String = ""): List<String> {
        val text = (title + " " + tags.joinToString(" ") + " " + description.take(300)).lowercase()
        return genreLexicon.filter { (_, ws) -> ws.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(text) } }.keys.take(3).toList()
    }

    fun energy(title: String, tags: List<String>, genres: List<String>): Float? {
        val text = (title + " " + tags.joinToString(" ")).lowercase()
        val base = genres.mapNotNull { genreEnergy[it] }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val up = energyUp.count { text.contains(it) }
        val down = energyDown.count { text.contains(it) }
        if (base == null && up == 0 && down == 0) return null
        return ((base ?: 0.55f) + 0.12f * up - 0.12f * down).coerceIn(0.05f, 0.98f)
    }

    fun year(publishedAt: String?): Int? = publishedAt?.take(4)?.toIntOrNull()
}
