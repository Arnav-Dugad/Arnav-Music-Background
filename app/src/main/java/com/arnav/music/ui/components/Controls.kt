package com.arnav.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Size
import com.arnav.music.ui.theme.Space
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.sin

/** Icon button with a 48dp target, press compression and a haptic tick. */
@Composable
fun ArnavIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ArnavTheme.colors.content,
    size: Dp = Size.icon,
    enabled: Boolean = true,
    container: Color = Color.Transparent,
) {
    val interaction = rememberInteraction()
    val haptics = ArnavTheme.haptics
    Box(
        modifier
            .size(Space.touch)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .background(container)
            .clickable(interaction, indication = androidx.compose.material3.ripple(bounded = false, radius = 24.dp), enabled = enabled, role = Role.Button, onClickLabel = contentDescription) {
                haptics.press(); onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size))
    }
}

/**
 * Favourite micro-interaction: heart pops with an overshoot, and a ring of 6 sparks bursts
 * outward once on like. Unlike is a quiet shrink — no celebration for removal.
 */
@Composable
fun HeartButton(liked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = Size.icon, tint: Color = ArnavTheme.colors.content) {
    val accent = ArnavTheme.colors.accent
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var likeCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(likeCount) {
        if (likeCount == 0 || motion.reduced) return@LaunchedEffect
        burst.snapTo(0f)
        pop.snapTo(0.6f)
        kotlinx.coroutines.coroutineScope {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
            launch { burst.animateTo(1f, androidx.compose.animation.core.tween(480)) }
        }
    }
    val interaction = rememberInteraction()
    Box(
        modifier
            .size(Space.touch)
            .pressScale(interaction, 0.85f)
            .clickable(interaction, indication = null, role = Role.Switch) {
                if (!liked) { likeCount++; haptics.favorite() } else haptics.select()
                onToggle()
            }
            .semantics {
                contentDescription = "Like"
                stateDescription = if (liked) "Liked" else "Not liked"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 2.2f)) {
            val t = burst.value
            if (t < 1f) {
                val r = size.toPx() * (0.6f + 0.7f * t)
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0 - 90.0)
                    drawCircle(accent.copy(alpha = 1f - t), radius = 3.dp.toPx() * (1f - t * 0.6f), center = center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()))
                }
            }
        }
        // Morph: the outline warms to the accent while the fill grows out from the heart's centre.
        val fill by animateFloatAsState(
            if (liked) 1f else 0f,
            if (motion.reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 420f),
            label = "heartFill",
        )
        Box(Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.FavoriteBorder, null, tint = lerp(tint, accent, fill.coerceIn(0f, 1f)), modifier = Modifier.size(size))
            if (fill > 0.01f) {
                Icon(
                    Icons.Rounded.Favorite, null, tint = accent,
                    modifier = Modifier.size(size).graphicsLayer {
                        val f = fill.coerceIn(0f, 1.15f)
                        scaleX = f; scaleY = f
                        alpha = fill.coerceIn(0f, 1f)
                    },
                )
            }
        }
    }
}

/**
 * Play ⇄ pause as one shape: the triangle's two halves slide and square off into the two bars
 * (and back), so the control never swaps icons abruptly.
 */
@Composable
fun PlayPauseGlyph(morph: Float, color: Color, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier) {
        val t = morph.coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        fun quad(play: FloatArray, pause: FloatArray) {
            path.reset()
            for (i in 0 until 4) {
                val x = lerp(play[i * 2], pause[i * 2], t) * w
                val y = lerp(play[i * 2 + 1], pause[i * 2 + 1], t) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color)
            // A hairline stroke with round joins softens the corners like the rounded icon set.
            drawPath(path, color, style = Stroke(width = w * 0.06f, join = StrokeJoin.Round))
        }
        // Play: triangle (0.28,0.16)-(0.84,0.5)-(0.28,0.84) split at x = 0.56. Pause: two bars.
        quad(floatArrayOf(0.28f, 0.16f, 0.56f, 0.33f, 0.56f, 0.67f, 0.28f, 0.84f), floatArrayOf(0.22f, 0.18f, 0.42f, 0.18f, 0.42f, 0.82f, 0.22f, 0.82f))
        quad(floatArrayOf(0.56f, 0.33f, 0.84f, 0.5f, 0.84f, 0.5f, 0.56f, 0.67f), floatArrayOf(0.58f, 0.18f, 0.78f, 0.18f, 0.78f, 0.82f, 0.58f, 0.82f))
    }
}

