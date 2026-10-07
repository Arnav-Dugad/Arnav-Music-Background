package com.arnav.music.ui.pip

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import com.arnav.music.R
import com.arnav.music.core.playback.PlaybackController
import org.koin.core.context.GlobalContext

/** What the floating (picture-in-picture) player should look like right now. */
data class PipSpec(
    /** Auto-enter when the user leaves the app: on only while something is playing and the setting allows it. */
    val enabled: Boolean,
    val playing: Boolean,
    val video: Boolean,
    val hasNext: Boolean,
)

/**
 * Picture-in-picture is Arnav Music's floating mini-player. For YouTube it keeps the official player
 * visible while you use other apps (background playback stays off: closing the window stops it).
 */
object Pip {
    const val ACTION = "com.arnav.music.PIP_CONTROL"
    const val EXTRA = "control"
    private const val PLAY_PAUSE = 1
    private const val NEXT = 2
    private const val PREVIOUS = 3

    @RequiresApi(26)
    fun params(context: Context, spec: PipSpec): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(if (spec.video) Rational(16, 9) else Rational(1, 1))
            .setActions(
                listOf(
                    action(context, PREVIOUS, R.drawable.ic_w_prev, "Previous"),
                    if (spec.playing) action(context, PLAY_PAUSE, R.drawable.ic_w_pause, "Pause") else action(context, PLAY_PAUSE, R.drawable.ic_w_play, "Play"),
                    action(context, NEXT, R.drawable.ic_w_next, "Next").apply { isEnabled = spec.hasNext },
                ),
            )
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(spec.enabled)
            // Video keeps its frame while resizing; artwork can resize seamlessly.
            b.setSeamlessResizeEnabled(!spec.video)
        }
        return b.build()
    }

    @RequiresApi(26)
    private fun action(context: Context, control: Int, icon: Int, title: String): RemoteAction {
        val intent = Intent(ACTION).setPackage(context.packageName).setClass(context, PipActionReceiver::class.java).putExtra(EXTRA, control)
        val pending = PendingIntent.getBroadcast(context, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(context, icon), title, title, pending)
    }

    internal fun handle(control: Int) {
        val player = runCatching { GlobalContext.get().get<PlaybackController>() }.getOrNull() ?: return
        when (control) {
            PLAY_PAUSE -> player.togglePlay()
            NEXT -> player.next()
            PREVIOUS -> player.previous()
        }
    }
}

/** Receives the play/pause/skip buttons shown on the picture-in-picture window. */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Pip.ACTION) return
        Pip.handle(intent.getIntExtra(Pip.EXTRA, 0))
    }
}
