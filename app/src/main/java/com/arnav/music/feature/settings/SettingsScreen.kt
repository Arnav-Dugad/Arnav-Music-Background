package com.arnav.music.feature.settings

import android.app.Activity
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.BuildConfig
import com.arnav.music.core.playback.PlaybackService
import com.arnav.music.core.settings.AccentMode
import com.arnav.music.core.settings.ArtworkMotion
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.core.settings.MotionLevel
import com.arnav.music.core.settings.PerformanceMode
import com.arnav.music.core.settings.ThemeMode
import com.arnav.music.domain.format.Formatters
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.ArnavMark
import com.arnav.music.ui.components.NoticeBanner
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(page: String, vm: SettingsViewModel = koinViewModel()) {
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    val chrome = LocalChromePadding.current
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val app = LocalAppViewModel.current
    LaunchedEffect(notice) { notice?.let { app.message(it); vm.consumeNotice() } }

    val title = when (page) {
        "appearance" -> "Appearance"; "playback" -> "Playback"; "ai" -> "Arnav AI"; "sources" -> "Sources"
        "privacy" -> "Privacy"; "usage" -> "Usage & quotas"; "about" -> "About"; "accessibility" -> "Accessibility"
        "performance" -> "Performance"; "sync" -> "Data & sync"; "account" -> "Account"; "notifications" -> "Notifications"
        "studio" -> "Listening studio"; "developer" -> "Developer"; "library" -> "Library"; "updates" -> "App updates"; else -> "Settings"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back) }
                Text(title, style = ArnavTheme.type.display, color = c.content, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
                busy?.let { NoticeBanner(Icons.Rounded.Cloud, it) }
            }
        }
        item {
            when (page) {
                "appearance" -> AppearancePage(vm)
                "playback" -> PlaybackPage(vm)
                "ai" -> AiPage(vm)
                "sources" -> SourcesPage(vm)
                "privacy" -> PrivacyPage(vm)
                "usage" -> UsagePage(vm)
                "about" -> AboutPage()
                "accessibility" -> AccessibilityPage(vm)
                "performance" -> PerformancePage(vm)
                "sync" -> CloudDataPage(vm)
                "studio" -> StudioPage(vm)
                "account" -> AccountPage(vm)
                "notifications" -> NotificationsPage(vm)
                "library" -> LibraryPage(vm)
                "updates" -> UpdatesPage(vm)
                "developer" -> if (BuildConfig.DEBUG) DeveloperPage(vm) else RootPage()
                else -> RootPage()
            }
        }
    }
}

@Composable
private fun RootPage() {
    val nav = LocalNavigator.current
    val c = ArnavTheme.colors
    fun go(p: String) = nav.go(Routes.settings(p))
    Column {
        SettingsGroup {
            NavRow(Icons.Rounded.AccountCircle, "Account", "Sign in, sync, profile", c.accent) { go("account") }
        }
        SettingsGroup("Experience") {
            NavRow(Icons.Rounded.AutoAwesome, "Listening studio", "Home layout, AI controls, rule playlists", c.accent) { go("studio") }
            Divider()
            NavRow(Icons.Rounded.Palette, "Appearance", "Theme, accent, Glass, motion", Color(0xFF8C7CFF)) { go("appearance") }
            Divider()
            NavRow(Icons.Rounded.PlayCircle, "Playback", "Fades, speed, equalizer", Color(0xFF52D6C3)) { go("playback") }
            Divider()
            NavRow(Icons.Rounded.AutoAwesome, "Arnav AI", "Gemini, personalization, explanations", Color(0xFFFFB86B)) { go("ai") }
            Divider()
            NavRow(Icons.Rounded.SmartDisplay, "Sources", "YouTube key, region, quota budget", Color(0xFFFF6B6B)) { go("sources") }
            Divider()
            NavRow(Icons.Rounded.LibraryMusic, "Library", "On-device files, saved results", Color(0xFF6BA8FF)) { go("library") }
        }
        SettingsGroup("Data") {
            NavRow(Icons.Rounded.Cloud, "Data & sync", "What syncs, and when", Color(0xFF6BA8FF)) { go("sync") }
            Divider()
            NavRow(Icons.Rounded.Notifications, "Notifications", "Recaps, playback controls", Color(0xFFFFB86B)) { go("notifications") }
            Divider()
            NavRow(Icons.Rounded.Shield, "Privacy", "What's local, what's shared, delete data", Color(0xFF4ADE9B)) { go("privacy") }
            Divider()
            NavRow(Icons.Rounded.Insights, "Usage & quotas", "Free-tier usage today", Color(0xFFB9AEFF)) { go("usage") }
        }
        SettingsGroup("System") {
            NavRow(Icons.Rounded.Accessibility, "Accessibility", "Contrast, transparency, haptics", Color(0xFF52D6C3)) { go("accessibility") }
            Divider()
            NavRow(Icons.Rounded.Bolt, "Performance", "Quality vs battery", Color(0xFFFFC266)) { go("performance") }
            Divider()
            NavRow(Icons.Rounded.SystemUpdate, "App updates", "v${BuildConfig.VERSION_NAME} · from GitHub Releases", Color(0xFF8C7CFF)) { go("updates") }
            Divider()
            NavRow(Icons.Rounded.Info, "About", "Version, licenses, attribution", c.contentMuted) { go("about") }
            if (BuildConfig.DEBUG) { Divider(); NavRow(Icons.Rounded.Code, "Developer", "Diagnostics (debug builds only)", c.contentMuted) { go("developer") } }
        }
    }
}

