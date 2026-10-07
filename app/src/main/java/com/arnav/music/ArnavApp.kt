package com.arnav.music

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.arnav.music.core.chapters.chaptersModule
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.firebase.FirebaseGate
import com.arnav.music.core.firebase.RemoteConfigRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.di.appModule
import com.arnav.music.widget.LyricsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ArnavApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ArnavApp)
            modules(appModule, chaptersModule, com.arnav.music.core.metadata.metadataModule)
        }
        // Cloud work is deferred off the launch path; the UI never waits on Firebase.
        val scope: CoroutineScope = get()
        // Keep the home-screen widgets in sync with playback (debounced; main thread for the player).
        // Widgets render a lightweight snapshot and route user transport actions to the playback controller.
        scope.launch {
            val player: com.arnav.music.core.playback.PlaybackController = get()
            kotlinx.coroutines.flow.combine(player.state,
                player.progress.map { it.positionMs / 10_000L }.distinctUntilChanged()) { state, _ -> state }
                .map { s ->
                    listOf(
                        s.current?.id?.value, s.current?.title, s.isPlaying, s.queue.hasNext,
                        com.arnav.music.widget.WidgetQueueItem.key(s.queue), player.progress.value.positionMs / 10_000L,
                        s.current?.artist, s.current?.artworkUrl,
                    ) to s
                }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .debounce(400)
                .collect { (_, s) ->
                    val t = s.current
                    val upNext = com.arnav.music.widget.WidgetQueueItem.upNext(s.queue)
                    launch(Dispatchers.IO) {
                        com.arnav.music.widget.ArnavWidget.refresh(
                            this@ArnavApp, t?.title, t?.artist, t?.artworkUrl, s.isPlaying,
                            t?.source == com.arnav.music.domain.model.SourceType.YOUTUBE, s.queue.hasNext, upNext, player.progress.value.positionMs, player.progress.value.durationMs,
                        )
                    }
                }
        }
        scope.launch(Dispatchers.IO) { com.arnav.music.widget.Widgets.publishPreviews(this@ArnavApp) }
        // Lyrics widget: publishes the current/next synced line, only while one is placed.
        LyricsWidget.Feed.start(this, scope)
        // Refresh the "Your week" widget whenever the app goes to the background (no-op when not placed).
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                scope.launch(Dispatchers.IO) { runCatching { com.arnav.music.widget.RecapWidget.refresh(this@ArnavApp) } }
            }
        })
        scope.launch(Dispatchers.Default) {
            val gate: FirebaseGate = get()
            gate.ensure()
            val settings: SettingsRepository = get()
            val analytics: Analytics = get()
            launch { settings.settings.map { it.analytics }.distinctUntilChanged().collect { analytics.setEnabled(it) } }
            launch { settings.settings.map { it.weeklyRecapNotification }.distinctUntilChanged().collect { com.arnav.music.core.notify.RecapWorker.schedule(this@ArnavApp, it) } }
            launch { settings.settings.map { it.analyzeLocalAudio }.distinctUntilChanged().collect { com.arnav.music.core.analysis.AnalysisWorker.schedule(this@ArnavApp, it) } }
            launch {
                settings.settings.map { it.autoUpdate }.distinctUntilChanged().collect { on ->
                    com.arnav.music.core.update.UpdateWorker.schedule(this@ArnavApp, on)
                    // Foreground check on launch (at most every 6 h) so the banner appears promptly.
                    val updates: com.arnav.music.core.update.UpdateManager = get()
                    if (on && updates.isDue()) updates.check()
                }
            }
            launch { com.arnav.music.core.importer.MatchWorker.ensureScheduled(this@ArnavApp, get()) }
            launch { settings.settings.map { it.autoTagLocal }.distinctUntilChanged().collect { com.arnav.music.core.metadata.AutoTagWorker.schedule(this@ArnavApp, it) } }
            // Widgets restyle immediately when the Material You setting changes.
            launch {
                settings.settings.map { it.widgetMaterialYou }.distinctUntilChanged().drop(1)
                    .collect { com.arnav.music.widget.Widgets.refreshAll(this@ArnavApp, it) }
            }
            get<RemoteConfigRepository>().refresh()
            get<CloudSync>().startObserving()
            get<CloudSync>().schedulePeriodic()
        }
    }

    /** Artwork pipeline: shared OkHttp, 25% memory cache, 256 MB disk cache, gentle crossfade. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { get<OkHttpClient>() })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(220)
        .build()
}
