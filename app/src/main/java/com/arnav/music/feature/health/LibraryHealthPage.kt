package com.arnav.music.feature.health

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.feature.settings.ActionRowS
import com.arnav.music.feature.settings.DataFact
import com.arnav.music.feature.settings.SettingsGroup
import com.arnav.music.ui.LocalNavigator
import com.arnav.music.ui.player.SheetRequest
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibraryHealthPage(vm: LibraryHealthViewModel = koinViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    Column {
        SettingsGroup("Library health", footer = "Checks local files and saved metadata. YouTube availability is confirmed by its player when you play a song.") {
            ActionRowS("Scan library", "Read-only scan; your songs and playlists stay intact", enabled = !report.scanning) { vm.scan() }
            if (report.scanning) CircularProgressIndicator(Modifier.padding(Space.l))
            else DataFact("${report.total} songs checked", if (report.issues.isEmpty() && report.error == null) "No issues found in the checks performed." else "${report.issues.size} categories to review")
            report.error?.let { DataFact("Scan failed", it) }
        }
        report.issues.forEach { issue ->
            SettingsGroup(issue.title) {
                DataFact("What to check", issue.detail)
                issue.route?.let { route -> ActionRowS("Open review") { nav.go(route) } }
                issue.tracks.take(12).forEach { track ->
                    ActionRowS(track.title, track.artist + " · Review song details") { nav.openSheet(SheetRequest.TrackActions(track)) }
                }
                if (issue.tracks.size > 12) DataFact("${issue.tracks.size - 12} more", "Open your library to review the remaining songs.")
            }
        }
    }
}
