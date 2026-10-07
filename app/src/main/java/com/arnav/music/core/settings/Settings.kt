package com.arnav.music.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }
enum class AccentMode { ARTWORK, MATERIAL_YOU, PRESET }
enum class GlassLevel { OFF, SUBTLE, FULL }
enum class MotionLevel { FULL, REDUCED, MINIMAL }
enum class ArtworkMotion { OFF, SUBTLE, DYNAMIC }
enum class PerformanceMode { AUTOMATIC, MAXIMUM, BALANCED, BATTERY_SAVER }
enum class LibraryLayout { LIST, GRID, COMPACT }

@Serializable
data class StudioSettings(
    val homeOrder: List<String> = emptyList(),
    val hiddenHome: Set<String> = emptySet(),
    val sessionControls: Boolean = false,
    val sessionMinutes: Int = 45,
    val sessionEnergy: Float = 0.55f,
    val sessionFamiliarity: Float = 0.6f,
    val sessionDiversity: Float = 0.7f,
    val sessionCurve: com.arnav.music.domain.intelligence.EnergyCurve = com.arnav.music.domain.intelligence.EnergyCurve.FLAT,
)

/** User-facing preferences. Synced to the cloud profile only when the user opts in. */
@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val guestMode: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentMode: AccentMode = AccentMode.ARTWORK,
    val presetAccent: Int = 0xFF8C7CFF.toInt(),
    val glass: GlassLevel = GlassLevel.SUBTLE,
    val motion: MotionLevel = MotionLevel.FULL,
    val artworkMotion: ArtworkMotion = ArtworkMotion.SUBTLE,
    val gyroParallax: Boolean = false,
    val haptics: Boolean = true,
    val performance: PerformanceMode = PerformanceMode.AUTOMATIC,
    val highContrast: Boolean = false,
    val reduceTransparency: Boolean = false,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    // Playback (local media)
    val gapless: Boolean = true,
    val fadeMs: Int = 400,
    val crossfadeMs: Int = 0,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1f,
    val pauseOnDisconnect: Boolean = true,
    // Intelligence
    val aiEnabled: Boolean = true,
    val aiPersonalization: Boolean = true,
    val explanations: Boolean = true,
    val dailyAiLimit: Int = 40,
    // Data
    val cloudSync: Boolean = true,
    val analytics: Boolean = false,
    val youtubeDailyBudget: Int = 10_000,
    val weeklyRecapNotification: Boolean = false,
    val selectedMoods: Set<String> = emptySet(),
    val seedArtists: List<String> = emptyList(),
    val regionCode: String = "",
    // App updates (GitHub Releases)
    val autoUpdate: Boolean = true,
    val updateWifiOnly: Boolean = true,
    val autoInstallUpdates: Boolean = true,
    /** YouTube Music-style default: play the song (audio/art-track upload) unless videos are preferred. */
    val preferVideos: Boolean = false,
    /** Replace unplayable YouTube uploads with another upload of the same song automatically. */
    val autoReplaceUnavailable: Boolean = true,
    /** Picture-in-picture player when leaving the app mid-song (keeps YouTube visible, as required). */
    val floatingPlayer: Boolean = true,
    /** Gentle beat-synced pulses in the Now Playing backdrop for analyzed on-device songs. */
    val beatVisuals: Boolean = true,
    /** OLED: after a few idle seconds in Now Playing, dim to a hairline progress glow around the screen edge. */
    val ambientEdgeGlow: Boolean = true,
    /** Analyze on-device songs for tempo and loudness while charging. */
    val analyzeLocalAudio: Boolean = true,
    /** Show the line being sung in the mini player when synced lyrics exist. */
    val miniPlayerLyrics: Boolean = true,
    /** Songs on this phone: begin the fade at the analysed outro and skip leading silence on the next song. */
    val smartTransitions: Boolean = true,
    /** Look up lyrics on LRCLIB (open community database) when a song has none on the device. */
    val onlineLyrics: Boolean = true,
    /** Show a translation under each lyric line (on-device ML Kit, into the phone's language). */
    val lyricsTranslation: Boolean = false,
    /** Show a romanisation under lines written in non-Latin scripts. */
    val lyricsRomanization: Boolean = false,
    /** Fill in missing artist/album/cover for songs on this phone from MusicBrainz (Wi-Fi). */
    val autoTagLocal: Boolean = true,
    /** Widgets follow the wallpaper colours (Material You) instead of the dark glass style. */
    val widgetMaterialYou: Boolean = true,
    /** When the queue ends, keep playing similar songs picked by the recommender. */
    val endlessRadio: Boolean = true,
    /** When no lyrics are found anywhere, transcribe them with Arnav AI (Gemini) automatically. */
    val autoAiLyrics: Boolean = true,
    /** Crop the empty side bars of YouTube cover-art ("Topic") videos so only the square art shows. */
    val cropArtTracks: Boolean = true,
    /** Now Playing background: a slowly drifting gradient made from the cover's colours (on-device covers). */
    val movingGradient: Boolean = true,
    /** Double-tap the left/right of the cover to go back/forward 5 seconds. */
    val doubleTapSeek: Boolean = true,
    /** On-device covers dissolve into particles and rebuild as the song changes. */
    val coverParticles: Boolean = true,
    /** A haptic pulse on each beat drop of songs on this phone. */
    val beatDropHaptics: Boolean = false,
    val studio: StudioSettings = StudioSettings(),
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val store: DataStore<Preferences> get() = context.settingsStore

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private object K {
        val studio = stringPreferencesKey("studio")
        val snapshot = stringPreferencesKey("settings_snapshot")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val guest = booleanPreferencesKey("guest_mode")
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent_mode")
        val presetAccent = intPreferencesKey("preset_accent")
        val glass = stringPreferencesKey("glass")
        val motion = stringPreferencesKey("motion")
        val artworkMotion = stringPreferencesKey("artwork_motion")
        val gyro = booleanPreferencesKey("gyro")
        val haptics = booleanPreferencesKey("haptics")
        val perf = stringPreferencesKey("performance")
        val highContrast = booleanPreferencesKey("high_contrast")
        val reduceTransparency = booleanPreferencesKey("reduce_transparency")
        val libraryLayout = stringPreferencesKey("library_layout")
        val gapless = booleanPreferencesKey("gapless")
        val fadeMs = intPreferencesKey("fade_ms")
        val crossfadeMs = intPreferencesKey("crossfade_ms")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val speed = floatPreferencesKey("speed")
        val pauseOnDisconnect = booleanPreferencesKey("pause_disconnect")
        val ai = booleanPreferencesKey("ai_enabled")
        val aiPersonal = booleanPreferencesKey("ai_personal")
        val explanations = booleanPreferencesKey("explanations")
        val aiLimit = intPreferencesKey("ai_limit")
        val cloud = booleanPreferencesKey("cloud_sync")
        val analytics = booleanPreferencesKey("analytics")
        val ytBudget = intPreferencesKey("yt_budget")
        val recap = booleanPreferencesKey("weekly_recap")
        val moods = stringPreferencesKey("moods")
        val seeds = stringPreferencesKey("seed_artists")
        val region = stringPreferencesKey("region")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val updateWifiOnly = booleanPreferencesKey("update_wifi_only")
        val autoInstall = booleanPreferencesKey("auto_install_updates")
        val preferVideos = booleanPreferencesKey("prefer_videos")
        val autoReplace = booleanPreferencesKey("auto_replace_unavailable")
        val floatingPlayer = booleanPreferencesKey("floating_player")
        val beatVisuals = booleanPreferencesKey("beat_visuals")
        val ambientGlow = booleanPreferencesKey("ambient_edge_glow")
        val analyzeAudio = booleanPreferencesKey("analyze_local_audio")
        val miniLyrics = booleanPreferencesKey("mini_player_lyrics")
        val smartTransitions = booleanPreferencesKey("smart_transitions")
        val onlineLyrics = booleanPreferencesKey("online_lyrics")
        val lyricsTranslation = booleanPreferencesKey("lyrics_translation")
        val lyricsRomanization = booleanPreferencesKey("lyrics_romanization")
        val autoTagLocal = booleanPreferencesKey("auto_tag_local")
        val widgetMaterialYou = booleanPreferencesKey("widget_material_you")
        val endlessRadio = booleanPreferencesKey("endless_radio")
        val autoAiLyrics = booleanPreferencesKey("auto_ai_lyrics")
        val cropArtTracks = booleanPreferencesKey("crop_art_tracks")
        val movingGradient = booleanPreferencesKey("moving_gradient")
        val doubleTapSeek = booleanPreferencesKey("double_tap_seek")
        val coverParticles = booleanPreferencesKey("cover_particles")
        val beatDropHaptics = booleanPreferencesKey("beat_drop_haptics")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private val _loaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** False until DataStore has been read once — the splash screen waits on this. */
    val loaded: StateFlow<Boolean> = _loaded

    val settings: StateFlow<AppSettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                studio = p[K.studio]?.let { runCatching { json.decodeFromString<StudioSettings>(it) }.getOrNull() } ?: d.studio,
                onboardingDone = p[K.onboarding] ?: d.onboardingDone,
                guestMode = p[K.guest] ?: d.guestMode,
                themeMode = p.enum(K.theme, d.themeMode),
                accentMode = p.enum(K.accent, d.accentMode),
                presetAccent = p[K.presetAccent] ?: d.presetAccent,
                glass = p.enum(K.glass, d.glass),
                motion = p.enum(K.motion, d.motion),
                artworkMotion = p.enum(K.artworkMotion, d.artworkMotion),
                gyroParallax = p[K.gyro] ?: d.gyroParallax,
                haptics = p[K.haptics] ?: d.haptics,
                performance = p.enum(K.perf, d.performance),
                highContrast = p[K.highContrast] ?: d.highContrast,
                reduceTransparency = p[K.reduceTransparency] ?: d.reduceTransparency,
                libraryLayout = p.enum(K.libraryLayout, d.libraryLayout),
                gapless = p[K.gapless] ?: d.gapless,
                fadeMs = p[K.fadeMs] ?: d.fadeMs,
                crossfadeMs = (p[K.crossfadeMs] ?: d.crossfadeMs).coerceIn(0, 12_000),
                skipSilence = p[K.skipSilence] ?: d.skipSilence,
                playbackSpeed = p[K.speed] ?: d.playbackSpeed,
                pauseOnDisconnect = p[K.pauseOnDisconnect] ?: d.pauseOnDisconnect,
                aiEnabled = p[K.ai] ?: d.aiEnabled,
                aiPersonalization = p[K.aiPersonal] ?: d.aiPersonalization,
                explanations = p[K.explanations] ?: d.explanations,
                dailyAiLimit = p[K.aiLimit] ?: d.dailyAiLimit,
                cloudSync = p[K.cloud] ?: d.cloudSync,
                analytics = p[K.analytics] ?: d.analytics,
                youtubeDailyBudget = p[K.ytBudget] ?: d.youtubeDailyBudget,
                weeklyRecapNotification = p[K.recap] ?: d.weeklyRecapNotification,
                selectedMoods = p[K.moods]?.split('|')?.filter { it.isNotBlank() }?.toSet() ?: d.selectedMoods,
                seedArtists = p[K.seeds]?.split('|')?.filter { it.isNotBlank() } ?: d.seedArtists,
                regionCode = p[K.region] ?: d.regionCode,
                autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
                updateWifiOnly = p[K.updateWifiOnly] ?: d.updateWifiOnly,
                autoInstallUpdates = p[K.autoInstall] ?: d.autoInstallUpdates,
                preferVideos = p[K.preferVideos] ?: d.preferVideos,
                autoReplaceUnavailable = p[K.autoReplace] ?: d.autoReplaceUnavailable,
                floatingPlayer = p[K.floatingPlayer] ?: d.floatingPlayer,
                beatVisuals = p[K.beatVisuals] ?: d.beatVisuals,
                ambientEdgeGlow = p[K.ambientGlow] ?: d.ambientEdgeGlow,
                analyzeLocalAudio = p[K.analyzeAudio] ?: d.analyzeLocalAudio,
                miniPlayerLyrics = p[K.miniLyrics] ?: d.miniPlayerLyrics,
                smartTransitions = p[K.smartTransitions] ?: d.smartTransitions,
                onlineLyrics = p[K.onlineLyrics] ?: d.onlineLyrics,
                lyricsTranslation = p[K.lyricsTranslation] ?: d.lyricsTranslation,
                lyricsRomanization = p[K.lyricsRomanization] ?: d.lyricsRomanization,
                autoTagLocal = p[K.autoTagLocal] ?: d.autoTagLocal,
                widgetMaterialYou = p[K.widgetMaterialYou] ?: d.widgetMaterialYou,
                endlessRadio = p[K.endlessRadio] ?: d.endlessRadio,
                autoAiLyrics = p[K.autoAiLyrics] ?: d.autoAiLyrics,
                cropArtTracks = p[K.cropArtTracks] ?: d.cropArtTracks,
                movingGradient = p[K.movingGradient] ?: d.movingGradient,
                doubleTapSeek = p[K.doubleTapSeek] ?: d.doubleTapSeek,
                coverParticles = p[K.coverParticles] ?: d.coverParticles,
                beatDropHaptics = p[K.beatDropHaptics] ?: d.beatDropHaptics,
            )
        }
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val current = p[K.snapshot]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: settings.value
            val s = transform(current)
            p[K.snapshot] = json.encodeToString(AppSettings.serializer(), s)
            p[K.studio] = json.encodeToString(StudioSettings.serializer(), s.studio)
            p[K.onboarding] = s.onboardingDone
            p[K.guest] = s.guestMode
            p[K.theme] = s.themeMode.name
            p[K.accent] = s.accentMode.name
            p[K.presetAccent] = s.presetAccent
            p[K.glass] = s.glass.name
            p[K.motion] = s.motion.name
            p[K.artworkMotion] = s.artworkMotion.name
            p[K.gyro] = s.gyroParallax
            p[K.haptics] = s.haptics
            p[K.perf] = s.performance.name
            p[K.highContrast] = s.highContrast
            p[K.reduceTransparency] = s.reduceTransparency
            p[K.libraryLayout] = s.libraryLayout.name
            p[K.gapless] = s.gapless
            p[K.fadeMs] = s.fadeMs
            p[K.crossfadeMs] = s.crossfadeMs.coerceIn(0, 12_000)
            p[K.skipSilence] = s.skipSilence
            p[K.speed] = s.playbackSpeed
            p[K.pauseOnDisconnect] = s.pauseOnDisconnect
            p[K.ai] = s.aiEnabled
            p[K.aiPersonal] = s.aiPersonalization
            p[K.explanations] = s.explanations
            p[K.aiLimit] = s.dailyAiLimit
            p[K.cloud] = s.cloudSync
            p[K.analytics] = s.analytics
            p[K.ytBudget] = s.youtubeDailyBudget
            p[K.recap] = s.weeklyRecapNotification
            p[K.moods] = s.selectedMoods.joinToString("|")
            p[K.seeds] = s.seedArtists.joinToString("|")
            p[K.region] = s.regionCode
            p[K.autoUpdate] = s.autoUpdate
            p[K.updateWifiOnly] = s.updateWifiOnly
            p[K.autoInstall] = s.autoInstallUpdates
            p[K.preferVideos] = s.preferVideos
            p[K.autoReplace] = s.autoReplaceUnavailable
            p[K.floatingPlayer] = s.floatingPlayer
            p[K.beatVisuals] = s.beatVisuals
            p[K.ambientGlow] = s.ambientEdgeGlow
            p[K.analyzeAudio] = s.analyzeLocalAudio
            p[K.miniLyrics] = s.miniPlayerLyrics
            p[K.smartTransitions] = s.smartTransitions
            p[K.onlineLyrics] = s.onlineLyrics
            p[K.lyricsTranslation] = s.lyricsTranslation
            p[K.lyricsRomanization] = s.lyricsRomanization
            p[K.autoTagLocal] = s.autoTagLocal
            p[K.widgetMaterialYou] = s.widgetMaterialYou
            p[K.endlessRadio] = s.endlessRadio
            p[K.autoAiLyrics] = s.autoAiLyrics
            p[K.cropArtTracks] = s.cropArtTracks
            p[K.movingGradient] = s.movingGradient
            p[K.doubleTapSeek] = s.doubleTapSeek
            p[K.coverParticles] = s.coverParticles
            p[K.beatDropHaptics] = s.beatDropHaptics
        }
    }

    suspend fun reset() { store.edit { it.clear() } }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
