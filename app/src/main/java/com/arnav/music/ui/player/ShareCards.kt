package com.arnav.music.ui.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arnav.music.R
import com.arnav.music.domain.color.ArtworkCorrector
import com.arnav.music.domain.color.SurfaceMode
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a 1080×1350 share card entirely on device (no backend): cover, title, artist, a small
 * Arnav Music mark, and the real source link. It never implies Arnav Music owns the music.
 */
object ShareCards {
    suspend fun share(context: Context, track: Track) {
        val file = withContext(Dispatchers.Default) { render(context, track) }
        val link = when (track.source) {
            SourceType.YOUTUBE -> "https://music.youtube.com/watch?v=${track.playbackRef}"
            SourceType.LOCAL -> null
        }
        val text = buildString {
            append("${track.title} — ${track.artist}")
            if (link != null) append("\n").append(link)
            append("\nShared from Arnav Music")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            if (file != null) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".shares", file)
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else type = "text/plain"
        }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun render(context: Context, track: Track): File? = runCatching {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cover = track.artworkUrl?.let { url ->
            (context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(900).allowHardware(false).build()) as? SuccessResult)?.image?.toBitmap()
        }
        val dominant = cover?.let { androidx.palette.graphics.Palette.from(it).generate().getDominantColor(0xFF2B2D42.toInt()) } ?: 0xFF2B2D42.toInt()
        val pal = ArtworkCorrector.correct(dominant, dominant, null, SurfaceMode.DARK)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), pal.backdrop, pal.backdropSecondary, Shader.TileMode.CLAMP)
        })
        val art = RectF(120f, 140f, 960f, 980f)
        val clip = Path().apply { addRoundRect(art, 48f, 48f, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        if (cover != null) canvas.drawBitmap(cover, null, art, Paint(Paint.FILTER_BITMAP_FLAG))
        else canvas.drawRect(art, Paint().apply { color = pal.accent })
        canvas.restore()
        val bold = ResourcesCompat.getFont(context, R.font.manrope_extrabold) ?: Typeface.DEFAULT_BOLD
        val medium = ResourcesCompat.getFont(context, R.font.manrope_medium) ?: Typeface.DEFAULT
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.onBackdrop; textSize = 64f; typeface = bold }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.onBackdropMuted; textSize = 42f; typeface = medium }
        canvas.drawText(ellipsize(track.title, title, 840f), 120f, 1090f, title)
        canvas.drawText(ellipsize(track.artist, sub, 840f), 120f, 1150f, sub)
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent; textSize = 32f; typeface = bold }
        canvas.drawText("Arnav Music", 120f, 1260f, brand)
        val src = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.onBackdropMuted; textSize = 28f; typeface = medium; textAlign = Paint.Align.RIGHT }
        canvas.drawText(if (track.source == SourceType.YOUTUBE) "Listen on YouTube Music" else "From my library", 960f, 1260f, src)
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val out = File(dir, "arnav-share.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bmp.recycle()
        out
    }.getOrNull()

    private fun ellipsize(text: String, paint: Paint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > max) end--
        return text.substring(0, end) + "…"
    }
}
