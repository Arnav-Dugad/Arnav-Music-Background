package com.arnav.music.di

import android.content.pm.PackageManager
import android.os.Build
import com.arnav.music.BuildConfig
import com.arnav.music.core.ai.AiGateway
import com.arnav.music.core.common.Clock
import com.arnav.music.core.common.NetworkMonitor
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.core.diagnostics.UsageMeter
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.firebase.AuthRepository
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.firebase.FirebaseGate
import com.arnav.music.core.firebase.RemoteConfigRepository
import com.arnav.music.core.local.LocalMediaSource
import com.arnav.music.core.perf.PerformanceManager
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.playback.YouTubeEngine
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.core.repo.SearchRepository
import com.arnav.music.core.security.SecureStore
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.core.youtube.YouTubeApi
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.ui.AppViewModel
import com.arnav.music.feature.auth.AuthViewModel
import com.arnav.music.feature.explore.ExploreViewModel
import com.arnav.music.feature.home.HomeViewModel
import com.arnav.music.feature.library.LibraryViewModel
import com.arnav.music.feature.ai.ArnavAiViewModel
import com.arnav.music.feature.insights.InsightsViewModel
import com.arnav.music.feature.settings.SettingsViewModel
import com.arnav.music.feature.collection.CollectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

val appModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single { Clock.System }
    single { Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true } }
    single {
        OkHttpClient.Builder()
            .cache(Cache(File(androidContext().cacheDir, "http"), 20L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    single { ArnavDatabase.build(androidContext()) }
    single { SettingsRepository(androidContext(), get()) }
    single { SecureStore(androidContext()) }
    single { NetworkMonitor(androidContext(), get()) }
    single { UsageMeter(androidContext(), get()) }
    single { FirebaseGate(androidContext()) }
    single { RemoteConfigRepository(get()) }
    single { Analytics(androidContext(), get()) }
    single { AuthRepository(get()) }
    single { com.arnav.music.core.backup.UserDataArchive(androidContext(), get(), get()) }
    single { com.arnav.music.core.firebase.CloudVault(androidContext(), get()) }
    single { CloudSync(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    single { PerformanceManager(androidContext(), get(), get()) }
    single { LocalMediaSource(androidContext()) }
    single {
        val secure: SecureStore = get()
        val ctx = androidContext()
        val sha1 = certificateSha1(ctx)
        YouTubeApi(
            client = get(), json = get(),
            apiKey = { secure.get(SecureStore.YOUTUBE_API_KEY)?.takeIf { it.isNotBlank() } ?: BuildConfig.YOUTUBE_API_KEY },
            packageName = ctx.packageName,
            certSha1 = { sha1 },
        )
    }
    single {
        val settings: SettingsRepository = get()
        val remote: RemoteConfigRepository = get()
        YouTubeRepository(
            api = get(), searchDao = get<ArnavDatabase>().search(), trackDao = get<ArnavDatabase>().tracks(),
            usage = get(), json = get(), clock = get(),
            dailyBudget = { settings.settings.value.youtubeDailyBudget.takeIf { it > 0 } ?: remote.tunables.value.youtubeDefaultBudget },
            region = { settings.settings.value.regionCode.ifBlank { java.util.Locale.getDefault().country } },
        )
    }
    single { LibraryRepository(get(), get(), get(), get(), get()) }
    single {
        val settings: SettingsRepository = get()
        SearchRepository(get(), get(), get<ArnavDatabase>().search(), get(), get(), preferVideos = { settings.settings.value.preferVideos })
    }
    single { AiGateway(get(), get(), get(), get<ArnavDatabase>().aiCache(), get(), get()) }
    single { IntelligenceRepository(get(), get(), get(), get(), get(), get()) }
    single { YouTubeEngine(androidContext()) }
    single { com.arnav.music.core.update.UpdateManager(androidContext(), get(), get()) }
    single { com.arnav.music.core.youtube.UploadResolver(get()) }
    single { com.arnav.music.core.youtube.YouTubeImporter(get(), get(), get()) }
    single {
        val settings: SettingsRepository = get()
        com.arnav.music.core.lyrics.LyricsRepository(
            androidContext(), get<ArnavDatabase>().lyrics(),
            online = com.arnav.music.core.lyrics.LrclibClient(get(), "ArnavMusic/${com.arnav.music.BuildConfig.VERSION_NAME} (https://github.com/Arnav-Dugad/Arnav-Music)"),
            onlineEnabled = { settings.settings.value.onlineLyrics },
            features = get<ArnavDatabase>().audioFeatures(),
        )
    }
    single { com.arnav.music.core.lyrics.AiLyrics(androidContext(), get(), get(), get()) }
    single { com.arnav.music.core.importer.FileImporter(androidContext()) }
    single { com.arnav.music.core.importer.ImportMatcher(androidContext(), get(), get(), get(), get()) }
    single { PlaybackController(androidContext(), get(), get(), get(), get(), get(), get()) }

    viewModel { AppViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AuthViewModel(get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { ExploreViewModel(get(), get(), get(), get()) }
    viewModel { LibraryViewModel(get(), get(), get()) }
    viewModel { com.arnav.music.feature.duplicates.DuplicatesViewModel(androidContext(), get()) }
    viewModel { com.arnav.music.feature.imports.ImportHistoryViewModel(get(), get()) }
    viewModel { com.arnav.music.feature.library.YouTubeImportViewModel(get()) }
    viewModel { ArnavAiViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { InsightsViewModel(get(), get()) }
    viewModel { com.arnav.music.feature.insights.ListeningStatsViewModel(androidContext(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { CollectionViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { com.arnav.music.feature.library.PlaylistImportViewModel(androidContext(), get(), get(), get(), get(), get()) }
    viewModel { com.arnav.music.feature.moments.MomentViewModel(get(), get()) }
}

/** SHA-1 of the signing cert, sent with API calls so an Android-restricted API key works. */
private fun certificateSha1(ctx: android.content.Context): String? = runCatching {
    val pm = ctx.packageManager
    val sig = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    } ?: return@runCatching null
    MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
}.getOrNull()
