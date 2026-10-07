package com.arnav.music.core.system

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arnav.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Home-screen shortcuts for playlists.
 * - [pinPlaylist] / [pinSmart]: asks the launcher to pin a shortcut (the system shows its own
 *   confirmation). The icon is an adaptive bitmap made from the cover, with a fallback glyph.
 * - [reportOpened]: keeps up to [MAX_RECENT] dynamic shortcuts (long-press on the app icon) for
 *   the most recently opened playlists.
 *
 * Shortcuts are plain deep links (`arnavmusic://playlist/<id>`, `arnavmusic://smart/<KIND>`) handled
 * by MainActivity, so they never start playback by themselves.
 */
object Shortcuts {
    const val MAX_RECENT = 4

    private const val PLAYLIST_PREFIX = "playlist_"
    private const val SMART_PREFIX = "smart_"
    private const val PREFS = "shortcuts"
    private const val KEY_RECENT = "recent"

    /** True when the launcher supports pinning; check before showing a "Add to home screen" action. */
    fun canPin(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Requests a pinned shortcut for an Arnav playlist. False when the launcher can't pin. */
    suspend fun pinPlaylist(context: Context, id: String, name: String, artworkUrl: String?): Boolean =
        pin(context, PLAYLIST_PREFIX + id, playlistUri(id), name, artworkUrl)

    /** Requests a pinned shortcut for a smart playlist ([kind] = `SmartPlaylist.name`). */
    suspend fun pinSmart(context: Context, kind: String, name: String, artworkUrl: String?): Boolean =
        pin(context, SMART_PREFIX + kind, smartUri(kind), name, artworkUrl)

    /** Pushes a dynamic shortcut for a playlist the user just opened and keeps only the newest [MAX_RECENT]. */
    suspend fun reportOpened(context: Context, id: String, name: String, artworkUrl: String?, smart: Boolean = false) {
        val app = context.applicationContext
        val shortcutId = (if (smart) SMART_PREFIX else PLAYLIST_PREFIX) + id
        val uri = if (smart) smartUri(id) else playlistUri(id)
        runCatching {
            val info = build(app, shortcutId, uri, name, artworkUrl, rank = 0)
            withContext(Dispatchers.IO) {
                ShortcutManagerCompat.pushDynamicShortcut(app, info)
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val recent = (listOf(shortcutId) + prefs.getString(KEY_RECENT, "").orEmpty().split('\n'))
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(MAX_RECENT)
                prefs.edit().putString(KEY_RECENT, recent.joinToString("\n")).apply()
                val stale = ShortcutManagerCompat.getDynamicShortcuts(app)
                    .map { it.id }
                    .filter { (it.startsWith(PLAYLIST_PREFIX) || it.startsWith(SMART_PREFIX)) && it !in recent }
                if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(app, stale)
            }
        }
    }

    /** Drops the dynamic shortcut and disables any pinned one (e.g. after the playlist was deleted). */
    fun removePlaylist(context: Context, id: String) {
        val app = context.applicationContext
        val shortcutId = PLAYLIST_PREFIX + id
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(shortcutId))
            ShortcutManagerCompat.disableShortcuts(app, listOf(shortcutId), "This playlist was deleted")
        }
    }

    fun playlistUri(id: String): String = "arnavmusic://playlist/" + Uri.encode(id)
    fun smartUri(kind: String): String = "arnavmusic://smart/" + Uri.encode(kind)

    private suspend fun pin(context: Context, shortcutId: String, uri: String, name: String, artworkUrl: String?): Boolean {
        val app = context.applicationContext
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(app)) return false
        return runCatching {
            val info = build(app, shortcutId, uri, name, artworkUrl, rank = null)
            withContext(Dispatchers.Main) { ShortcutManagerCompat.requestPinShortcut(app, info, null) }
        }.getOrDefault(false)
    }

    private suspend fun build(context: Context, shortcutId: String, uri: String, name: String, artworkUrl: String?, rank: Int?): ShortcutInfoCompat {
        val label = name.trim().ifEmpty { "Playlist" }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
        val builder = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(25))
            .setLongLabel(label.take(50))
            .setIcon(icon(context, artworkUrl))
            .setIntent(intent)
        if (rank != null) builder.setRank(rank)
        return builder.build()
    }

    // region icon

    /** Adaptive icon (108 dp canvas): the cover center-cropped into the 72 dp safe zone, or a fallback glyph. */
    private suspend fun icon(context: Context, artworkUrl: String?): IconCompat {
        val size = (108 * context.resources.displayMetrics.density).roundToInt().coerceIn(108, 432)
        val cover = artworkUrl?.let { loadBitmap(context, it, size) }
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { if (cover != null) fromCover(cover, size) else fallback(context, size) }.getOrNull()
        }
        return bitmap?.let { IconCompat.createWithAdaptiveBitmap(it) } ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
    }

    private suspend fun loadBitmap(context: Context, url: String, size: Int): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
        (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()

    private fun fromCover(cover: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Background (only visible at the edges while the launcher animates the icon): the cover's average colour, darkened.
        val avg = Bitmap.createScaledBitmap(cover, 1, 1, true).getPixel(0, 0)
        canvas.drawColor(Color.rgb((Color.red(avg) * 0.6f).toInt(), (Color.green(avg) * 0.6f).toInt(), (Color.blue(avg) * 0.6f).toInt()))
        // Center-crop the cover to a square, drawn into the safe zone (inner 72 of 108 dp).
        val side = minOf(cover.width, cover.height)
        val src = Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        val inset = size / 6f
        canvas.drawBitmap(cover, src, RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun fallback(context: Context, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), ContextCompat.getColor(context, R.color.icon_bg_start), ContextCompat.getColor(context, R.color.icon_bg_end), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        ContextCompat.getDrawable(context, R.drawable.ic_shortcut_playlist)?.let { glyph ->
            val g = (size * 0.4f).roundToInt()
            val left = (size - g) / 2
            glyph.setBounds(left, left, left + g, left + g)
            glyph.draw(canvas)
        }
        return out
    }

    // endregion
}
