package com.arnav.music.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.intelligence.EnergyCurve
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space

private val homeSections = listOf(
    "continue" to "Continue listening", "moments" to "Moments", "made" to "Made for you", "start" to "Start here",
    "foryou" to "For you right now", "daily" to "Daily mixes", "fresh" to "Fresh finds", "rediscover" to "Rediscover",
    "tm" to "Time machine", "local" to "From your device", "trending" to "Trending",
)
@Composable
fun StudioPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val studio = s.studio
    val rules by vm.rules.collectAsStateWithLifecycle()
    val preview by vm.collectionPreview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("My short songs") }
    var query by rememberSaveable { mutableStateOf("source:local duration:0..240") }
    val order = (studio.homeOrder + homeSections.map { it.first }).distinct().filter { key -> homeSections.any { it.first == key } }
    Column {
        SettingsGroup("Your Home", footer = "Hide sections and move the music you use most to the top. Empty sections stay hidden automatically. Your layout is included in account backups.") {
            order.forEachIndexed { index, key ->
                ToggleRow(homeSections.first { it.first == key }.second, key !in studio.hiddenHome, { show ->
                    vm.update { current -> current.copy(studio = current.studio.copy(hiddenHome = if (show) current.studio.hiddenHome - key else current.studio.hiddenHome + key)) }
                })
                Row(Modifier.padding(horizontal = Space.l)) {
                    TextButton(onClick = { vm.update { current ->
                        val moved = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                        current.copy(studio = current.studio.copy(homeOrder = moved))
                    } }, enabled = index > 0) { Text("Move up") }
                    TextButton(onClick = { vm.update { current ->
                        val moved = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                        current.copy(studio = current.studio.copy(homeOrder = moved))
                    } }, enabled = index < order.lastIndex) { Text("Move down") }
                }
                Divider()
            }
            ActionRowS("Reset Home layout") { vm.update { it.copy(studio = it.studio.copy(homeOrder = emptyList(), hiddenHome = emptySet())) } }
        }
        SettingsGroup("AI session controls", footer = "When enabled these controls override duration, energy and familiarity inferred from prompts. Every session remains a preview until you press Play or Save.") {
            ToggleRow("Use my session controls", studio.sessionControls, { v -> vm.update { it.copy(studio = it.studio.copy(sessionControls = v)) } })
            SliderRow("Duration", studio.sessionMinutes.toFloat(), 5f..120f, 22, studio.sessionMinutes.toString() + " min") { v -> vm.update { it.copy(studio = it.studio.copy(sessionMinutes = v.toInt())) } }
            SliderRow("Energy", studio.sessionEnergy, 0f..1f, 9, (studio.sessionEnergy * 100).toInt().toString() + "%") { v -> vm.update { it.copy(studio = it.studio.copy(sessionEnergy = v)) } }
            SliderRow("Familiar songs", studio.sessionFamiliarity, 0f..1f, 9, (studio.sessionFamiliarity * 100).toInt().toString() + "%") { v -> vm.update { it.copy(studio = it.studio.copy(sessionFamiliarity = v)) } }
            SliderRow("Artist variety", studio.sessionDiversity, 0f..1f, 9, (studio.sessionDiversity * 100).toInt().toString() + "%") { v -> vm.update { it.copy(studio = it.studio.copy(sessionDiversity = v)) } }
            ChoiceRow("Energy curve", EnergyCurve.entries, studio.sessionCurve, { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                { v -> vm.update { it.copy(studio = it.studio.copy(sessionCurve = v)) } })
        }
        SettingsGroup("Advanced library search", footer = "Filters search your saved catalog and on-device songs without spending YouTube API quota.") {
            DataFact("Search operators", "artist:\"Daft Punk\" source:local year:2010..2024 duration:180..360\nDurations are seconds. source:youtube finds saved YouTube songs. Small typos in titles and artist names are tolerated.")
        }
        SettingsGroup("Rule playlists", footer = "Preview up to 500 matches before saving. Refresh a saved rule here when your library changes; the same playlist is updated in Library.") {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth().padding(Space.m), singleLine = true)
            OutlinedTextField(query, { query = it.take(300) }, label = { Text("Library search rule") }, modifier = Modifier.fillMaxWidth().padding(Space.m))
            ActionRowS("Preview rule", enabled = query.isNotBlank() && busy == null) { vm.previewCollection(name, query) }
            rules.forEach { rule -> Divider(); ActionRowS("Refresh " + rule.name, rule.query, enabled = busy == null) { vm.previewCollection(rule.name, rule.query, rule.id) } }
        }
    }
    preview?.let { collection ->
        AlertDialog(onDismissRequest = vm::dismissCollection, containerColor = ArnavTheme.colors.surfaceRaised,
            title = { Text(collection.name + " · " + collection.tracks.size + " matches") },
            text = { Column {
                Text(collection.query); Spacer(Modifier.height(Space.s))
                collection.tracks.take(5).forEach { Text(it.title + " · " + it.artist, style = ArnavTheme.type.bodySmall) }
                if (collection.tracks.size == 500) Text("First 500 matches shown. Narrow the rule for a smaller collection.")
            } },
            confirmButton = { TextButton(onClick = { vm.saveCollection() }, enabled = busy == null && collection.tracks.isNotEmpty()) { Text(if (collection.targetId == null) "Save playlist" else "Apply refresh") } },
            dismissButton = { TextButton(onClick = vm::dismissCollection) { Text("Cancel") } },
        )
    }
}