@Composable
private fun AppearancePage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val reveal = com.arnav.music.ui.LocalThemeReveal.current
    fun animated(change: () -> Unit) { reveal?.run(change) ?: change() }
    val budget by vm.budget.collectAsStateWithLifecycle()
    Column {
        GlassPreview(s.glass)
        SettingsGroup("Theme") {
            ChoiceRow("Theme", ThemeMode.entries, s.themeMode, { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"; ThemeMode.OLED -> "OLED black" } }, { m -> animated { vm.update { it.copy(themeMode = m) } } })
            Divider()
            ChoiceRow("Accent", AccentMode.entries, s.accentMode, { when (it) { AccentMode.ARTWORK -> "From artwork"; AccentMode.MATERIAL_YOU -> "Material You"; AccentMode.PRESET -> "Preset" } }, { m -> vm.update { it.copy(accentMode = m) } })
            if (s.accentMode == AccentMode.PRESET) {
                Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    listOf(0xFF8C7CFF, 0xFF52D6C3, 0xFFFF6B9A, 0xFFFFB86B, 0xFF6BA8FF, 0xFF4ADE9B).forEach { col ->
                        val selected = s.presetAccent == col.toInt()
                        Box(Modifier.size(if (selected) 36.dp else 30.dp).clip(CircleShape).background(Color(col)).clickable(onClickLabel = "Accent colour") { vm.update { it.copy(presetAccent = col.toInt()) } })
                    }
                }
            }
        }
        SettingsGroup("Glass UI", footer = if (budget.glass != s.glass) "Currently reduced: ${budget.reason}." else "Glass is a material with four optical layers. Pure mode stays fully opaque and just as premium.") {
            ChoiceRow("Glass", GlassLevel.entries, s.glass, { when (it) { GlassLevel.OFF -> "Pure"; GlassLevel.SUBTLE -> "Subtle"; GlassLevel.FULL -> "Full" } }, { g -> animated { vm.update { it.copy(glass = g) } } })
        }
        SettingsGroup("Motion") {
            ChoiceRow("Motion", MotionLevel.entries, s.motion, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { m -> vm.update { it.copy(motion = m) } })
            Divider()
            ChoiceRow("Living Artwork", ArtworkMotion.entries, s.artworkMotion, { when (it) { ArtworkMotion.OFF -> "Off"; ArtworkMotion.SUBTLE -> "Subtle"; ArtworkMotion.DYNAMIC -> "Dynamic" } }, { m -> vm.update { it.copy(artworkMotion = m) } },
                subtitle = "Slow light and colour drift behind the player")
            Divider()
            ToggleRow("Tilt parallax", s.gyroParallax, { v -> vm.update { it.copy(gyroParallax = v) } }, "Very subtle depth when you tilt your phone")
            Divider()
            ToggleRow("Beat-synced light", s.beatVisuals, { v -> vm.update { it.copy(beatVisuals = v) } }, "Songs on this phone: the backdrop breathes gently with the beat once analyzed. Never flashes")
            Divider()
            ToggleRow("Material You widgets", s.widgetMaterialYou, { v -> vm.update { it.copy(widgetMaterialYou = v) } }, "Widgets take their colours from your wallpaper (Android 12+); off keeps the dark glass look")
            Divider()
            ToggleRow("Ambient edge glow", s.ambientEdgeGlow, { v -> vm.update { it.copy(ambientEdgeGlow = v) } }, "OLED theme: after 8 s idle in Now Playing the screen sinks to black with a hairline of progress light around the edge")
        }
    }
}

