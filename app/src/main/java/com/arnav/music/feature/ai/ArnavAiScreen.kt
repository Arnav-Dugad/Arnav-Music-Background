package com.arnav.music.feature.ai

import com.arnav.music.ui.Routes
import com.arnav.music.ui.OnTabReselect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.ai.AiUnavailableReason
import com.arnav.music.core.repo.SessionResult
import com.arnav.music.domain.format.Formatters
import com.arnav.music.domain.intelligence.EnergyCurve
import com.arnav.music.domain.intelligence.SessionBuilder
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.ArnavMark
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

private val examples = listOf(
    "Energetic but not aggressive",
    "A late-night coding mix",
    "45-minute gym session",
    "Gradually increase in energy",
    "Cinematic night drive",
    "Rediscover music I haven't played recently",
    "Something like my favourites but calmer",
)

@Composable
fun ArnavAiScreen(initialQuery: String, vm: ArnavAiViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val studioNav = LocalNavigator.current
    val motion = ArnavTheme.motion
    val chrome = LocalChromePadding.current
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(initialQuery) { if (initialQuery.isNotBlank()) { text = initialQuery; vm.build(initialQuery) } }
    val mode = vm.mode()

    Box(Modifier.fillMaxSize()) {
        AiAura(Modifier.fillMaxWidth().height(360.dp))
        val list = rememberLazyListState()
        OnTabReselect(Routes.AI) { list.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
            item {
                Column(Modifier.statusBarsPadding().padding(horizontal = Space.gutter).padding(top = Space.l)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArnavMark(Modifier.size(34.dp))
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text("Arnav AI", style = ArnavTheme.type.display, color = c.content)
                        }
                    }
                    Spacer(Modifier.height(Space.xs))
                    Text("Describe a feeling, a moment or a plan. I'll build a real session from music you can play.", style = ArnavTheme.type.body, color = c.contentMuted)
                    Spacer(Modifier.height(Space.m))
                    ModeChip(mode)
                    Spacer(Modifier.height(Space.l))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).glass(GlassMaterial.Regular, RoundedCornerShape(Radius.l)).padding(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            text, { text = it.take(300) }, textStyle = ArnavTheme.type.body.copy(color = c.content), cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { vm.build(text); keyboard?.hide() }),
                            modifier = Modifier.weight(1f).padding(vertical = Space.m),
                            decorationBox = { inner -> if (text.isEmpty()) Text("“45 minutes of focus, mostly familiar, a few surprises”", style = ArnavTheme.type.body, color = c.contentSubtle); inner() },
                        )
                        ArnavIconButton(Icons.AutoMirrored.Rounded.Send, "Build session", { vm.build(text); keyboard?.hide() }, tint = if (text.isBlank()) c.contentSubtle else c.accent, enabled = text.isNotBlank())
                    }
                    Spacer(Modifier.height(Space.m))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Pill("Session controls", settings.studio.sessionControls, { studioNav.go(Routes.settings("studio")) })
                        if (ui is AiUi.Building) Pill("Cancel build", false, vm::cancel)
                        if (canUndo) Pill("Undo preview", false, vm::undo)
                    }
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(Space.s))
                        Text("Recent requests", style = ArnavTheme.type.caption, color = c.contentMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            history.take(4).forEach { p -> Pill(p, false, { text = p; vm.build(p) }) }
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        examples.forEach { e -> Pill(e, false, { text = e; vm.build(e) }) }
                    }
                }
            }
            item {
                AnimatedContent(ui, contentKey = { it::class }, transitionSpec = {
                    (fadeIn(motion.fast()) + slideInVertically(motion.responsive()) { (it / 12 * motion.travel).toInt() }) togetherWith fadeOut(motion.fast())
                }, label = "ai") { s ->
                    when (s) {
                        AiUi.Idle -> Box {}
                        is AiUi.Building -> BuildingCard(s)
                        is AiUi.Empty -> EmptyState(
                            Icons.Rounded.SearchOff, "Couldn't find playable songs for that",
                            if (s.result.aiUnavailable == null) "Try different words, or connect YouTube search in Settings."
                            else "Search may be offline or resting. Like a few songs or add music to your device and I'll use those.",
                        )
                        is AiUi.Ready -> SessionHeader(s.prompt, s.result, onRegenerate = { vm.build(s.prompt) })
                    }
                }
            }
            (ui as? AiUi.Ready)?.let { r ->
                val tracks = r.result.session.tracks
                itemsIndexed(tracks, key = { _, t -> "s_" + t.id.value }) { i, t ->
                    val app = LocalAppViewModel.current
                    val nav = LocalNavigator.current
                    val reason = r.result.session.reasons[t.id]
                    TrackRow(
                        t, { app.play(tracks, i) }, index = i + 1,
                        subtitle = t.artist + (reason?.let { " · " + vm.explain(it, t) } ?: ""),
                        onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) }, onMore = { nav.openSheet(SheetRequest.TrackActions(t)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: AiUnavailableReason?) {
    val c = ArnavTheme.colors
    val (label, detail) = when (mode) {
        null -> "Gemini · free tier" to "Interprets your words; songs are chosen on-device."
        AiUnavailableReason.DISABLED_BY_USER -> "On-device mode" to "Cloud AI is off in Settings. The local engine still understands moods, time and energy."
        AiUnavailableReason.NOT_CONFIGURED -> "On-device mode" to "Cloud AI isn't set up in this build. The local engine handles requests."
        AiUnavailableReason.DAILY_LIMIT, AiUnavailableReason.QUOTA -> "On-device mode" to "Today's free AI allowance is used. The local engine takes over until tomorrow."
        AiUnavailableReason.APP_CHECK -> "On-device mode" to "App Check couldn't verify this install (APKs from GitHub aren't vouched for by Google Play), so cloud AI is blocked. Fix it in Settings → Arnav AI → Cloud AI on this phone. Until then the local engine answers."
        else -> "On-device mode" to "The local engine handles requests right now. Settings → Arnav AI shows the last cloud error."
    }
    Row(Modifier.clip(RoundedCornerShape(Radius.m)).background(c.content.copy(alpha = 0.06f)).padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (mode == null) Icons.Rounded.CheckCircle else Icons.Rounded.PhoneAndroid, null, tint = if (mode == null) c.success else c.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.s))
        Column {
            Text(label, style = ArnavTheme.type.label, color = c.content)
            Text(detail, style = ArnavTheme.type.caption, color = c.contentMuted)
        }
    }
}

