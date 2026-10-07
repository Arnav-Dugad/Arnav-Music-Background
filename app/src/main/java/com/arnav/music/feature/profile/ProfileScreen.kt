package com.arnav.music.feature.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.feature.insights.InsightsViewModel
import com.arnav.music.feature.settings.NavRow
import com.arnav.music.feature.settings.SettingsGroup
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.LocalChromePadding
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.Routes
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ProfileScreen(insights: InsightsViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val nav = LocalNavigator.current
    val app = LocalAppViewModel.current
    val user by app.user.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val dna by insights.dna.collectAsStateWithLifecycle()
    val names by insights.artistNames.collectAsStateWithLifecycle()
    val chrome = LocalChromePadding.current
    LaunchedEffect(Unit) { insights.loadDna() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + Space.xl)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = Space.xs)) {
                ArnavIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", nav::back)
                Spacer(Modifier.weight(1f))
                ArnavIconButton(Icons.Rounded.Settings, "Settings", { nav.go(Routes.settings()) })
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(Space.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                val u = user
                if (u?.photoUrl != null) Artwork(u.photoUrl, "me", Modifier.size(96.dp), CircleShape, decodeSize = 240)
                else Box(Modifier.size(96.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = c.accent, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(Space.m))
                Text(u?.displayName ?: if (u == null) "Listener" else u.email ?: "Listener", style = ArnavTheme.type.headline, color = c.content)
                Text(
                    u?.createdAt?.let { "Joined Arnav Music " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Using Arnav Music on this device",
                    style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                )
                if (u == null) {
                    Spacer(Modifier.height(Space.l))
                    PrimaryButton("Sign in to sync", { nav.go(Routes.AUTH) })
                }
            }
        }
        dna?.let { d ->
            item {
                SettingsGroup("Taste summary") {
                    Column(Modifier.padding(Space.l)) {
                        Text("${d.totalMinutes} minutes · ${(d.discoveryRatio * 100).toInt()}% discovery", style = ArnavTheme.type.title, color = c.content)
                        Spacer(Modifier.height(Space.s))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            d.topArtists.take(6).forEach { (k, _) ->
                                Text(names[k] ?: k, style = ArnavTheme.type.caption, color = c.content, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                    NavRow(Icons.Rounded.Insights, "Open Taste DNA", "Recaps, constellation, timeline") { nav.go(Routes.INSIGHTS) }
                }
            }
        }
        item {
            SettingsGroup("Preferences") {
                com.arnav.music.feature.settings.InfoRow("Theme", settings.themeMode.name.lowercase().replaceFirstChar(Char::uppercase))
                com.arnav.music.feature.settings.InfoRow("Glass", settings.glass.name.lowercase().replaceFirstChar(Char::uppercase))
                com.arnav.music.feature.settings.InfoRow("Arnav AI", if (settings.aiEnabled) "On" else "On-device only")
            }
        }
    }
}