/** Live demonstration of the four glass layers over an artwork-like backdrop. */
@Composable
private fun GlassPreview(level: GlassLevel) {
    Box(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.s).fillMaxWidth().height(170.dp).clip(RoundedCornerShape(Radius.xl))
            .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)))),
    ) {
        Row(Modifier.fillMaxSize().padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            GlassMaterial.entries.forEach { m ->
                Box(Modifier.weight(1f).height(110.dp).glass(m, RoundedCornerShape(Radius.m), level = level), contentAlignment = Alignment.BottomStart) {
                    if (m == GlassMaterial.Elevated) com.arnav.music.ui.theme.LiquidGlassBackdrop(null, "glass-preview", Radius.m, alpha = 0.35f)
                    Text(m.name, style = ArnavTheme.type.caption, color = ArnavTheme.colors.content, modifier = Modifier.padding(Space.s))
                }
            }
        }
    }
}


@Composable
private fun PlaybackPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column {
        SettingsGroup("On-device music", footer = "These apply to music files on your phone. YouTube playback uses YouTube's own player.") {
            ToggleRow("Gapless playback", s.gapless, { v -> vm.update { it.copy(gapless = v) } }, "Albums flow without silence between tracks")
            Divider()
            SliderRow("Fade in / out", s.fadeMs.toFloat(), 0f..1500f, 5, if (s.fadeMs == 0) "Off" else "${s.fadeMs} ms") { v -> vm.update { it.copy(fadeMs = v.toInt()) } }
            Divider()
            ToggleRow("Skip silence", s.skipSilence, { v -> vm.update { it.copy(skipSilence = v) } }, "Trims long silent gaps")
            Divider()
            SliderRow("Playback speed", s.playbackSpeed, 0.5f..2f, 5, "%.2f×".format(s.playbackSpeed)) { v -> vm.update { it.copy(playbackSpeed = v) } }
            Divider()
            ToggleRow("Pause when headphones disconnect", s.pauseOnDisconnect, { v -> vm.update { it.copy(pauseOnDisconnect = v) } })
            Divider()
            ToggleRow("Smart transitions", s.smartTransitions, { v -> vm.update { it.copy(smartTransitions = v) } }, "Analyzed songs fade out where their outro begins and the next one skips silence at its start")
            Divider()
            ActionRowS("Open equalizer", "Uses your phone's built-in audio effects") {
                runCatching {
                    context.startActivity(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, PlaybackService.audioSessionId.value)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC))
                }
            }
        }
        SettingsGroup("Floating player", footer = "Leaving the app mid-song shrinks the player into a small window above other apps. YouTube keeps playing only while that window is visible; closing it stops playback.") {
            ToggleRow("Picture-in-picture", s.floatingPlayer, { v -> vm.update { it.copy(floatingPlayer = v) } }, "Play, pause and skip right from the window")
            Divider()
            ToggleRow("Lyrics in the mini player", s.miniPlayerLyrics, { v -> vm.update { it.copy(miniPlayerLyrics = v) } }, "Shows the line being sung under the title when synced lyrics are available")
            Divider()
            ToggleRow("Lyrics translation", s.lyricsTranslation, { v -> vm.update { it.copy(lyricsTranslation = v) } }, "A translation into your phone's language under each line (on-device; downloads a language model once)")
            Divider()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ToggleRow("Lyrics romanisation", s.lyricsRomanization, { v -> vm.update { it.copy(lyricsRomanization = v) } }, "Latin letters under lines written in other scripts. Japanese: kana only")
                Divider()
            }
            ToggleRow("Endless radio", s.endlessRadio, { v -> vm.update { it.copy(endlessRadio = v) } }, "When the queue ends, keep going with songs picked for you")
            Divider()
            ToggleRow("AI lyrics when none exist", s.autoAiLyrics, { v -> vm.update { it.copy(autoAiLyrics = v) } }, "Arnav AI listens to the song and writes timed lyrics when no other source has them (sends the audio to Google's Gemini; uses your daily AI limit)")
            Divider()
            ToggleRow("Online lyrics (LRCLIB)", s.onlineLyrics, { v -> vm.update { it.copy(onlineLyrics = v) } }, "Fetches time-synced lyrics from LRCLIB, an open community database, when a song has none on this device. Saved after the first look-up")
        }
        SettingsGroup("YouTube", footer = "Song plays the official audio upload (\"Topic\" art track) and Video plays the music video — switch any time in Now Playing. YouTube always plays in its official embedded player, which stays visible; for background listening continue in YouTube Music.") {
            ToggleRow("Moving cover colours", s.movingGradient, { v -> vm.update { it.copy(movingGradient = v) } }, "Now Playing's background slowly drifts through the cover's colours (songs on this phone)")
            ToggleRow("Double-tap to skip 5 seconds", s.doubleTapSeek, { v -> vm.update { it.copy(doubleTapSeek = v) } }, "Double-tap the left or right of the cover to go back or forward")
            ToggleRow("Particle cover changes", s.coverParticles, { v -> vm.update { it.copy(coverParticles = v) } }, "Covers dissolve into particles and rebuild when the song changes (off with reduced motion)")
            ToggleRow("Haptics on the beat drop", s.beatDropHaptics, { v -> vm.update { it.copy(beatDropHaptics = v) } }, if (s.haptics) "A pulse you can feel when the beat drops, for analysed songs on this phone" else "Turn on Haptics first", enabled = s.haptics)
            ToggleRow("Crop cover-art videos", s.cropArtTracks, { v -> vm.update { it.copy(cropArtTracks = v) } }, "Song uploads that are just album art show as a clean square, without the black side bars")
            Divider()
            ToggleRow("Prefer music videos", s.preferVideos, { v -> vm.update { it.copy(preferVideos = v) } }, "Off: songs first, like YouTube Music")
            Divider()
            ToggleRow("Replace unplayable videos", s.autoReplaceUnavailable, { v -> vm.update { it.copy(autoReplaceUnavailable = v) } }, "If an upload can't be embedded, play another upload of the same song")
            Divider()
            InfoRow("Background playback", "Not available (YouTube policy)")
        }
    }
}

