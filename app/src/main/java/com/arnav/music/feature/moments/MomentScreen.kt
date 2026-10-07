package com.arnav.music.feature.moments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.repo.IntelligenceRepository
import com.arnav.music.domain.intelligence.BuiltSession
import com.arnav.music.domain.model.Moment
import com.arnav.music.domain.model.MomentMotion
import com.arnav.music.domain.model.Moments
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.EmptyState
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.TrackRow
import com.arnav.music.ui.components.TrackRowSkeleton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Manrope
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

class MomentViewModel(private val intelligence: IntelligenceRepository, private val analytics: Analytics) : ViewModel() {
    private val _session = MutableStateFlow<BuiltSession?>(null)
    val session: StateFlow<BuiltSession?> = _session.asStateFlow()
    val loading = MutableStateFlow(false)
    fun load(m: Moment) {
        if (_session.value != null || loading.value) return
        analytics.log(Analytics.Event.MOMENT_OPENED, mapOf("moment" to m.id))
        loading.value = true
        viewModelScope.launch {
            _session.value = runCatching { intelligence.momentQueue(m) }.getOrNull()
            loading.value = false
        }
    }
}

/** Each Moment has its own typographic voice — same family, different posture. */
private fun momentType(m: Moment): TextStyle = when (m.motion) {
    MomentMotion.DRIFT -> TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Light, fontSize = 52.sp, letterSpacing = 0.12.em, lineHeight = 56.sp)
    MomentMotion.RAIN -> TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 56.sp, letterSpacing = (-0.01).em, lineHeight = 60.sp)
    MomentMotion.STILL -> TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 48.sp, letterSpacing = 0.02.em, lineHeight = 54.sp)
    MomentMotion.SHIMMER -> TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 54.sp, letterSpacing = (-0.02).em, lineHeight = 58.sp)
    MomentMotion.SURGE -> TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, letterSpacing = (-0.04).em, lineHeight = 64.sp)
    MomentMotion.PULSE -> TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 58.sp, letterSpacing = (-0.03).em, lineHeight = 60.sp)
}

@Composable
fun MomentScreen(id: String, vm: MomentViewModel = koinViewModel()) {
    val moment = Moments.byId(id) ?: run {
        EmptyState(androidx.compose.material.icons.Icons.Rounded.PlayArrow, "Moment not found", "This moment link isn't valid anymore.")
        return
    }
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val session by vm.session.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    LaunchedEffect(id) { vm.load(moment) }

    Box(Modifier.fillMaxSize()) {
        MomentCanvas(moment, Modifier.fillMaxSize(), animated = true, density = 1f)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
            item {
                Row(Modifier.statusBarsPadding().padding(Space.xs)) { ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back, tint = Color.White) }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(top = 140.dp)) {
                    Text("MOMENT", style = ArnavTheme.type.overline, color = Color.White.copy(alpha = 0.7f))
                    Text(moment.title, style = momentType(moment), color = Color.White)
                    Spacer(Modifier.height(Space.s))
                    Text(moment.subtitle, style = ArnavTheme.type.body, color = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(Space.xl))
                    PrimaryButton(
                        if (loading) "Tuning…" else "Start ${moment.title}",
                        { session?.tracks?.let { app.play(it, 0) } },
                        enabled = !session?.tracks.isNullOrEmpty(), loading = loading, icon = Icons.Rounded.PlayArrow,
                    )
                    Spacer(Modifier.height(Space.xxl))
                }
            }
            item {
                AnimatedVisibility(session != null || loading, enter = fadeIn() + slideInVertically { it / 8 }) {
                    Column(Modifier.padding(horizontal = Space.s).fillMaxWidth().glass(GlassMaterial.Thick, RoundedCornerShape(Radius.xl), tint = Color(moment.palette.last())).padding(vertical = Space.m)) {
                        Text("In this moment", style = ArnavTheme.type.title, color = ArnavTheme.colors.content, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
                        when {
                            loading -> repeat(5) { TrackRowSkeleton() }
                            session?.tracks.isNullOrEmpty() -> Text(
                                "Couldn't gather songs right now. Like a few tracks or add on-device music and this moment will fill itself.",
                                style = ArnavTheme.type.bodySmall, color = ArnavTheme.colors.contentMuted, modifier = Modifier.padding(Space.gutter),
                            )
                            else -> session?.tracks?.forEachIndexed { i, t ->
                                TrackRow(t, { app.play(session!!.tracks, i) }, onQueue = { app.addToQueue(t) }, onLike = { app.toggleLike(t) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused") private val arr = Arrangement.Top
