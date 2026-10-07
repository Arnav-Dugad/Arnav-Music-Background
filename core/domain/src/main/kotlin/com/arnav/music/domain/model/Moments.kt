package com.arnav.music.domain.model

/** Immersive, context-driven listening collections. Visuals are rendered procedurally from [aesthetic]. */
enum class MomentMotion { DRIFT, PULSE, RAIN, SHIMMER, STILL, SURGE }

data class Moment(
    val id: String,
    val title: String,
    val subtitle: String,
    val moods: Set<Mood>,
    val seedQueries: List<String>,
    val aesthetic: AestheticDescriptor,
    val motion: MomentMotion,
    /** Two or three ARGB colours the environment is painted with. */
    val palette: List<Int>,
)

object Moments {
    val all: List<Moment> = listOf(
        Moment("night_drive", "Night Drive", "Neon roads, steady pulse", setOf(Mood.NIGHT, Mood.CINEMATIC),
            listOf("synthwave night drive", "late night drive music", "retrowave"),
            AestheticDescriptor("midnight_drive", 0.62f, 0.25f, 0.45f, 0.35f, listOf("indigo", "magenta")),
            MomentMotion.DRIFT, listOf(0xFF0B1030.toInt(), 0xFF3A1C71.toInt(), 0xFFD76D77.toInt())),
        Moment("rain", "Rain", "Soft textures for grey days", setOf(Mood.CALM, Mood.MELANCHOLY),
            listOf("rainy day lofi", "ambient piano rain", "mellow indie"),
            AestheticDescriptor("rain", 0.25f, 0.35f, 0.3f, 0.6f, listOf("slate", "teal")),
            MomentMotion.RAIN, listOf(0xFF1C2833.toInt(), 0xFF2E4053.toInt(), 0xFF5D8AA8.toInt())),
        Moment("deep_focus", "Deep Focus", "Minimal, wordless, unbroken", setOf(Mood.FOCUS, Mood.CALM),
            listOf("deep focus instrumental", "study beats", "minimal electronic focus"),
            AestheticDescriptor("focus", 0.4f, 0.45f, 0.15f, 0.2f, listOf("graphite", "sage")),
            MomentMotion.STILL, listOf(0xFF101418.toInt(), 0xFF1F2A2E.toInt(), 0xFF7FA38A.toInt())),
        Moment("golden_hour", "Golden Hour", "Warm light, easy rhythm", setOf(Mood.UPBEAT, Mood.CHILL),
            listOf("golden hour indie", "sunset chill", "feel good acoustic"),
            AestheticDescriptor("golden_hour", 0.55f, 0.9f, 0.35f, 0.4f, listOf("amber", "rose")),
            MomentMotion.SHIMMER, listOf(0xFF2B1608.toInt(), 0xFFB8572A.toInt(), 0xFFF4C27A.toInt())),
        Moment("gym", "Gym", "Heavy sets, no small talk", setOf(Mood.WORKOUT, Mood.ENERGETIC),
            listOf("workout hip hop", "gym motivation", "high energy edm"),
            AestheticDescriptor("gym", 0.95f, 0.55f, 0.8f, 0.7f, listOf("crimson", "steel")),
            MomentMotion.SURGE, listOf(0xFF140606.toInt(), 0xFF7A1010.toInt(), 0xFFFF5A36.toInt())),
        Moment("late_night", "Late Night", "Low lights, slow thoughts", setOf(Mood.NIGHT, Mood.CHILL),
            listOf("late night r&b", "midnight lofi", "slow jams"),
            AestheticDescriptor("late_night", 0.35f, 0.3f, 0.25f, 0.3f, listOf("navy", "violet")),
            MomentMotion.DRIFT, listOf(0xFF05060F.toInt(), 0xFF1B1F4B.toInt(), 0xFF6C5CE7.toInt())),
        Moment("calm", "Calm", "Breathe out", setOf(Mood.CALM, Mood.ACOUSTIC),
            listOf("calm acoustic", "ambient relaxing", "soft piano"),
            AestheticDescriptor("calm", 0.2f, 0.55f, 0.15f, 0.2f, listOf("mist", "sand")),
            MomentMotion.STILL, listOf(0xFF121615.toInt(), 0xFF2F3E3A.toInt(), 0xFFB9D3C2.toInt())),
        Moment("throwback", "Throwback", "Songs that remember you", setOf(Mood.NOSTALGIC, Mood.UPBEAT),
            listOf("2000s hits", "90s classics", "throwback party songs"),
            AestheticDescriptor("throwback", 0.65f, 0.7f, 0.4f, 0.5f, listOf("teal", "coral")),
            MomentMotion.PULSE, listOf(0xFF0F1F24.toInt(), 0xFF1E6F72.toInt(), 0xFFFF8A65.toInt())),
        Moment("discovery", "Discovery", "Just outside your comfort zone", setOf(Mood.UPBEAT, Mood.CINEMATIC),
            listOf("new indie music", "underground electronic", "alternative discoveries"),
            AestheticDescriptor("discovery", 0.6f, 0.5f, 0.5f, 0.5f, listOf("cyan", "lime")),
            MomentMotion.SHIMMER, listOf(0xFF061417.toInt(), 0xFF0E4D64.toInt(), 0xFF9BE15D.toInt())),
        Moment("high_energy", "High Energy", "All gas", setOf(Mood.PARTY, Mood.ENERGETIC),
            listOf("party anthems", "dance hits", "high energy pop"),
            AestheticDescriptor("high_energy", 0.92f, 0.65f, 0.75f, 0.75f, listOf("magenta", "gold")),
            MomentMotion.PULSE, listOf(0xFF16041C.toInt(), 0xFF8E2DE2.toInt(), 0xFFFFC837.toInt())),
    )

    fun byId(id: String): Moment? = all.firstOrNull { it.id == id }
}
