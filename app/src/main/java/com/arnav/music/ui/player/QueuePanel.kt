package com.arnav.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.arnav.music.core.playback.PlayerState
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.queue.QueueItem
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.components.bounce
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lets the panel's host pull the queue sheet down from inside the list: when the list is at its top,
 * the leftover of a downward drag goes to this connection instead of the list's elastic overscroll.
 */
val LocalQueuePull = compositionLocalOf<NestedScrollConnection?> { null }

/**
 * Up Next. Long-press any row (or grab its handle) to reorder: the held row lifts (scale + shadow),
 * neighbours spring out of its way, and the row is pulled magnetically towards the nearest slot,
 * ticking as it crosses each one. The move is committed once, on release. "Journey" shows upcoming
 * songs as a flowing timeline.
 */
@Composable
fun QueuePanel(
    state: PlayerState,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onSkipTo: (Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onShuffle: (() -> Unit)? = null,
    onHarmonicMix: (() -> Unit)? = null,
) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    val motion = ArnavTheme.motion
    val scope = rememberCoroutineScope()
    var journey by rememberSaveable { mutableStateOf(false) }
    val q = state.queue
    val latestQueue by rememberUpdatedState(q)
    val move by rememberUpdatedState(onMove)
    val listState = rememberLazyListState()
    val pull = LocalQueuePull.current

    // ---- Reorder state (indices are positions within Up Next) ----
    var dragUid by remember { mutableStateOf<Long?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTarget by remember { mutableIntStateOf(-1) }
    var itemH by remember { mutableFloatStateOf(1f) }
    // Waiting for the committed order to come back from the player.
    var pending by remember { mutableStateOf(false) }
    val dragVisual = remember { Animatable(0f) }
    val drop = remember { DropMemo() }
    // The committed order has arrived: this very frame shows the new layout with every offset at rest.
    val arrived = pending && drop.items != null && q.items !== drop.items
    val active = dragUid != null && !arrived

    fun clear() {
        dragUid = null; dragFrom = -1; dragTarget = -1; pending = false
        drop.items = null
        scope.launch { dragVisual.snapTo(0f) }
    }
    if (arrived) {
        SideEffect {
            // Keep the viewport where it was (by index, not by the moved rows' keys).
            drop.anchor?.let { (index, offset) -> listState.requestScrollToItem(index, offset) }
            drop.anchor = null
        }
        LaunchedEffect(Unit) { clear() }
    }

    fun startDrag(uid: Long) {
        if (dragUid != null) return
        val up = latestQueue.upNext
        val from = up.indexOfFirst { it.uid == uid }
        if (from < 0) return
        itemH = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == uid }?.size?.toFloat()?.coerceAtLeast(1f) ?: return
        dragUid = uid; dragFrom = from; dragTarget = from
        drop.raw = 0f
        scope.launch { dragVisual.snapTo(0f) }
        haptics.longPress()
    }

    fun dragBy(dy: Float) {
        if (dragUid == null || pending) return
        drop.raw += dy
        val raw = drop.raw
        val last = latestQueue.upNext.lastIndex
        val target = (dragFrom + (raw / itemH).roundToInt()).coerceIn(0, max(last, 0))
        if (target != dragTarget) { dragTarget = target; haptics.snap() }
        // Magnetism: close to a slot the row is drawn into it; halfway between, it follows the finger.
        val slot = (target - dragFrom) * itemH
        val d = ((raw - slot) / itemH).coerceIn(-0.5f, 0.5f)
        val pullIn = 0.6f * (1f - abs(d) * 2f).pow(1.5f)
        val goal = raw + (slot - raw) * pullIn
        scope.launch {
            if (motion.reduced) dragVisual.snapTo(goal)
            else dragVisual.animateTo(goal, spring(dampingRatio = 0.8f, stiffness = 1400f))
        }
    }

    fun endDrag(uid: Long) {
        if (dragUid != uid || pending) return
        val from = dragFrom
        val to = dragTarget
        pending = true
        scope.launch {
            // Settle into the slot with a little spring, then commit the move once.
            dragVisual.animateTo((to - from) * itemH, if (motion.reduced) snap() else spring(dampingRatio = 0.68f, stiffness = 520f))
            haptics.queued()
            val qs = latestQueue
            val cur = qs.items.indexOfFirst { it.uid == uid }
            if (to == from || cur < 0) { clear(); return@launch }
            val absTo = (qs.currentIndex + 1 + to).coerceIn(qs.currentIndex + 1, qs.items.lastIndex)
            drop.items = qs.items
            drop.anchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            move(cur, absTo)
            // Safety net: if the new order never shows up (the queue changed meanwhile), let go anyway.
            delay(800)
            if (dragUid == uid && pending) clear()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = Space.s), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.content.copy(alpha = 0.25f)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = ArnavTheme.type.headline, color = c.content)
                val remaining = q.upNext.sumOf { it.track.durationMs ?: 0L }
                Text("${q.upNext.size} tracks · ${Formatters.longDuration(remaining)}", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
            }
            ArnavIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save queue as playlist", onSave)
        }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.s)) {
            Pill("List", !journey, { journey = false }, leading = Icons.AutoMirrored.Rounded.ViewList)
            Pill("Journey", journey, { journey = true }, leading = Icons.Rounded.Timeline)
            // Reordering happens in place: every row glides to its new slot.
            if (onShuffle != null) Pill("Shuffle", q.shuffled, { haptics.select(); onShuffle() }, leading = Icons.Rounded.Shuffle)
            if (onHarmonicMix != null) Pill("Harmonic mix", false, { haptics.select(); onHarmonicMix() }, leading = Icons.Rounded.GraphicEq)
        }
        Spacer(Modifier.height(Space.s))
        if (q.upNext.isEmpty()) {
            Text("Nothing queued. Swipe right on any song to add it here.", style = ArnavTheme.type.bodySmall, color = c.contentMuted, modifier = Modifier.padding(Space.gutter))
            return@Column
        }
        val bounce = com.arnav.music.ui.components.rememberBounce()
        LazyColumn(
            state = listState, contentPadding = PaddingValues(bottom = Space.xxxl),
            // The pull connection sits inside the bounce so it sees the list's leftovers first.
            modifier = Modifier.weight(1f).bounce(bounce).then(if (pull != null) Modifier.nestedScroll(pull) else Modifier),
        ) {
            if (journey) {
                val start = System.currentTimeMillis() + ((q.current?.track?.durationMs ?: 0L) / 2)
                val etas = q.upNext.runningFold(start) { acc, it -> acc + (it.track.durationMs ?: 210_000L) }
                itemsIndexed(q.upNext, key = { _, it -> it.uid }) { i, item ->
                    JourneyNode(item, etas[i], first = i == 0, last = i == q.upNext.lastIndex, onClick = { onSkipTo(q.currentIndex + 1 + i) })
                }
            } else {
                itemsIndexed(q.upNext, key = { _, it -> it.uid }) { i, item ->
                    val absolute = q.currentIndex + 1 + i
                    val dragging = active && dragUid == item.uid
                    val lift by animateFloatAsState(if (dragging) 1f else 0f, motion.responsive(), label = "lift")
                    // Neighbours between the lifted row's home and its target slide one slot out of its way.
                    val shiftTarget = when {
                        !active || dragging -> 0f
                        dragFrom < dragTarget && i in (dragFrom + 1)..dragTarget -> -itemH
                        dragTarget < dragFrom && i in dragTarget until dragFrom -> itemH
                        else -> 0f
                    }
                    val shift by animateFloatAsState(
                        shiftTarget,
                        if (active && !motion.reduced) spring(dampingRatio = 0.78f, stiffness = 420f) else snap(),
                        label = "shift",
                    )
                    Row(
                        Modifier
                            .animateItem(
                                placementSpec = if (dragUid != null || arrived) null
                                else spring(dampingRatio = 0.8f, stiffness = 280f, visibilityThreshold = IntOffset(1, 1)),
                            )
                            .zIndex(if (dragging || lift > 0.01f) 1f else 0f)
                            .graphicsLayer {
                                translationY = when {
                                    dragging -> dragVisual.value
                                    active -> shift
                                    else -> 0f
                                }
                                val s = 1f + 0.04f * lift
                                scaleX = s; scaleY = s
                                shadowElevation = 16.dp.toPx() * lift
                                shape = RoundedCornerShape(Radius.m)
                            }
                            .background(c.surfaceRaised.copy(alpha = lift), RoundedCornerShape(Radius.m))
                            .pointerInput(item.uid) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { startDrag(item.uid) },
                                    onDragEnd = { endDrag(item.uid) },
                                    onDragCancel = { endDrag(item.uid) },
                                ) { change, amount ->
                                    if (dragUid == item.uid) { change.consume(); dragBy(amount.y) }
                                }
                            }
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Move up") { if (absolute > q.currentIndex + 1) onMove(absolute, absolute - 1); true },
                                    CustomAccessibilityAction("Move down") { if (absolute < q.items.lastIndex) onMove(absolute, absolute + 1); true },
                                    CustomAccessibilityAction("Remove from queue") { onRemove(absolute); true },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(item.track, onClick = { onSkipTo(absolute) }, modifier = Modifier.weight(1f), compact = true)
                        ArnavIconButton(Icons.Rounded.Close, "Remove ${item.track.title} from queue", { onRemove(absolute) }, tint = c.contentSubtle, size = 18.dp)
                        Icon(
                            Icons.Rounded.DragHandle, "Reorder ${item.track.title}", tint = c.contentSubtle,
                            modifier = Modifier
                                .padding(end = Space.s)
                                .size(Space.touch)
                                .padding(12.dp)
                                .pointerInput(item.uid) {
                                    // The handle lifts the row straight away (no long press needed).
                                    detectVerticalDragGestures(
                                        onDragStart = { startDrag(item.uid) },
                                        onDragEnd = { endDrag(item.uid) },
                                        onDragCancel = { endDrag(item.uid) },
                                    ) { change, dy ->
                                        if (dragUid == item.uid) { change.consume(); dragBy(dy) }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/** Plain (non-observable) bookkeeping for a drop in flight. */
private class DropMemo {
    var raw = 0f
    var items: List<QueueItem>? = null
    var anchor: Pair<Int, Int>? = null
}

@Composable
private fun JourneyNode(item: QueueItem, eta: Long, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    val energy = item.track.energy
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable(onClick = onClick).padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp).height(76.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2
                if (!first) drawLine(c.accent.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height / 2), strokeWidth = 2.dp.toPx())
                if (!last) drawLine(c.accent.copy(alpha = 0.25f), Offset(x, size.height / 2), Offset(x, size.height), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                // Node size hints at energy when known.
                val r = (4f + 4f * (energy ?: 0.4f)).dp.toPx()
                drawCircle(c.accent, r, Offset(x, size.height / 2))
                drawCircle(c.background, r * 0.45f, Offset(x, size.height / 2))
            }
        }
        Spacer(Modifier.width(Space.m))
        Artwork(item.track.artworkUrl, item.track.id.value, Modifier.size(48.dp), RoundedCornerShape(Radius.s), decodeSize = 140)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(item.track.title, style = ArnavTheme.type.titleSmall, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.track.artist, style = ArnavTheme.type.bodySmall, color = c.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(eta)), style = ArnavTheme.type.numeric, color = c.contentSubtle)
    }
}