@Composable
private fun AiPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val usage by vm.usage.state.collectAsStateWithLifecycle()
    Column {
        SettingsGroup("Arnav AI", footer = "Arnav AI uses Gemini through Firebase AI Logic on the free tier. Songs are always chosen on your device — AI only interprets your words. If AI is off or unavailable, the on-device engine answers instead.") {
            ToggleRow("Cloud AI (Gemini)", s.aiEnabled, { v -> vm.update { it.copy(aiEnabled = v) } }, if (vm.cloudAvailable) "Free tier, with local fallback" else "Not configured in this build — on-device engine only")
            Divider()
            ActionRowS("Reset recommendations", "Forget “not interested”, blocked artists and what the recommender has learned") { vm.resetRecommendations() }
            Divider()
            ToggleRow("Personalize with my taste", s.aiPersonalization, { v -> vm.update { it.copy(aiPersonalization = v) } }, "Sends only your top artist names and style hints — never history or searches")
            Divider()
            ToggleRow("Explain recommendations", s.explanations, { v -> vm.update { it.copy(explanations = v) } })
            Divider()
            SliderRow("Daily AI request limit", s.dailyAiLimit.toFloat(), 5f..100f, 18, "${s.dailyAiLimit}") { v -> vm.update { it.copy(dailyAiLimit = v.toInt()) } }
        }
        if (vm.cloudAvailable && !com.arnav.music.BuildConfig.DEBUG) AppCheckGroup(vm)
        SettingsGroup("Today") {
            InfoRow("AI requests", "${usage.aiRequests} / ${s.dailyAiLimit}")
            InfoRow("Answered from cache", "${(usage.aiCacheRatio * 100).toInt()}%")
            InfoRow("On-device fallbacks", "${usage.aiFallbacks}")
            vm.lastAiError?.let { err ->
                Text("Last Gemini error: $err", style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentSubtle, modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s))
            }
        }
        SettingsGroup { ActionRowS("Delete AI personalization", "Clears cached AI answers and turns personalization off", destructive = true) { vm.deleteAiPersonalization() } }
    }
}

