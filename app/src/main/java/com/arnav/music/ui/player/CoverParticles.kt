package com.arnav.music.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.model.Track
import com.arnav.music.domain.model.TrackId
import com.arnav.music.ui.components.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Particles per side of the grid (40 × 40 = 1,600 per cover). */
private const val GRID = 40
private const val SWAP_MS = 820

/**
 * Tiny colour grids of covers (GRID × GRID ARGB), decoded off the main thread with Coil and cached,
 * so the outgoing cover's colours are ready the moment the song changes.
 */
object CoverSampler {
    private val cache = LruCache<String, IntArray>(24)

    fun peek(url: String?): IntArray? = url?.let { cache.get(it) }

    suspend fun sample(context: Context, url: String?): IntArray? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).size(GRID * 2).allowHardware(false).build()
                val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
                val src = result.image.toBitmap()
                // Centre-crop to a square (the cover is shown cropped too), then shrink to the grid.
                val side = min(src.width, src.height).coerceAtLeast(1)
                val square = if (src.width == src.height) src else Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val small = Bitmap.createScaledBitmap(square, GRID, GRID, true)
                val px = IntArray(GRID * GRID)
                small.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
                px
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }
}

/**
 * Precomputed particle field: the outgoing cover's grid scatters and fades with a little turbulence
 * while the incoming grid flies into place. All arrays are filled once per change; drawing allocates
 * nothing (one reused Paint, plain rects on the native canvas, one pass).
 */
internal class ParticleField {
    private val n = GRID * GRID
    private val outDelay = FloatArray(n); private val outDx = FloatArray(n); private val outDy = FloatArray(n)
    private val inDelay = FloatArray(n); private val inDx = FloatArray(n); private val inDy = FloatArray(n)
    private val phase = FloatArray(n)
    private val outColor = IntArray(n); private val inColor = IntArray(n)
    var hasOut = false
        private set
    var hasIn = false
        private set
    private val paint = android.graphics.Paint().apply { isAntiAlias = false; style = android.graphics.Paint.Style.FILL }

    fun prepare(old: IntArray?, new: IntArray?, forward: Boolean, seed: Int) {
        hasOut = old != null && old.size == n
        hasIn = new != null && new.size == n
        if (hasOut) old!!.copyInto(outColor)
        if (hasIn) new!!.copyInto(inColor)
        val rnd = Random(seed)
        val dir = if (forward) -1f else 1f
        for (i in 0 until n) {
            val gx = (i % GRID) / (GRID - 1f)
            val gy = (i / GRID) / (GRID - 1f)
            // The old cover peels away from the side it's leaving towards; the new one arrives from the other.
            val sweepOut = if (forward) gx else 1f - gx
            outDelay[i] = 0.2f * sweepOut + 0.08f * rnd.nextFloat() + 0.04f * gy
            outDx[i] = dir * (0.18f + 0.5f * rnd.nextFloat())
            outDy[i] = (rnd.nextFloat() - 0.5f) * 0.55f - 0.08f
            inDelay[i] = 0.30f + 0.14f * (1f - sweepOut) + 0.06f * rnd.nextFloat()
            inDx[i] = -dir * (0.25f + 0.55f * rnd.nextFloat())
            inDy[i] = (rnd.nextFloat() - 0.5f) * 0.7f
            phase[i] = rnd.nextFloat() * 6.2832f
        }
    }

    fun draw(scope: DrawScope, t: Float) = with(scope) {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@with
        val cellX = w / GRID; val cellY = h / GRID
        val wobble = w * 0.035f
        // The real new cover takes over at the very end; the settled grid fades under it.
        val handover = 1f - ((t - 0.86f) / 0.14f).coerceIn(0f, 1f)
        val appear = (t / 0.08f).coerceIn(0f, 1f)
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            for (i in 0 until n) {
                val hx = (i % GRID) * cellX
                val hy = (i / GRID) * cellY
                if (hasOut) {
                    val u = ((t - outDelay[i]) / 0.5f).coerceIn(0f, 1f)
                    if (u < 1f) {
                        val e = u * u
                        val x = hx + outDx[i] * e * w + sin(phase[i] + u * 7f) * wobble * u
                        val y = hy + outDy[i] * e * w + cos(phase[i] * 1.3f + u * 6f) * wobble * u
                        val s = 1.04f - 0.5f * u
                        val a = (1f - u) * appear
                        if (a > 0.01f) rect(canvas, x, y, cellX * s, cellY * s, outColor[i], a)
                    }
                }
                if (hasIn) {
                    val v = ((t - inDelay[i]) / 0.5f).coerceIn(0f, 1f)
                    if (v > 0f) {
                        val k = 1f - v
                        val e = 1f - k * k * k
                        val x = hx + inDx[i] * (1f - e) * w + sin(phase[i] + v * 5f) * wobble * k
                        val y = hy + inDy[i] * (1f - e) * w + cos(phase[i] * 0.7f + v * 5f) * wobble * k
                        val s = 0.5f + 0.54f * e
                        val a = min(1f, v * 1.6f) * handover
                        if (a > 0.01f) rect(canvas, x, y, cellX * s, cellY * s, inColor[i], a)
                    }
                }
            }
        }
    }

    private fun rect(canvas: android.graphics.Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float) {
        val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        paint.color = (a shl 24) or (color and 0x00FFFFFF)
        canvas.drawRect(x, y, x + w + 0.5f, y + h + 0.5f, paint)
    }
}

