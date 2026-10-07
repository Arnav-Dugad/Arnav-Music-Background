package com.arnav.music.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.settings.GlassLevel
import com.arnav.music.core.settings.ThemeMode
import com.arnav.music.domain.model.Moments
import com.arnav.music.domain.model.Mood
import com.arnav.music.feature.auth.AuthCard
import com.arnav.music.feature.auth.AuthViewModel
import com.arnav.music.feature.moments.MomentCanvas
import com.arnav.music.ui.AppViewModel
import com.arnav.music.ui.components.ArnavMark
import com.arnav.music.ui.components.Pill
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space
import org.koin.compose.viewmodel.koinViewModel

/**
 * Short, cinematic onboarding. Five steps, every one skippable; nothing is required.
 */
@Composable
fun OnboardingScreen(app: AppViewModel, authVm: AuthViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val settings by app.settings.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var moods by rememberSaveable { mutableStateOf(setOf<String>()) }
    val authUi by authVm.ui.collectAsStateWithLifecycle()
    var permissionAsked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionAsked = true; step++ }
    val last = 4

    fun finish() = app.updateSettings { it.copy(onboardingDone = true, selectedMoods = moods, guestMode = authVm.available.not() || it.guestMode) }
    LaunchedEffect(authUi.done) { if (authUi.done) finish() }

    Box(Modifier.fillMaxSize().background(c.background)) {
        val moment = Moments.all[listOf(5, 3, 1, 2, 0)[step.coerceIn(0, last)]]
        MomentCanvas(moment, Modifier.fillMaxSize().graphicsLayer { alpha = if (c.isDark) 0.9f else 0.55f }, animated = true, density = 0.6f)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Space.gutter)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(last + 1) { i ->
                        Box(Modifier.height(4.dp).width(if (i == step) 22.dp else 8.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i <= step) 0.95f else 0.3f)))
                    }
                }
                Text("Skip", style = ArnavTheme.type.label, color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.clip(CircleShape).clickable(onClickLabel = "Skip onboarding") { finish() }.padding(Space.s))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(step, transitionSpec = {
                    (slideInHorizontally(motion.offsetSpring()) { (it * 0.2f * motion.travel).toInt() } + fadeIn(motion.fast())) togetherWith
                        (slideOutHorizontally(motion.offsetSpring()) { (-it * 0.2f * motion.travel).toInt() } + fadeOut(motion.fast()))
                }, label = "onb") { s ->
                    Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (s) {
                            0 -> Welcome()
                            1 -> {
                                Title("Choose your look", "You can change this anytime in Settings.")
                                Spacer(Modifier.height(Space.xl))
                                Label("Theme")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    ThemeMode.entries.forEach { m -> Pill(m.name.lowercase().replaceFirstChar(Char::uppercase).replace("Oled", "OLED"), settings.themeMode == m, { app.setTheme(m) }) }
                                }
                                Spacer(Modifier.height(Space.l))
                                Label("Glass UI")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                    GlassLevel.entries.forEach { g -> Pill(when (g) { GlassLevel.OFF -> "Pure"; GlassLevel.SUBTLE -> "Subtle glass"; GlassLevel.FULL -> "Full glass" }, settings.glass == g, { app.setGlass(g) }) }
                                }
                            }
                            2 -> {
                                Title("What do you reach for?", "Pick a few moods. They seed your first home — nothing else.")
                                Spacer(Modifier.height(Space.xl))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    Mood.entries.filter { it != Mood.AGGRESSIVE }.forEach { m ->
                                        Pill(m.label, m.name in moods, { moods = if (m.name in moods) moods - m.name else moods + m.name })
                                    }
                                }
                            }
                            3 -> {
                                Title("Bring your own library", "Play music files on this phone with full background playback, lock-screen and Bluetooth controls. Files never leave your device.")
                                Spacer(Modifier.height(Space.xl))
                                PrimaryButton(if (app.hasLocalPermission()) "Access allowed" else "Allow access to music", { launcher.launch(app.localPermission) }, enabled = !app.hasLocalPermission(), icon = Icons.Rounded.PhoneAndroid)
                            }
                            else -> {
                                Title("Sync across devices?", "Optional. Your likes and playlists follow you. History stays on this phone.")
                                Spacer(Modifier.height(Space.l))
                                AuthCard(authVm, authUi)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (step < last) PrimaryButton(if (step == 0) "Get started" else "Continue", { step++ }, icon = Icons.AutoMirrored.Rounded.ArrowForward)
                else SecondaryButton("Continue without an account", { finish() })
            }
        }
    }
}

@Composable
private fun Welcome() {
    val reveal = remember { Animatable(0f) }
    val motion = ArnavTheme.motion
    LaunchedEffect(Unit) { reveal.animateTo(1f, if (motion.reduced) tween(0) else tween(1600)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ArnavMark(Modifier.size(120.dp), progress = reveal.value)
        Spacer(Modifier.height(Space.xl))
        Text("Arnav Music", style = ArnavTheme.type.display, color = Color.White, modifier = Modifier.graphicsLayer { alpha = ((reveal.value - 0.4f) / 0.6f).coerceIn(0f, 1f) })
        Text("Music, alive.", style = ArnavTheme.type.title, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.graphicsLayer { alpha = ((reveal.value - 0.6f) / 0.4f).coerceIn(0f, 1f) })
    }
}

@Composable
private fun Title(title: String, body: String) {
    Text(title, style = ArnavTheme.type.display, color = Color.White, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.s))
    Text(body, style = ArnavTheme.type.body, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = ArnavTheme.type.overline, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth().padding(bottom = Space.s))
}
