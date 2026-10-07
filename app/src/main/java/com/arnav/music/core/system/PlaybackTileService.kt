package com.arnav.music.core.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arnav.music.MainActivity
import com.arnav.music.R
import com.arnav.music.core.playback.PlaybackController
import com.arnav.music.core.playback.PlayerState
import com.arnav.music.domain.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Quick Settings tile. Toggles on-device playback; for YouTube (which can't play while the app
 * is hidden) or an empty queue it opens the app instead.
 */
class PlaybackTileService : TileService() {
    private var scope: CoroutineScope? = null

    private fun player(): PlaybackController? = runCatching { GlobalContext.get().get<PlaybackController>() }.getOrNull()

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val p = player() ?: run { render(null); return }
        s.launch {
            p.state
                .map { st -> TileModel(st.current?.title, st.current?.source, st.isPlaying) }
                .distinctUntilChanged()
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val p = player()
        val st: PlayerState? = p?.state?.value
        val current = st?.current
        when {
            p == null || st == null || current == null -> openApp()
            // Pausing anything is fine; only on-device audio may be started from here.
            st.isPlaying && current.source != SourceType.LOCAL -> p.pause()
            current.source == SourceType.LOCAL -> p.togglePlay()
            else -> openApp()
        }
        if (p != null && st != null) render(TileModel(p.state.value.current?.title, p.state.value.current?.source, p.state.value.isPlaying))
    }

    private fun render(m: TileModel?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_music)
        tile.state = if (m == null || m.title == null) Tile.STATE_UNAVAILABLE else if (m.playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = m?.title ?: getString(R.string.tile_nothing_playing)
        }
        tile.contentDescription = m?.title?.let { getString(R.string.tile_label) + ", " + it } ?: getString(R.string.tile_label)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        val start = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pi)
            } else {
                startActivityAndCollapseLegacy(intent)
            }
        }
        if (isLocked) unlockAndRun { runCatching { start() } } else runCatching { start() }
    }

    /** Pre-34 only: the Intent overload is deprecated (and throws when targeting 34+ on 34+). */
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private data class TileModel(val title: String?, val source: SourceType?, val playing: Boolean)
}