@Composable
private fun SourcesPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.apiKeyPresent.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    val c = ArnavTheme.colors
    val context = LocalContext.current
    Column {
        SettingsGroup("YouTube Data API", footer = "Search uses your own free YouTube Data API key (Google Cloud → APIs → YouTube Data API v3). It's stored encrypted on this device and only sent to Google. Restrict the key to Android apps + YouTube Data API in Google Cloud.") {
            InfoRow("Status", when { hasKey -> "Your key"; vm.builtInKey -> "Built-in key"; else -> "Not connected" })
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.s)) {
                OutlinedTextField(
                    key, { key = it.trim().take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (hasKey) "••••••••  (saved)" else "Paste API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(Radius.m),
                )
            }
            ActionRowS("Save key", enabled = key.length >= 20) { vm.saveApiKey(key); key = "" }
            if (hasKey) { Divider(); ActionRowS("Remove key", destructive = true) { vm.disconnectYouTube() } }
            Divider()
            ActionRowS("How to get a free key") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.google.com/youtube/v3/getting-started")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        SettingsGroup("Quota", footer = "YouTube gives a daily unit allowance (a search costs about 100 units). Arnav Music caches results and slows down near the limit. Set this to match your Google Cloud quota.") {
            SliderRow("Daily unit budget", s.youtubeDailyBudget.toFloat(), 1000f..10000f, 17, "${s.youtubeDailyBudget}") { v -> vm.update { it.copy(youtubeDailyBudget = (v / 500).toInt() * 500) } }
            Divider()
            ChoiceRow("Region for trending", listOf("", "IN", "US", "GB", "CA", "AU", "DE", "JP", "BR"), s.regionCode, { if (it.isBlank()) "Auto" else it }, { r -> vm.update { it.copy(regionCode = r) } })
        }
        Text("Arnav Music is not affiliated with YouTube or Google.", style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(Space.gutter))
    }
}

@Composable
private fun LibraryPage(vm: SettingsViewModel) {
    val app = LocalAppViewModel.current
    val storage by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadStorage(context) }
    Column {
        SettingsGroup("On-device music") {
            InfoRow("Access", if (app.hasLocalPermission()) "Allowed" else "Not allowed")
            Divider()
            ActionRowS("Manage permission in system settings") {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        SettingsGroup("Audio analysis", footer = "Measures tempo, loudness and energy of songs on this phone, on this phone. Powers beat-synced light, energy-aware ordering and the BPM shown for each song. Saved analysis is included in private account backups when cloud sync is enabled.") {
            val s by vm.settings.collectAsStateWithLifecycle()
            val dao = org.koin.compose.koinInject<com.arnav.music.core.db.ArnavDatabase>().audioFeatures()
            val analyzedFlow = androidx.compose.runtime.remember(dao) { dao.analyzedCount() }
            val analyzed by analyzedFlow.collectAsStateWithLifecycle(initialValue = 0)
            ToggleRow("Fix missing song info", s.autoTagLocal, { v -> vm.update { it.copy(autoTagLocal = v) } }, "Fills in missing artist, album and cover for songs on this phone from MusicBrainz, on Wi-Fi. Your files are never changed")
            Divider()
            ActionRowS("Look up missing info now", "Uses MusicBrainz, about one song per second", enabled = s.autoTagLocal) { com.arnav.music.core.metadata.AutoTagWorker.runNow(context) }
            Divider()
            ToggleRow("Analyze while charging", s.analyzeLocalAudio, { v -> vm.update { it.copy(analyzeLocalAudio = v) } }, "Runs quietly in the background, only when plugged in")
            Divider()
            InfoRow("Songs analyzed", "$analyzed")
            Divider()
            ActionRowS("Analyze now", "Starts right away (battery not low)", enabled = app.hasLocalPermission()) {
                com.arnav.music.core.analysis.AnalysisWorker.runNow(context)
            }
        }
        SettingsGroup("Offline cache", footer = "Saved search pages, artwork and metadata make Arnav Music fast and quota-friendly.") {
            InfoRow("Known songs", "${storage.knownTracks}")
            InfoRow("Saved search pages", "${storage.searchCached}")
            InfoRow("Artwork cache", "${storage.artworkCacheKb / 1024} MB")
            Divider()
            ActionRowS("Clear saved search results", destructive = true) { vm.clearSearchHistory() }
        }
    }
}

@Composable
private fun AccessibilityPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column {
        SettingsGroup(footer = "Arnav Music also follows your system font size, TalkBack, and \"Remove animations\".") {
            ToggleRow("High contrast", s.highContrast, { v -> vm.update { it.copy(highContrast = v) } }, "Stronger text and outlines")
            Divider()
            ToggleRow("Reduce transparency", s.reduceTransparency, { v -> vm.update { it.copy(reduceTransparency = v) } }, "Turns Glass into solid surfaces")
            Divider()
            ChoiceRow("Motion", MotionLevel.entries, s.motion, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { m -> vm.update { it.copy(motion = m) } })
            Divider()
            ToggleRow("Haptic feedback", s.haptics, { v -> vm.update { it.copy(haptics = v) } }, "Subtle taps for likes, queueing and snaps")
        }
    }
}

