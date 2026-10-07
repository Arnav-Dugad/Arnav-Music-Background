package com.arnav.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arnav.music.ui.AppViewModel
import com.arnav.music.ui.ArnavAppRoot
import com.arnav.music.ui.DeepLink
import org.koin.compose.viewmodel.koinViewModel
import org.koin.android.ext.android.inject
import com.arnav.music.core.settings.SettingsRepository
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.domain.model.SourceType
import com.arnav.music.ui.pip.Pip
import com.arnav.music.ui.pip.PipSpec
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val settingsRepo: SettingsRepository by inject()
    private val deepLink = mutableStateOf<DeepLink?>(null)
    private val openPlayer = mutableStateOf(false)
    private val player: PlaybackController by inject()
    /** True while the app is shown as the floating picture-in-picture player. */
    private val inPip = mutableStateOf(false)
    private var pipSpec = PipSpec(enabled = false, playing = false, video = false, hasNext = false)
    private val pipSupported by lazy { Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Hold the system splash until settings are read, so returning users never see onboarding flash.
        splash.setKeepOnScreenCondition { !settingsRepo.loaded.value }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        if (pipSupported) {
            lifecycleScope.launch {
                combine(player.state, settingsRepo.settings) { st, set ->
                    PipSpec(
                        enabled = set.floatingPlayer && st.isPlaying && st.current != null &&
                            (st.current?.source != SourceType.YOUTUBE || st.current?.variant == com.arnav.music.domain.model.MediaVariant.VIDEO),
                        playing = st.isPlaying,
                        video = st.current?.source == SourceType.YOUTUBE &&
                            !(set.cropArtTracks && st.current?.variant == com.arnav.music.domain.model.MediaVariant.SONG),
                        hasNext = st.queue.hasNext,
                    )
                }.distinctUntilChanged().collect { spec ->
                    pipSpec = spec
                    if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(Pip.params(this@MainActivity, spec)) }
                }
            }
        }
        setContent {
            val vm: AppViewModel = koinViewModel()
            ArnavAppRoot(
                vm = vm,
                deepLink = deepLink.value,
                onDeepLinkHandled = { deepLink.value = null },
                openPlayer = openPlayer.value,
                onOpenPlayerHandled = { openPlayer.value = false },
                pip = inPip.value,
            )
        }
    }

    /** Android 8–11: enter the floating player when the user leaves mid-song (12+ auto-enters via params). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipSupported && Build.VERSION.SDK_INT in 26..30 && pipSpec.enabled) {
            runCatching { enterPictureInPictureMode(Pip.params(this, pipSpec)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
            Regex("""https?://\S+""").find(t)?.value?.let(android.net.Uri::parse)
        } else null
        DeepLink.parse(shared ?: intent.data)?.let { deepLink.value = it }
    }

    companion object { const val EXTRA_OPEN_PLAYER = "open_player" }
}
