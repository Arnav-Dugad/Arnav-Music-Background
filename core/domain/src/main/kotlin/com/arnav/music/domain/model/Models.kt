package com.arnav.music.domain.model

import kotlinx.serialization.Serializable

/** Where a piece of music comes from. The UI never branches on this except to show attribution. */
@Serializable
enum class SourceType { YOUTUBE, LOCAL }

/** Like YouTube Music's Song/Video switch: SONG = audio or art-track upload, VIDEO = music/lyric video. */
@Serializable
enum class MediaVariant { SONG, VIDEO }

/**
 * Stable cross-source id. Format: "<source>:<native id>", e.g. "yt:dQw4w9WgXcQ" or "local:1234".
 */
@JvmInline
@Serializable
value class TrackId(val value: String) {
    val source: SourceType
        get() = if (value.startsWith(LOCAL_PREFIX)) SourceType.LOCAL else SourceType.YOUTUBE

    val nativeId: String get() = value.substringAfter(':')

    companion object {
        private const val YT_PREFIX = "yt:"
        private const val LOCAL_PREFIX = "local:"
        fun youtube(videoId: String) = TrackId(YT_PREFIX + videoId)
        fun local(mediaStoreId: Long) = TrackId(LOCAL_PREFIX + mediaStoreId)
    }
}

@Serializable
data class Track(
    val id: TrackId,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    /** Video id for YouTube, content:// uri for local media. */
    val playbackRef: String,
    val channelId: String? = null,
    val genres: List<String> = emptyList(),
    /** Estimated 0..1 energy. Null when unknown — never fabricated. */
    val energy: Float? = null,
    val year: Int? = null,
    val variant: MediaVariant? = null,
    /** Performers/cast parsed from label titles ("Venkatesh, Shriya"). */
    val credits: String? = null,
    /** Mix, mashup, jukebox or long-form set — excluded from recommendations. */
    val compilation: Boolean = false,
    /** Position on its disc (on-device albums); null when untagged. */
    val trackNumber: Int? = null,
    /** Disc of a multi-disc album; null when untagged (treated as disc 1). */
    val discNumber: Int? = null,
    /** Source album id (MediaStore ALBUM_ID for on-device songs). */
    val albumId: String? = null,
    /** Album artist tag, when it differs from the per-song artist (compilations, features). */
    val albumArtist: String? = null,
) {
    val source: SourceType get() = id.source
    val artistKey: String get() = ArtistKey.of(artist)
}

object ArtistKey {
    // "feat." must start a word (after a space or a bracket): otherwise "Daft Punk" would lose "ft Punk".
    private val featRegex = Regex("""(?:\s+|\s*[(\[]\s*)(feat\.?|ft\.?|featuring|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val topicSuffix = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)
    private val vevoSuffix = Regex("""vevo$""", RegexOption.IGNORE_CASE)

    /** Normalised artist identity used for affinity math ("Daft Punk - Topic" == "daftpunkvevo"-ish). */
    fun of(raw: String): String {
        val primary = raw.split(',', '&', '×', '/').firstOrNull().orEmpty()
        return primary
            .replace(featRegex, "")
            .replace(topicSuffix, "")
            .trim()
            .replace(vevoSuffix, "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .ifEmpty { raw.lowercase().trim() }
    }
}

@Serializable
data class Artist(
    val key: String,
    val name: String,
    val artworkUrl: String? = null,
    val channelId: String? = null,
)

@Serializable
enum class PlaylistKind { ARNAV, YOUTUBE, LOCAL, SMART }

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val kind: PlaylistKind,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val updatedAt: Long = 0L,
    val pinned: Boolean = false,
)

/** What a source is allowed / able to do. The player UI adapts to this, never to the source itself. */
data class PlaybackCapabilities(
    val backgroundPlayback: Boolean,
    val requiresVisiblePlayer: Boolean,
    val canSeek: Boolean = true,
    val canSkip: Boolean = true,
    val supportsCrossfade: Boolean = false,
    val supportsSpeed: Boolean = false,
    val supportsEqualizer: Boolean = false,
) {
    companion object {
        val LocalMedia = PlaybackCapabilities(
            backgroundPlayback = true, requiresVisiblePlayer = false,
            supportsCrossfade = true, supportsSpeed = true, supportsEqualizer = true,
        )
        /** Experimental GitHub background edition; IFrame playback remains unchanged. */
        val YouTubeEmbed = PlaybackCapabilities(backgroundPlayback = true, requiresVisiblePlayer = false)
    }
}

/** One listening event, recorded locally only (Room). */
data class PlayEvent(
    val trackId: TrackId,
    val artistKey: String,
    val startedAt: Long,
    val listenedMs: Long,
    val trackDurationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
) {
    val completionRatio: Float
        get() = trackDurationMs?.takeIf { it > 0 }?.let { (listenedMs.toFloat() / it).coerceIn(0f, 1f) }
            ?: if (completed) 1f else 0.5f
}

enum class Mood(val label: String, val energy: Float, val valence: Float) {
    ENERGETIC("Energetic", 0.85f, 0.75f),
    UPBEAT("Upbeat", 0.72f, 0.85f),
    CALM("Calm", 0.25f, 0.6f),
    FOCUS("Focus", 0.45f, 0.5f),
    NIGHT("Late night", 0.4f, 0.4f),
    MELANCHOLY("Melancholy", 0.3f, 0.2f),
    ROMANTIC("Romantic", 0.4f, 0.7f),
    PARTY("Party", 0.9f, 0.9f),
    WORKOUT("Workout", 0.95f, 0.6f),
    CINEMATIC("Cinematic", 0.55f, 0.5f),
    ACOUSTIC("Acoustic", 0.35f, 0.6f),
    CHILL("Chill", 0.35f, 0.65f),
    AGGRESSIVE("Aggressive", 0.97f, 0.3f),
    NOSTALGIC("Nostalgic", 0.45f, 0.55f),
}

/** Cheap structured aesthetic description that the app renders procedurally (no image generation). */
@Serializable
data class AestheticDescriptor(
    val mood: String = "neutral",
    val energy: Float = 0.5f,
    val warmth: Float = 0.5f,
    val motion: Float = 0.4f,
    val density: Float = 0.4f,
    val paletteHints: List<String> = emptyList(),
) {
    fun sanitized() = copy(
        energy = energy.coerceIn(0f, 1f),
        warmth = warmth.coerceIn(0f, 1f),
        motion = motion.coerceIn(0f, 1f),
        density = density.coerceIn(0f, 1f),
        paletteHints = paletteHints.take(4).map { it.take(24) },
    )
}