@Composable
private fun PerformancePage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val b by vm.budget.collectAsStateWithLifecycle()
    Column {
        SettingsGroup(footer = "Automatic considers battery saver, device temperature, refresh rate and device class. It reduces blur, particles and motion without breaking anything.") {
            ChoiceRow("Quality", PerformanceMode.entries, s.performance, { when (it) { PerformanceMode.AUTOMATIC -> "Automatic"; PerformanceMode.MAXIMUM -> "Maximum"; PerformanceMode.BALANCED -> "Balanced"; PerformanceMode.BATTERY_SAVER -> "Battery saver" } }, { m -> vm.update { it.copy(performance = m) } })
        }
        SettingsGroup("Right now") {
            InfoRow("Status", b.reason)
            InfoRow("Device class", b.tier.name.lowercase().replaceFirstChar(Char::uppercase))
            InfoRow("Display", "${b.refreshRate.toInt()} Hz")
            InfoRow("Glass", b.glass.name.lowercase())
            InfoRow("Real blur", if (b.realBlur) "On" else "Off")
            InfoRow("Particles", "${b.particles}")
        }
    }
}

@Composable
private fun NotificationsPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column {
        SettingsGroup(footer = "Playback controls appear automatically while on-device music plays. No promotional notifications, ever.") {
            ToggleRow("Weekly listening recap", s.weeklyRecapNotification, { v -> vm.update { it.copy(weeklyRecapNotification = v) } }, "Sunday evening, computed on device")
        }
    }
}

@Composable
private fun AccountPage(vm: SettingsViewModel) {
    val user by vm.user.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val activity = LocalContext.current as? Activity
    Column {
        val u = user
        if (u == null) {
            SettingsGroup(footer = if (vm.cloudAvailable) "An account is optional. It syncs likes and playlists, and privately backs up your app data." else "Cloud accounts aren't configured in this build. Everything works locally.") {
                ActionRowS("Sign in or create account", enabled = vm.cloudAvailable) { nav.go(Routes.AUTH) }
            }
        } else {
            SettingsGroup {
                InfoRow("Name", u.displayName ?: "—")
                InfoRow("Email", u.email ?: "—")
                InfoRow("Verified", if (u.emailVerified) "Yes" else "No")
                if (!u.emailVerified && u.providers.contains("password")) { Divider(); ActionRowS("Resend verification email") { vm.resendVerification() } }
                Divider()
                ActionRowS("Sign out") { vm.signOut(activity) }
            }
        }
    }
}

