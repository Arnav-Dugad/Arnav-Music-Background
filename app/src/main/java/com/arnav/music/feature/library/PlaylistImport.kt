package com.arnav.music.feature.library

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.common.Clock
import com.arnav.music.core.importer.FileImportSummary
import com.arnav.music.core.importer.FileImporter
import com.arnav.music.core.importer.ImportMatcher
import com.arnav.music.core.importer.MatchProgress
import com.arnav.music.core.youtube.YouTubeImporter
import com.arnav.music.core.youtube.YouTubeRepository
import com.arnav.music.domain.importer.ImportFormatException
import com.arnav.music.domain.importer.PlaylistFiles
import com.arnav.music.domain.quota.QuotaState
import com.arnav.music.ui.components.NoticeBanner
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.player.ArnavSheet
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.io.IOException

sealed interface FileImportUi {
    data object Choose : FileImportUi
    data object Reading : FileImportUi
    data class Done(val summary: FileImportSummary) : FileImportUi
    data class Failed(val message: String) : FileImportUi
}

/**
 * Playlist imports from files (Spotify data export, CSV), the background-matching banner, and the
 * quiet once-a-day refresh of playlists imported from YouTube.
 */
class PlaylistImportViewModel(
    context: Context,
    private val files: FileImporter,
    private val matcher: ImportMatcher,
    private val youtubeImporter: YouTubeImporter,
    private val youtube: YouTubeRepository,
    private val clock: Clock,
) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences(ImportMatcher.PREFS, Context.MODE_PRIVATE)

    private val _file = MutableStateFlow<FileImportUi>(FileImportUi.Choose)
    val file: StateFlow<FileImportUi> = _file.asStateFlow()

    val progress: StateFlow<MatchProgress> = matcher.progress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchProgress(0))

    fun importFile(uri: Uri) {
        if (_file.value == FileImportUi.Reading) return
        _file.value = FileImportUi.Reading
        viewModelScope.launch {
            runCatching {
                val parsed = files.read(uri)
                matcher.import(parsed, label = runCatching { files.displayName(uri) }.getOrNull())
            }
                .onSuccess { _file.value = FileImportUi.Done(it) }
                .onFailure { e ->
                    _file.value = FileImportUi.Failed(
                        when (e) {
                            is ImportFormatException -> e.message ?: PlaylistFiles.NOT_A_PLAYLIST
                            is IOException, is SecurityException -> "Couldn't read this file. Try picking it again."
                            else -> "Import didn't finish. Try again."
                        },
                    )
                }
        }
    }

    fun resetFile() { if (_file.value != FileImportUi.Reading) _file.value = FileImportUi.Choose }

    fun matchMoreNow() = matcher.matchMoreNow()

    // ---- Quiet daily refresh of playlists imported from YouTube ----

    /** Over 24 h since the last check, quota is healthy, and there is something to refresh. */
    suspend fun silentRefreshDue(): Boolean {
        if (clock.now() - prefs.getLong(KEY_YT_REFRESH, 0L) < DAY_MS) return false
        if (youtube.quotaState() != QuotaState.NORMAL) return false
        return runCatching { youtubeImporter.importedPlaylists().isNotEmpty() }.getOrDefault(false)
    }

    /** Records today's check, whether or not a token was available, so we ask Google at most daily. */
    fun markRefreshChecked() { prefs.edit().putLong(KEY_YT_REFRESH, clock.now()).apply() }

    /** Refreshes in the background with a token used only for this call; failures stay silent. */
    fun refreshQuietly(token: String) {
        markRefreshChecked()
        viewModelScope.launch { runCatching { youtubeImporter.refresh(token, record = false) } }
    }

    private companion object {
        const val KEY_YT_REFRESH = "yt_refresh_at"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** "Import playlists": YouTube account, Spotify data export, or a CSV file. */
@Composable
fun ImportPlaylistsSheet(
    onDismiss: () -> Unit,
    onYouTube: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onHistory: (() -> Unit)? = null,
    vm: PlaylistImportViewModel = koinViewModel(),
) {
    val c = ArnavTheme.colors
    val ui by vm.file.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importFile(uri)
    }
    val close = { vm.resetFile(); onDismiss() }

    ArnavSheet(close) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Text("Import playlists", style = ArnavTheme.type.title, color = c.content)
            Text(
                "Bring your playlists from other apps. Files are read on this phone and never uploaded.",
                style = ArnavTheme.type.bodySmall, color = c.contentMuted,
            )
            Spacer(Modifier.height(Space.l))
            AnimatedContent(ui::class, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "fileImport") { _ ->
                when (val s = ui) {
                    FileImportUi.Choose -> Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        ImportOption(Icons.Rounded.OndemandVideo, "From YouTube", "Your playlists and liked videos, read-only") { onYouTube() }
                        ImportOption(
                            Icons.Rounded.LibraryMusic, "From Spotify export (.zip / .json)",
                            "From Spotify's \"Download your data\" (Account → Privacy). Pick the .zip, or Playlist1.json inside it.",
                        ) { picker.launch(FileImporter.SPOTIFY_TYPES) }
                        ImportOption(Icons.Rounded.TableChart, "From CSV", "Exportify, or any spreadsheet with title and artist columns") {
                            picker.launch(FileImporter.CSV_TYPES)
                        }
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "Songs already on this phone match instantly. The rest are matched to YouTube uploads a few at a time in the background.",
                            style = ArnavTheme.type.caption, color = c.contentSubtle,
                        )
                        if (onHistory != null) {
                            Row(
                                Modifier.clip(RoundedCornerShape(Radius.s)).clickable(role = Role.Button, onClick = onHistory).padding(vertical = Space.s, horizontal = Space.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.History, null, tint = c.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Space.s))
                                Text("Import history · undo an import", style = ArnavTheme.type.label, color = c.accent)
                            }
                        }
                    }
                    FileImportUi.Reading -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Text("Reading your file…", style = ArnavTheme.type.body, color = c.content)
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.s)), color = c.accent, trackColor = c.outline)
                        Text("Checking songs on this phone and ones you've played before first. No searches yet.", style = ArnavTheme.type.bodySmall, color = c.contentMuted)
                    }
                    is FileImportUi.Done -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        val sum = s.summary
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.success)
                            Spacer(Modifier.width(Space.s))
                            Text(importHeadline(sum), style = ArnavTheme.type.body, color = c.content)
                        }
                        Text(importDetail(sum), style = ArnavTheme.type.bodySmall, color = c.contentMuted)
                        val single = sum.playlists.singleOrNull()
                        if (single != null) {
                            PrimaryButton("Open playlist", { vm.resetFile(); onOpenPlaylist(single.playlistId) }, Modifier.fillMaxWidth())
                            SecondaryButton("Done", close, Modifier.fillMaxWidth())
                        } else {
                            PrimaryButton("Done", close, Modifier.fillMaxWidth())
                        }
                    }
                    is FileImportUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger)
                            Spacer(Modifier.width(Space.s))
                            Text(s.message, style = ArnavTheme.type.body, color = c.content)
                        }
                        Text(
                            "Spotify: use the .zip from \"Download your data\" or Playlist1.json inside it. CSV: the first row should name the columns, such as Track Name and Artist Name(s).",
                            style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                        )
                        SecondaryButton("Choose another file", { vm.resetFile() }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun importHeadline(sum: FileImportSummary): String {
    val single = sum.playlists.singleOrNull()
    return if (single != null) "Imported ${songs(sum.total)} into “${single.name}”"
    else "Imported ${songs(sum.total)} into ${sum.playlists.size} playlists"
}

private fun importDetail(sum: FileImportSummary): String = when {
    sum.pending == 0 -> "All ${songs(sum.matched)} matched instantly."
    else -> {
        val days = (sum.pending + ImportMatcher.DAILY_SEARCH_CAP - 1) / ImportMatcher.DAILY_SEARCH_CAP
        val pace = if (days <= 1) "should be done within a day" else "will take about $days days"
        "${sum.matched} matched instantly; ${sum.pending} will be matched in the background. " +
            "YouTube's free daily search limit allows ~100 a day, so Arnav uses up to ${ImportMatcher.DAILY_SEARCH_CAP} for imports and leaves the rest for you. This import $pace."
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = ArnavTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.s)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = ArnavTheme.type.titleSmall, color = c.content)
            Text(subtitle, style = ArnavTheme.type.caption, color = c.contentMuted)
        }
    }
}

/** Small persistent note in Library while imported songs are still waiting for a match. */
@Composable
fun ImportMatchBanner(progress: MatchProgress, onMatchMore: () -> Unit, modifier: Modifier = Modifier) {
    if (progress.open <= 0) return
    val n = progress.open
    val text = when {
        progress.running -> "Matching ${songs(n)} from your imports…"
        progress.quotaLow -> "${songs(n)} from your imports waiting to match · continues after YouTube's daily limit resets"
        progress.dailyCapReached -> "${songs(n)} from your imports waiting to match · continues tomorrow"
        else -> "Matching ${songs(n)} from your imports · continues automatically"
    }
    val canRunNow = !progress.running && !progress.quotaLow
    NoticeBanner(
        Icons.Rounded.Sync, text, modifier,
        action = if (canRunNow) "Match more now" else null,
        onAction = if (canRunNow) onMatchMore else null,
        tone = ArnavTheme.colors.accent,
    )
}