@Composable
private fun BuildingCard(s: AiUi.Building) {
    val c = ArnavTheme.colors
    val rotation = if (ArnavTheme.motion.reduced) 0f else rememberInfiniteTransition(label = "think").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p").value
    Column(Modifier.padding(Space.gutter).fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.xl)).padding(Space.xl)) {
        Text("“${s.prompt}”", style = ArnavTheme.type.title, color = c.content)
        Spacer(Modifier.height(Space.l))
        BuildStep.entries.forEach { step ->
            val done = step.ordinal < s.step.ordinal
            val active = step == s.step
            Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    else Canvas(Modifier.size(14.dp)) {
                        drawCircle(c.content.copy(alpha = 0.15f), style = Stroke(2.dp.toPx()))
                        if (active) drawArc(c.accent, 360f * rotation, 100f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                Spacer(Modifier.width(Space.m))
                Text(step.label, style = ArnavTheme.type.body, color = if (done || active) c.content else c.contentSubtle)
            }
        }
    }
}

@Composable
private fun SessionHeader(prompt: String, result: SessionResult, onRegenerate: () -> Unit) {
    val c = ArnavTheme.colors
    val app = LocalAppViewModel.current
    val s = result.session
    val k = s.constraints
    Column(Modifier.padding(Space.gutter).fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.xl)).padding(Space.xl)) {
        Text(if (result.usedAi) "BUILT WITH GEMINI + ON-DEVICE RANKING" else "BUILT ON-DEVICE", style = ArnavTheme.type.overline, color = c.accent)
        Spacer(Modifier.height(Space.xs))
        Text(k.title, style = ArnavTheme.type.headline, color = c.content)
        Text("${s.tracks.size} songs · ${Formatters.longDuration(s.totalMs)}" + if (s.discoveredCount > 0) " · ${s.discoveredCount} new to you" else "", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
        result.explanation?.let { Spacer(Modifier.height(Space.s)); Text(it, style = ArnavTheme.type.bodySmall, color = c.content) }
        Spacer(Modifier.height(Space.l))
        EnergyChart(k, s.tracks.map { it.energy }, Modifier.fillMaxWidth().height(96.dp))
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Chip("${k.durationMinutes} min")
            Chip("Energy ${(k.energyTarget * 100).toInt()}%")
            Chip(when (k.energyCurve) { EnergyCurve.FLAT -> "Steady"; EnergyCurve.RISING -> "Building"; EnergyCurve.FALLING -> "Winding down"; EnergyCurve.WAVE -> "Waves"; EnergyCurve.PEAK -> "Peak in the middle" })
            Chip("${(k.familiarity * 100).toInt()}% familiar")
            k.moods.take(3).forEach { Chip(it.replaceFirstChar { ch -> ch.uppercase() }) }
            k.avoidMoods.take(2).forEach { Chip("No ${it.lowercase()}") }
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PrimaryButton("Play", { app.play(s.tracks, 0) }, icon = Icons.Rounded.PlayArrow)
            SecondaryButton("Save", { app.createPlaylistWith(k.title, s.tracks) }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
            ArnavIconButton(Icons.Rounded.Refresh, "Regenerate", onRegenerate)
        }
        if (result.aiUnavailable != null && result.aiUnavailable != AiUnavailableReason.DISABLED_BY_USER) {
            Spacer(Modifier.height(Space.m))
            Text("Answered on-device — " + fallbackReason(result.aiUnavailable), style = ArnavTheme.type.caption, color = c.contentSubtle)
        }
        if (result.searchedRemotely > 0) {
            Text("Used ${result.searchedRemotely} YouTube ${if (result.searchedRemotely == 1) "search" else "searches"} to find playable songs.", style = ArnavTheme.type.caption, color = c.contentSubtle)
        }
    }
}