/**
 * Coordinates the particle swap of the Now Playing cover. [shown] is the cover the swap considers
 * current; while a newer track waits for its particles it stays hidden ([alpha] = 0) and the
 * overlay keeps the old one on screen, so there is never a frame of the new cover popping in.
 */
@Stable
class CoverSwap internal constructor(initial: Track) {
    internal val t = Animatable(1f)
    internal val particles = ParticleField()
    var shown by mutableStateOf(initial)
        internal set
    /** The cover that is dissolving (crisp copy fades out above its particles). */
    var outgoing by mutableStateOf<Track?>(null)
        internal set
    private var suppress by mutableStateOf(false)
    internal var revealFrom = 0.86f

    /** The next change was a swipe through the carousel: the cover already slid in, no particles. */
    fun suppressNext() { suppress = true }

    internal fun consumeSuppress(): Boolean = suppress.also { suppress = false }

    /** A change to [id] is about to run (YouTube covers never dissolve; they're not on this device). */
    private fun pending(id: TrackId, enabled: Boolean): Boolean =
        enabled && !suppress && id != shown.id && shown.source != SourceType.YOUTUBE

    /** Alpha of the real cover of [id]. Read inside a graphicsLayer / draw block. */
    fun alpha(id: TrackId, enabled: Boolean): Float {
        if (id != shown.id) return if (pending(id, enabled)) 0f else 1f
        if (outgoing == null) return 1f
        return ((t.value - revealFrom) / (1f - revealFrom).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    }

    /** True while the overlay has something to draw for [current]. */
    fun busy(current: Track, enabled: Boolean): Boolean =
        outgoing != null || pending(current.id, enabled)
}

/**
 * Runs the swap whenever [track] changes while [enabled]. Otherwise the swap only follows along, so the
 * regular cover transitions run untouched.
 */
@Composable
fun rememberCoverSwap(track: Track, enabled: Boolean, forward: Boolean, prefetch: Boolean = enabled): CoverSwap {
    val swap = remember { CoverSwap(track) }
    val context = LocalContext.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestForward by rememberUpdatedState(forward)
    // Keep the showing cover's colours ready for the next change.
    LaunchedEffect(swap.shown.artworkUrl, prefetch) { if (prefetch) CoverSampler.sample(context, swap.shown.artworkUrl) }
    LaunchedEffect(track.id) {
        val old = swap.shown
        if (old.id == track.id) return@LaunchedEffect
        val suppressed = swap.consumeSuppress()
        if (suppressed || !latestEnabled || old.source == SourceType.YOUTUBE) {
            swap.outgoing = null
            swap.shown = track
            return@LaunchedEffect
        }
        val oldPx = CoverSampler.peek(old.artworkUrl) ?: withTimeoutOrNull(120) { CoverSampler.sample(context, old.artworkUrl) }
        val newPx = withTimeoutOrNull(320) { CoverSampler.sample(context, track.artworkUrl) }
        swap.particles.prepare(oldPx, newPx, latestForward, track.id.hashCode())
        // Without the new colours there is nothing to assemble: the cover simply fades in sooner.
        swap.revealFrom = if (swap.particles.hasIn) 0.86f else 0.4f
        swap.t.snapTo(0f)
        swap.outgoing = old
        swap.shown = track
        try {
            swap.t.animateTo(1f, tween(SWAP_MS, easing = LinearEasing))
        } finally {
            swap.outgoing = null
        }
    }
    return swap
}

/**
 * The swap's own layer, placed exactly over the cover: the old cover (crisp, then fading) and the
 * particles. Particles may drift past the cover's edge. Handles no input.
 */
@Composable
fun CoverParticleLayer(swap: CoverSwap, current: Track, modifier: Modifier = Modifier, corner: Dp = 22.dp) {
    val old = swap.outgoing ?: swap.shown.takeIf { it.id != current.id } ?: return
    val running = swap.outgoing != null
    Box(modifier) {
        Artwork(
            old.artworkUrl, old.id.value,
            Modifier.fillMaxSize().graphicsLayer {
                alpha = if (!running) 1f else {
                    val fade = if (swap.particles.hasOut) 0.1f else 0.3f
                    (1f - swap.t.value / fade).coerceIn(0f, 1f)
                }
            },
            shape = RoundedCornerShape(corner), contentDescription = null, decodeSize = 1000,
        )
        if (running) Canvas(Modifier.fillMaxSize()) { swap.particles.draw(this, swap.t.value) }
    }
}