@Composable
private fun PrivacyPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var confirm by remember { mutableStateOf<String?>(null) }
    val c = ArnavTheme.colors
    Column {
        SettingsGroup("Where your data lives") {
            PrivacyFact("Stored on this device", "App data is kept locally first. Local audio files, API keys and sign-in credentials are excluded from Firestore backups.")
            Divider()
            PrivacyFact("Synced to your account", if (user != null && s.cloudSync) "Likes, playlists, history, searches, AI responses, lyrics, metadata edits, preferences and queue snapshots. Older backups retain data until deleted." else "Nothing — not signed in or sync is off")
            Divider()
            PrivacyFact("What Arnav AI sees", if (s.aiEnabled) "The request you type" + (if (s.aiPersonalization) ", your top artist names and style hints" else "") + (if (s.autoAiLyrics) ", and the audio (or YouTube link) of songs it writes lyrics for" else "") else "Nothing — cloud AI is off")
            Divider()
            PrivacyFact("YouTube", "Search text and video ids go to Google's YouTube Data API using your API key. No YouTube account is linked.")
            Divider()
            PrivacyFact("Analytics", if (s.analytics) "Anonymous feature usage (no titles, artists or searches)" else "Off")
        }
        SettingsGroup("Controls") {
            ToggleRow("Share anonymous usage analytics", s.analytics, { vm.setAnalytics(it) })
            Divider()
            ActionRowS("Clear search history", destructive = true) { confirm = "search" }
            Divider()
            ActionRowS("Clear listening history", "Resets Taste DNA, recaps and smart playlists", destructive = true) { confirm = "history" }
            Divider()
            ActionRowS("Delete AI personalization", destructive = true) { vm.deleteAiPersonalization() }
            Divider()
            ActionRowS("Disconnect YouTube", "Removes your API key from this device", destructive = true) { vm.disconnectYouTube() }
            if (user != null) {
                Divider()
                ActionRowS("Delete cloud profile", "Deletes all account backups, chunks, devices, likes and playlists", destructive = true) { confirm = "cloud" }
                Divider()
                ActionRowS("Delete account", "Permanently deletes your account and cloud data", destructive = true) { confirm = "account" }
            }
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = c.surfaceRaised,
            title = { Text(when (what) { "search" -> "Clear search history?"; "history" -> "Clear listening history?"; "cloud" -> "Delete cloud profile?"; else -> "Delete your account?" }) },
            text = { Text(when (what) { "account" -> "This permanently deletes your Arnav Music account and everything synced. Data on this device stays until you clear it. You may need to sign in again first."; "cloud" -> "All account backups, chunks, devices, likes and playlists will be deleted. Cloud sync turns off to prevent immediate re-upload. Local data stays."; else -> "This can't be undone." }) },
            confirmButton = {
                TextButton({
                    when (what) { "search" -> vm.clearSearchHistory(); "history" -> vm.clearListeningHistory(); "cloud" -> vm.deleteCloudProfile(); "account" -> vm.deleteAccount(activity) }
                    confirm = null
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PrivacyFact(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m)) {
        Text(title, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content)
        Text(body, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted)
    }
}

@Composable
private fun UsagePage(vm: SettingsViewModel) {
    val u by vm.usage.state.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadStorage(context) }
    Column {
        SettingsGroup("YouTube Data API · today", footer = "Approximate, for awareness. Google Cloud Console is the authoritative source.") {
            InfoRow("Units used (approx.)", "${u.youtube.unitsUsed} / ${s.youtubeDailyBudget}")
            InfoRow("Remote searches", "${u.youtube.remoteSearches}")
            InfoRow("Served from cache", "${(u.youtube.cacheHitRatio * 100).toInt()}%")
            InfoRow("State", u.youtube.state(s.youtubeDailyBudget).name.lowercase().replaceFirstChar(Char::uppercase))
        }
        SettingsGroup("Gemini (free tier) · today") {
            InfoRow("Requests", "${u.aiRequests}")
            InfoRow("Cached answers", "${(u.aiCacheRatio * 100).toInt()}%")
            InfoRow("On-device fallbacks", "${u.aiFallbacks}")
        }
        SettingsGroup("Firebase (Spark) · today") {
            InfoRow("Firestore reads", "${u.firestoreReads}")
            InfoRow("Firestore writes", "${u.firestoreWrites}")
        }
        SettingsGroup("On this device") {
            InfoRow("Database", "${storage.databaseKb} KB")
            InfoRow("Artwork cache", "${storage.artworkCacheKb / 1024} MB")
            InfoRow("HTTP cache", "${storage.httpCacheKb / 1024} MB")
            InfoRow("Cached AI answers", "${storage.aiCached}")
        }
    }
}

@Composable
private fun DeveloperPage(vm: SettingsViewModel) {
    val app = LocalAppViewModel.current
    val player by app.playerState.collectAsStateWithLifecycle()
    val b by vm.budget.collectAsStateWithLifecycle()
    val u by vm.usage.state.collectAsStateWithLifecycle()
    Column {
        SettingsGroup("Runtime") {
            InfoRow("Playback engine", player.engine.name)
            InfoRow("Queue", "${player.queue.items.size} items, index ${player.queue.currentIndex}")
            InfoRow("Performance tier", "${b.tier} · ${b.refreshRate.toInt()} Hz")
            InfoRow("HTTP requests today", "${u.httpRequests}")
            InfoRow("Firebase configured", BuildConfig.FIREBASE_CONFIGURED.toString())
        }
        SettingsGroup { ActionRowS("Replay onboarding") { vm.resetOnboarding() } }
    }
}

@Composable
private fun UpdatesPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val c = ArnavTheme.colors
    val context = LocalContext.current
    Column {
        com.arnav.music.ui.update.UpdateCard(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), alwaysShow = true)
        if (!vm.updates.supported) {
            Text("Self-update is turned off in debug builds (they're signed with a different key). Install a release build from GitHub.", style = ArnavTheme.type.caption, color = c.contentSubtle, modifier = Modifier.padding(Space.gutter))
        }
        SettingsGroup("Automatic updates", footer = "Arnav Music checks GitHub Releases every 6 hours. Each download is checked against its SHA-256 checksum and must be signed with the same key as this app, or it's discarded. On Android 12+ updates can install without a tap once Arnav Music has installed itself once.") {
            ToggleRow("Check for updates automatically", s.autoUpdate, { v -> vm.update { it.copy(autoUpdate = v) } })
            Divider()
            ToggleRow("Download on Wi-Fi only", s.updateWifiOnly, { v -> vm.update { it.copy(updateWifiOnly = v) } }, "Saves mobile data", enabled = s.autoUpdate)
            Divider()
            ToggleRow("Install automatically", s.autoInstallUpdates, { v -> vm.update { it.copy(autoInstallUpdates = v) } }, "When the app isn't on screen", enabled = s.autoUpdate)
        }
        SettingsGroup("Permission") {
            InfoRow("Install unknown apps", if (vm.updates.canInstallPackages()) "Allowed" else "Not allowed")
            InfoRow("Silent updates", if (vm.updates.canInstallSilently()) "Available" else "Needs one confirmed update")
            if (!vm.updates.canInstallPackages()) {
                Divider()
                ActionRowS("Allow Arnav Music to install updates") { runCatching { context.startActivity(vm.updates.unknownSourcesSettingsIntent()) } }
            }
        }
        SettingsGroup {
            InfoRow("Installed version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Divider()
            ActionRowS("All releases on GitHub") { open(context, "https://github.com/${BuildConfig.UPDATE_REPO}/releases") }
        }
    }
}

@Composable
private fun AboutPage() {
    val c = ArnavTheme.colors
    val context = LocalContext.current
    Column {
        Column(Modifier.fillMaxWidth().padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            ArnavMark(Modifier.size(72.dp))
            Spacer(Modifier.height(Space.m))
            Text("Arnav Music", style = ArnavTheme.type.headline, color = c.content)
            Text("Music, alive. · v${BuildConfig.VERSION_NAME}", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
        }
        SettingsGroup("Independence", footer = "YouTube is a trademark of Google LLC. Arnav Music is not affiliated with, endorsed by, or sponsored by Google or YouTube.") {
            PrivacyFact("An independent app", "Arnav Music is an independent project. It uses the official YouTube Data API for metadata and the official embedded YouTube player for playback, with YouTube's attribution and ads intact. It never downloads, extracts or background-plays YouTube audio.")
            Divider()
            ActionRowS("YouTube Terms of Service") { open(context, "https://www.youtube.com/t/terms") }
            Divider()
            ActionRowS("Google Privacy Policy") { open(context, "https://policies.google.com/privacy") }
        }
        SettingsGroup("Open-source licenses") {
            listOf(
                "Jetpack Compose, AndroidX, Media3, Room, Navigation, WorkManager — Apache 2.0",
                "Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache 2.0",
                "Firebase Android SDK — Apache 2.0",
                "Koin — Apache 2.0",
                "Coil — Apache 2.0",
                "OkHttp — Apache 2.0",
                "android-youtube-player (Pierfrancesco Soffritti) — Apache 2.0",
                "Material Symbols / Icons — Apache 2.0",
                "Manrope typeface (The Manrope Project Authors) — SIL Open Font License 1.1",
            ).forEach { Text(it, style = ArnavTheme.type.caption, color = c.contentMuted, modifier = Modifier.padding(horizontal = Space.l, vertical = 6.dp)) }
        }
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Lets this phone pass App Check although the APK came from GitHub rather than Google Play: it attests
 * with a debug token that the owner registers once in the Firebase console.
 */
@Composable
private fun AppCheckGroup(vm: SettingsViewModel) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(com.arnav.music.core.firebase.AppCheckDebugToken.isOn(context)) }
    var reveal by remember { mutableStateOf(false) }
    SettingsGroup(
        "Cloud AI on this phone",
        footer = "Google Play vouches only for apps installed from the Play Store, so App Check can't verify an APK from GitHub. " +
            "Firebase requires App Check for AI Logic from 2 November 2026. A debug token lets this one phone through: turn it on, copy the token, " +
            "then in the Firebase console open App Check → Apps → ⋮ next to Arnav Music → Manage debug tokens → Add, and paste it. " +
            "Keep the token private; anyone who has it can use your AI quota. Delete it in the console if you lose this phone.",
    ) {
        ToggleRow("Verify with a debug token", on, { v ->
            on = v
            com.arnav.music.core.firebase.AppCheckDebugToken.setOn(context, v)
            vm.clearAiBackoff()
        }, if (on) "This phone attests with its own token" else "Uses Play Integrity (works only for Play Store installs)")
        if (on) {
            val token = remember { com.arnav.music.core.firebase.AppCheckDebugToken.token(context) }
            if (token != null) {
                Divider()
                ActionRowS(
                    if (reveal) token else "Show debug token",
                    if (reveal) "Tap to copy" else "Shown only here; never sent anywhere except to Firebase App Check",
                ) {
                    if (!reveal) reveal = true else {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        val clip = android.content.ClipData.newPlainText("App Check debug token", token)
                        // Android 13+: keep the token out of the clipboard preview.
                        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        clipboard?.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
