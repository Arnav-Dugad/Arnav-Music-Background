package com.arnav.music.feature.insights

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Manrope
import com.arnav.music.ui.theme.Space
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.hypot

/**
 * Taste Constellation: artists as stars, co-listening + shared styles as light threads.
 * Pure Canvas — pan, pinch-zoom, tap a star to visit the artist.
 */
@Composable
fun ConstellationScreen(vm: InsightsViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val graph by vm.graph.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadGraph() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var focused by remember { mutableStateOf<Int?>(null) }
    val reveal = remember { Animatable(0f) }
    val motion = ArnavTheme.motion
    LaunchedEffect(graph) { if (graph != null) reveal.animateTo(1f, motion.cinematic()) }

    Box(Modifier.fillMaxSize()) {
        val g = graph
        if (g != null && g.nodes.isNotEmpty()) {
            val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
            val typeface = remember { android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD) }
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "Constellation of ${g.nodes.size} artists. Top: ${g.nodes.sortedByDescending { it.weight }.take(3).joinToString { it.label }}" }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, p, z, _ ->
                            zoom = (zoom * z).coerceIn(0.4f, 4f)
                            pan += p
                        }
                    }
                    .pointerInput(g) {
                        detectTapGestures(
                            onDoubleTap = { zoom = 1f; pan = Offset.Zero; focused = null },
                        ) { tap ->
                            val cx = size.width / 2f + pan.x
                            val cy = size.height / 2f + pan.y
                            val hit = g.nodes.indices.minByOrNull { i -> hypot(cx + g.nodes[i].x * zoom - tap.x, cy + g.nodes[i].y * zoom - tap.y) }
                            if (hit != null) {
                                val n = g.nodes[hit]
                                val d = hypot(cx + n.x * zoom - tap.x, cy + n.y * zoom - tap.y)
                                if (d < 40f) { if (focused == hit) nav.go(Routes.artist(n.label)) else focused = hit } else focused = null
                            }
                        }
                    },
            ) {
                drawRect(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.10f), c.background), center = center, radius = size.maxDimension * 0.7f))
                val r = reveal.value
                withTransform({ translate(size.width / 2 + pan.x, size.height / 2 + pan.y); scale(zoom * (0.85f + 0.15f * r), zoom * (0.85f + 0.15f * r), Offset.Zero) }) {
                    g.edges.forEach { e ->
                        val a = g.nodes[e.a]; val b = g.nodes[e.b]
                        val lit = focused == null || focused == e.a || focused == e.b
                        drawLine(c.accent.copy(alpha = (0.08f + 0.35f * e.strength) * r * if (lit) 1f else 0.25f), Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = (0.6f + 2.2f * e.strength) / zoom)
                    }
                    g.nodes.forEachIndexed { i, n ->
                        val rad = (5f + 16f * n.weight)
                        val dim = focused != null && focused != i && g.edges.none { (it.a == focused && it.b == i) || (it.b == focused && it.a == i) }
                        val alpha = r * if (dim) 0.3f else 1f
                        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.45f * alpha), Color.Transparent), Offset(n.x, n.y), rad * 3), rad * 3, Offset(n.x, n.y))
                        drawCircle(Color.White.copy(alpha = 0.92f * alpha), rad * 0.45f, Offset(n.x, n.y))
                        if (n.weight > 0.35f || zoom > 1.6f || focused == i) {
                            labelPaint.color = c.content.copy(alpha = alpha).toArgb()
                            labelPaint.textSize = (11f + 7f * n.weight) * density / zoom.coerceAtLeast(0.8f)
                            labelPaint.typeface = typeface
                            drawContext.canvas.nativeCanvas.drawText(n.label, n.x, n.y + rad + labelPaint.textSize + 2f, labelPaint)
                        }
                    }
                }
            }
        } else if (g != null) {
            EmptyState(Icons.Rounded.Hub, "Your universe is still forming", "Play music from a few different artists and your constellation will appear.", Modifier.align(Alignment.Center))
        }
        Row(Modifier.statusBarsPadding().padding(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back)
            Column(Modifier.weight(1f)) {
                Text("Taste constellation", style = ArnavTheme.type.title, color = c.content)
                Text("Pinch to zoom · tap a star twice to visit", style = ArnavTheme.type.caption, color = c.contentMuted)
            }
            ArnavIconButton(Icons.Rounded.CenterFocusStrong, "Recenter", { zoom = 1f; pan = Offset.Zero; focused = null })
        }
    }
}

@Suppress("unused") private val unusedFont = Manrope
@Suppress("unused") private val unusedDp = 0.dp
