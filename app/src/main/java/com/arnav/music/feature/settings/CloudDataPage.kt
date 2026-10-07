package com.arnav.music.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.domain.format.Formatters
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Space

@Composable
internal fun DataFact(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m)) {
        Text(title, style = ArnavTheme.type.titleSmall, color = ArnavTheme.colors.content)
        Text(body, style = ArnavTheme.type.caption, color = ArnavTheme.colors.contentMuted)
    }
}
@Composable
fun CloudDataPage(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val error by vm.syncError.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val preview by vm.restorePreview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) {
        uri -> uri?.let { vm.exportBackup(context, it) }
    }
    LaunchedEffect(user?.uid) { if (user != null) vm.refreshBackups() }
    Column {
        SettingsGroup(footer = "Likes and YouTube playlist entries merge across devices. Full backups preserve every app database table, settings, queue, lyrics and saved analysis. Restore other data explicitly below. Local audio files and sign-in/API credentials are excluded.") {
            ToggleRow("Cloud sync & backup", s.cloudSync, { v -> vm.update { it.copy(cloudSync = v) } },
                if (user == null) "Sign in to back up your data" else "Signed in as " + (user?.email ?: "you"), enabled = vm.cloudAvailable)
            Divider()
            InfoRow("Status", status.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
            InfoRow("Last synced", if (vm.lastSyncedAt == 0L) "Never" else Formatters.relative(vm.lastSyncedAt, System.currentTimeMillis()))
            error?.let { DataFact("Needs attention", it) }
            Divider()
            ActionRowS("Sync & back up now", enabled = user != null && s.cloudSync && busy == null) { vm.syncNow() }
            Divider()
            ActionRowS("Export my app data", "Save a portable compressed backup", enabled = busy == null) { export.launch("ArnavMusic-backup.gz") }
        }
        SettingsGroup("Account backups", footer = "Newest 30 snapshots shown. Each is immutable and verified before restoring. Older snapshots remain until cloud deletion and can contain history you have since cleared.") {
            ActionRowS("Refresh backups", enabled = user != null && busy == null) { vm.refreshBackups() }
            if (backups.isEmpty()) DataFact("No backups loaded", "Sign in, enable sync and make your first backup. Updated Firestore rules must be deployed first.")
            backups.forEach { backup ->
                Divider()
                ActionRowS(backup.device + " · " + Formatters.relative(backup.createdAt, System.currentTimeMillis()),
                    (backup.counts["tracks"] ?: 0).toString() + " tracks · " + (backup.counts["play_events"] ?: 0) + " listens · " + backup.bytes / 1024 + " KB · Preview restore",
                    enabled = busy == null) { vm.previewBackup(backup) }
            }
        }
    }
    preview?.let { backup ->
        AlertDialog(onDismissRequest = vm::dismissRestore, containerColor = ArnavTheme.colors.surfaceRaised,
            title = { Text("Restore verified backup?") },
            text = { Column {
                Text("From " + backup.device + ". This replaces this device's app data and pauses playback. Local audio files must already exist on this phone. A recovery checkpoint is saved first. Restart the app afterward to refresh cached preferences.")
                Spacer(Modifier.height(Space.m))
                listOf("tracks" to "Tracks", "playlists" to "Playlists", "likes" to "Like records", "play_events" to "Listening history",
                    "recent_searches" to "Searches", "lyrics" to "Lyrics", "tag_overrides" to "Metadata edits", "ai_cache" to "AI responses")
                    .forEach { (key, label) -> Text(label + ": " + (backup.counts[key] ?: 0), style = ArnavTheme.type.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = { vm.restoreBackup() }, enabled = busy == null) { Text("Restore") } },
            dismissButton = { TextButton(onClick = vm::dismissRestore) { Text("Cancel") } },
        )
    }
}
