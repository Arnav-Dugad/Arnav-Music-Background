package com.arnav.music.ui.artwork

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arnav.music.domain.color.ArtworkCorrector
import com.arnav.music.domain.color.ArtworkPalette
import com.arnav.music.domain.color.SurfaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts dominant / secondary / vibrant colours from a tiny (96 px) decode of the artwork
 * on a background thread, then hands them to the deterministic corrector for guaranteed contrast.
 */
object ArtworkEngine {
    private data class Swatches(val dominant: Int, val secondary: Int, val vibrant: Int?)
    private val cache = LruCache<String, Swatches>(96)

    suspend fun palette(context: Context, url: String?, mode: SurfaceMode): ArtworkPalette {
        if (url.isNullOrBlank()) return ArtworkPalette.neutral(mode)
        val sw = cache.get(url) ?: extract(context, url)?.also { cache.put(url, it) } ?: return ArtworkPalette.neutral(mode)
        return ArtworkCorrector.correct(sw.dominant, sw.secondary, sw.vibrant, mode)
    }

    private suspend fun extract(context: Context, url: String): Swatches? = withContext(Dispatchers.Default) {
        runCatching {
            val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
            val bitmap = result.image.toBitmap()
            val p = Palette.from(bitmap).maximumColorCount(16).generate()
            val dominant = p.dominantSwatch?.rgb ?: return@runCatching null
            val secondary = (p.darkMutedSwatch ?: p.mutedSwatch ?: p.darkVibrantSwatch)?.rgb ?: dominant
            val vibrant = (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch)?.rgb
            Swatches(dominant, secondary, vibrant)
        }.getOrNull()
    }
}

@Composable
fun rememberArtworkPalette(url: String?, mode: SurfaceMode): ArtworkPalette {
    val context = LocalContext.current
    var palette by remember(mode) { mutableStateOf(ArtworkPalette.neutral(mode)) }
    LaunchedEffect(url, mode) { palette = ArtworkEngine.palette(context, url, mode) }
    return palette
}
