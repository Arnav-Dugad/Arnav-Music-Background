package com.arnav.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import kotlin.math.absoluteValue

/**
 * Artwork with progressive reveal: a deterministic gradient placeholder (seeded by the key so the
 * same track always gets the same colours) that the real cover cross-fades over.
 */
@Composable
fun Artwork(
    url: String?,
    seed: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.artwork),
    contentDescription: String? = null,
    decodeSize: Int = 300,
) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(shape).background(placeholderBrush(seed)), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank() || failed) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(24.dp))
        } else {
            val ctx = LocalContext.current
            AsyncImage(
                model = remember(url, decodeSize) { ImageRequest.Builder(ctx).data(url).size(decodeSize).build() },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val seeds = listOf(
    0xFF5B5F97 to 0xFF2B2D42, 0xFF7B4B94 to 0xFF2D1E3B, 0xFF2E6F73 to 0xFF0F2A2C, 0xFF9C5B3B to 0xFF35190E,
    0xFF4A6FA5 to 0xFF16213A, 0xFF6B8F71 to 0xFF1E2B21, 0xFFA05470 to 0xFF331722, 0xFF5E548E to 0xFF1E1A33,
)

fun placeholderBrush(seed: String): Brush {
    val (a, b) = seeds[(seed.hashCode().absoluteValue) % seeds.size]
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

@Composable
fun Mosaic(urls: List<String>, seed: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Radius.artwork)) {
    if (urls.size < 4) { Artwork(urls.firstOrNull(), seed, modifier, shape); return }
    Box(modifier.clip(shape)) {
        androidx.compose.foundation.layout.Column {
            androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
                Artwork(urls[0], seed + 0, Modifier.weight(1f).fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, decodeSize = 160)
                Artwork(urls[1], seed + 1, Modifier.weight(1f).fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, decodeSize = 160)
            }
            androidx.compose.foundation.layout.Row(Modifier.weight(1f)) {
                Artwork(urls[2], seed + 2, Modifier.weight(1f).fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, decodeSize = 160)
                Artwork(urls[3], seed + 3, Modifier.weight(1f).fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, decodeSize = 160)
            }
        }
    }
}

@Suppress("unused")
fun Dp.half() = this / 2