private fun fallbackReason(r: AiUnavailableReason): String = when (r) {
    AiUnavailableReason.APP_CHECK -> "Gemini rejected this install's App Check token. Set AI Logic to Unenforced in Firebase App Check to allow GitHub installs."
    AiUnavailableReason.NOT_CONFIGURED -> "cloud AI isn't configured in this build."
    AiUnavailableReason.DAILY_LIMIT -> "you've reached today's AI limit (Settings → Arnav AI)."
    AiUnavailableReason.QUOTA -> "the free Gemini quota is used up for now."
    AiUnavailableReason.THROTTLED -> "requests are spaced a few seconds apart to protect the free tier."
    AiUnavailableReason.OFFLINE -> "Gemini couldn't be reached (offline)."
    AiUnavailableReason.TIMEOUT -> "Gemini took too long to answer."
    AiUnavailableReason.MALFORMED -> "Gemini's answer couldn't be understood, so the local engine stepped in."
    AiUnavailableReason.ERROR -> "Gemini returned an error (see Settings → Usage)."
    AiUnavailableReason.DISABLED_BY_USER -> "cloud AI is turned off."
}

@Composable
private fun Chip(text: String) {
    Text(text, style = ArnavTheme.type.caption, color = ArnavTheme.colors.content,
        modifier = Modifier.clip(CircleShape).background(ArnavTheme.colors.content.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** Target energy curve (line) with each chosen track's estimated energy (dots, when known). */
@Composable
private fun EnergyChart(c: com.arnav.music.domain.intelligence.SessionConstraints, energies: List<Float?>, modifier: Modifier) {
    val colors = ArnavTheme.colors
    Canvas(modifier.semantics { contentDescription = "Energy curve: ${c.energyCurve.name.lowercase()}, target ${(c.energyTarget * 100).toInt()} percent" }) {
        val w = size.width; val h = size.height
        val path = Path()
        for (i in 0..40) {
            val p = i / 40f
            val y = h - SessionBuilder.energyAt(c, p) * h
            if (i == 0) path.moveTo(0f, y) else path.lineTo(w * p, y)
        }
        val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(path, colors.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        if (energies.isNotEmpty()) energies.forEachIndexed { i, e ->
            if (e == null) return@forEachIndexed
            val x = if (energies.size == 1) w / 2 else w * i / (energies.size - 1)
            drawCircle(colors.content.copy(alpha = 0.7f), 3.dp.toPx(), Offset(x, h - e * h))
        }
    }
}

@Composable
private fun AiAura(modifier: Modifier) {
    val c = ArnavTheme.colors
    val reduced = ArnavTheme.motion.reduced
    val t = if (!reduced) rememberInfiniteTransition(label = "aura").animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "a").value else 0.5f
    Canvas(modifier) {
        val cx = size.width * (0.3f + 0.4f * t)
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.isOled) 0.12f else 0.22f), Color.Transparent), Offset(cx, size.height * 0.2f), size.width * 0.8f), size.width * 0.8f, Offset(cx, size.height * 0.2f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF52D6C3).copy(alpha = 0.12f), Color.Transparent), Offset(size.width - cx, size.height * 0.5f), size.width * 0.6f), size.width * 0.6f, Offset(size.width - cx, size.height * 0.5f))
    }
}