/** Play/pause button whose glyph morphs between the two states. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Size.playButton,
    container: Color = ArnavTheme.colors.content,
    content: Color = ArnavTheme.colors.background,
    buffering: Boolean = false,
) {
    val motion = ArnavTheme.motion
    val haptics = ArnavTheme.haptics
    val interaction = rememberInteraction()
    val ring by animateFloatAsState(if (buffering) 1f else 0f, motion.fast(), label = "buffer")
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .background(container)
            .clickable(interaction, indication = androidx.compose.material3.ripple(), role = Role.Button) { haptics.press(); onClick() }
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        if (ring > 0f) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(size - 6.dp).graphicsLayer { alpha = ring }, color = content.copy(alpha = 0.35f), strokeWidth = 2.dp,
            )
        }
        val morph by animateFloatAsState(
            if (playing) 1f else 0f,
            if (motion.reduced) snap() else spring(dampingRatio = 0.72f, stiffness = 650f),
            label = "pp",
        )
        PlayPauseGlyph(morph, content, Modifier.size(size * 0.46f))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ArnavTheme.type.title, color = ArnavTheme.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = ArnavTheme.type.bodySmall, color = ArnavTheme.colors.contentSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && onAction != null) {
            Text(action, style = ArnavTheme.type.label, color = ArnavTheme.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable(onClick = onAction).padding(horizontal = Space.s, vertical = Space.s))
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val bg by androidx.compose.animation.animateColorAsState(if (selected) c.content else c.surfaceRaised, motion.fast(), label = "pillbg")
    val fg by androidx.compose.animation.animateColorAsState(if (selected) c.background else c.contentMuted, motion.fast(), label = "pillfg")
    val interaction = rememberInteraction()
    val haptics = ArnavTheme.haptics
    Row(
        modifier
            .height(36.dp)
            .pressScale(interaction, 0.95f)
            .clip(CircleShape)
            .background(bg)
            .then(if (!selected) Modifier.border(1.dp, c.divider, CircleShape) else Modifier)
            .clickable(interaction, indication = null, role = Role.Tab) { haptics.select(); onClick() }
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = ArnavTheme.type.label, color = fg, maxLines = 1)
    }
}

/** YouTube attribution chip — shown wherever YouTube content or data appears. */
@Composable
fun SourceBadge(youtube: Boolean, modifier: Modifier = Modifier) {
    val c = ArnavTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(c.content.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (youtube) {
            Box(Modifier.size(width = 12.dp, height = 9.dp).clip(RoundedCornerShape(2.5.dp)).background(Color(0xFFFF0033)), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(5.dp)) {
                    val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }
                    drawPath(p, Color.White)
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(if (youtube) "YouTube" else "On device", style = ArnavTheme.type.overline.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = c.contentMuted)
    }
}

/** Intentional empty state: a quiet glyph, one line of copy, one action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = ArnavTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(Space.l))
        Text(title, style = ArnavTheme.type.title, color = c.content, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xs))
        Text(body, style = ArnavTheme.type.bodySmall, color = c.contentMuted, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(Space.l))
            PrimaryButton(action, onAction)
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null) {
    val c = ArnavTheme.colors
    val interaction = rememberInteraction()
    val haptics = ArnavTheme.haptics
    Row(
        modifier
            .height(52.dp)
            .pressScale(interaction, 0.97f)
            .clip(CircleShape)
            .background(if (enabled) c.content else c.content.copy(alpha = 0.3f))
            .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled && !loading, role = Role.Button) { haptics.press(); onClick() }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = c.background, strokeWidth = 2.dp)
        else {
            if (icon != null) { Icon(icon, null, tint = c.background, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = ArnavTheme.type.titleSmall, color = c.background)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = ArnavTheme.colors
    val interaction = rememberInteraction()
    Row(
        modifier
            .height(48.dp)
            .pressScale(interaction, 0.97f)
            .clip(CircleShape)
            .border(1.dp, c.outline, CircleShape)
            .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.content, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = ArnavTheme.type.label, color = if (enabled) c.content else c.contentSubtle)
    }
}

/** Banner for recoverable problems (offline, quota, AI fallback). Never a stack trace. */
@Composable
fun NoticeBanner(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, tone: Color = ArnavTheme.colors.warning) {
    val c = ArnavTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.gutter).clip(RoundedCornerShape(Radius.m)).background(tone.copy(alpha = 0.12f)).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(text, style = ArnavTheme.type.bodySmall, color = c.content, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = ArnavTheme.type.label, color = tone, modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable(onClick = onAction).padding(Space.s))
        }
    }
}

val ContentPadding = PaddingValues(horizontal = Space.gutter)
