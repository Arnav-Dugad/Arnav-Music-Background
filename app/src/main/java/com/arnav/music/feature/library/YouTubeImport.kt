package com.arnav.music.feature.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.arnav.music.core.youtube.ImportProgress
import com.arnav.music.core.youtube.ImportSummary
import com.arnav.music.core.youtube.RefreshSummary
import com.arnav.music.core.youtube.RemotePlaylist
import com.arnav.music.core.youtube.YouTubeImporter
import com.arnav.music.domain.provider.MusicError
import com.arnav.music.ui.components.Artwork
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.player.ArnavSheet
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.compose.viewmodel.koinViewModel

sealed interface ImportUi {
    data object Intro : ImportUi
    data object Connecting : ImportUi
    data class Pick(val playlists: List<RemotePlaylist>, val selected: Set<String>) : ImportUi
    data class Importing(val progress: ImportProgress?) : ImportUi
    data class Done(val summary: ImportSummary) : ImportUi
    data class Refreshed(val summary: RefreshSummary) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/**
 * Drives the import sheet. The OAuth access token is held only in this ViewModel's memory for the
 * length of the sheet and is dropped when it closes; it is never persisted or logged.
 */
class YouTubeImportViewModel(private val importer: YouTubeImporter) : ViewModel() {
    private val _ui = MutableStateFlow<ImportUi>(ImportUi.Intro)
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()
    private var token: String? = null

    /** How many playlists were imported from YouTube before (for "Refresh imported playlists"). */
    private val _importedCount = MutableStateFlow(0)
    val importedCount: StateFlow<Int> = _importedCount.asStateFlow()

    init { loadImportedCount() }

    private fun loadImportedCount() {
        viewModelScope.launch { _importedCount.value = runCatching { importer.importedPlaylists().size }.getOrDefault(0) }
    }

    /** Re-reads every imported playlist with a token used only for this call. */
    fun refreshAuthorized(accessToken: String?) {
        if (accessToken.isNullOrBlank()) { fail("Google didn't return access. Try again."); return }
        _ui.value = ImportUi.Importing(null)
        viewModelScope.launch {
            runCatching { importer.refresh(accessToken, null) { p -> _ui.value = ImportUi.Importing(p) } }
                .onSuccess { _ui.value = ImportUi.Refreshed(it); loadImportedCount() }
                .onFailure { fail(describe(it)) }
        }
    }

    fun connecting() { _ui.value = ImportUi.Connecting }

    fun authorized(accessToken: String?) {
        if (accessToken.isNullOrBlank()) { fail("Google didn't return access. Try again."); return }
        token = accessToken
        viewModelScope.launch {
            runCatching { importer.playlists(accessToken) }
                .onSuccess { list ->
                    _ui.value = if (list.isEmpty()) ImportUi.Failed("No playlists found on this YouTube account.")
                    else ImportUi.Pick(list, list.filter { !it.liked }.map { it.id }.toSet())
                }
                .onFailure { fail(describe(it)) }
        }
    }

    fun toggle(id: String) {
        val s = _ui.value as? ImportUi.Pick ?: return
        _ui.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun selectAll(all: Boolean) {
        val s = _ui.value as? ImportUi.Pick ?: return
        _ui.value = s.copy(selected = if (all) s.playlists.map { it.id }.toSet() else emptySet())
    }

    fun import() {
        val s = _ui.value as? ImportUi.Pick ?: return
        val t = token ?: return fail("Session expired. Connect again.")
        val chosen = s.playlists.filter { it.id in s.selected }
        if (chosen.isEmpty()) return
        _ui.value = ImportUi.Importing(null)
        viewModelScope.launch {
            runCatching { importer.import(t, chosen) { p -> _ui.value = ImportUi.Importing(p) } }
                .onSuccess { _ui.value = ImportUi.Done(it) }
                .onFailure { fail(describe(it)) }
        }
    }

    fun fail(message: String) { _ui.value = ImportUi.Failed(message) }

    fun reset() { token = null; _ui.value = ImportUi.Intro; loadImportedCount() }

    override fun onCleared() { token = null }

    private fun describe(e: Throwable): String = describeYouTubeError(e)
}

/** Plain-language reason for a failed YouTube import or refresh. */
fun describeYouTubeError(e: Throwable): String = when (e) {
    MusicError.Offline -> "You're offline. Connect and try again."
    MusicError.QuotaExhausted -> "Today's free YouTube quota is used up. Try again tomorrow."
    MusicError.PermissionDenied -> "Google access expired. Connect again."
    MusicError.NotConfigured -> "YouTube Data API v3 isn't enabled for this app's Google Cloud project yet."
    is MusicError.Http -> if (e.code == 403) "YouTube refused the request (403). The account may have no channel, or the app isn't approved for this scope yet." else "YouTube error ${e.code}. Try again."
    else -> "Import failed. Try again."
}

private val YOUTUBE_READONLY = Scope("https://www.googleapis.com/auth/youtube.readonly")

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun youTubeReadonlyRequest(): AuthorizationRequest =
    AuthorizationRequest.builder().setRequestedScopes(listOf(YOUTUBE_READONLY)).build()

/**
 * Asks Google for a youtube.readonly token. When consent is needed, Google's own consent screen is
 * shown; [onToken] gets the token (or null when none came back), [onError] a message to show.
 * The token is handed straight to the caller and never stored.
 */
@Composable
fun rememberYouTubeAuthorizer(onToken: (String?) -> Unit, onError: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenCallback by rememberUpdatedState(onToken)
    val errorCallback by rememberUpdatedState(onError)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) { errorCallback("Google access wasn't granted."); return@rememberLauncherForActivityResult }
        val result: AuthorizationResult? = runCatching {
            Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data ?: Intent())
        }.getOrNull()
        tokenCallback(result?.accessToken)
    }
    return remember(ctx, consent) {
        {
            scope.launch {
                val activity = ctx.findActivity()
                if (activity == null) { errorCallback("Couldn't open Google sign-in."); return@launch }
                runCatching { Identity.getAuthorizationClient(activity).authorize(youTubeReadonlyRequest()).await() }
                    .onSuccess { r ->
                        val pending = r.pendingIntent
                        if (r.hasResolution() && pending != null) consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        else tokenCallback(r.accessToken)
                    }
                    .onFailure { e ->
                        errorCallback(
                            if (e is ApiException && e.statusCode == 10) "This build's signing key isn't registered as an Android OAuth client in the Firebase project."
                            else "Google sign-in isn't available on this device.",
                        )
                    }
            }
            Unit
        }
    }
}

/**
 * Token for a background refresh, only when Google can grant it without showing anything
 * (the user consented before). Returns null when consent would be needed, or on any failure.
 */
suspend fun silentYouTubeToken(context: Context): String? = runCatching {
    val client = context.findActivity()?.let { Identity.getAuthorizationClient(it) } ?: Identity.getAuthorizationClient(context)
    val r = client.authorize(youTubeReadonlyRequest()).await()
    if (r.hasResolution()) null else r.accessToken
}.getOrNull()

@Composable
fun YouTubeImportSheet(onDismiss: () -> Unit, vm: YouTubeImportViewModel = koinViewModel()) {
    val c = ArnavTheme.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val importedCount by vm.importedCount.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    val authorize = rememberYouTubeAuthorizer(
        onToken = { t -> if (refreshing) vm.refreshAuthorized(t) else vm.authorized(t) },
        onError = { vm.fail(it) },
    )

    fun connect() { refreshing = false; vm.connecting(); authorize() }
    fun refresh() { refreshing = true; vm.connecting(); authorize() }

    ArnavSheet({ vm.reset(); onDismiss() }) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            Text("Import from YouTube", style = ArnavTheme.type.title, color = c.content)
            Text(
                "Copies your YouTube playlists into Arnav playlists. Read-only access; songs still play in the YouTube player.",
                style = ArnavTheme.type.bodySmall, color = c.contentMuted,
            )
            Spacer(Modifier.height(Space.l))
            AnimatedContent(ui::class, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "import") { _ ->
                when (val s = ui) {
                    ImportUi.Intro -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Text(
                            "YouTube Music's own \"Liked music\" isn't available to apps; your YouTube \"Liked videos\" and every playlist you made or saved are.",
                            style = ArnavTheme.type.bodySmall, color = c.contentSubtle,
                        )
                        PrimaryButton("Connect YouTube account", { connect() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CloudDownload)
                        if (importedCount > 0) {
                            SecondaryButton(
                                "Refresh $importedCount imported ${if (importedCount == 1) "playlist" else "playlists"}", { refresh() },
                                Modifier.fillMaxWidth(), icon = Icons.Rounded.Sync,
                            )
                            Text(
                                "Refreshing replaces songs with the current YouTube version. Songs you added in Arnav to those playlists are removed.",
                                style = ArnavTheme.type.caption, color = c.contentSubtle,
                            )
                        }
                    }
                    ImportUi.Connecting -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Text("Waiting for Google…", style = ArnavTheme.type.body, color = c.content)
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.s)), color = c.accent, trackColor = c.outline)
                    }
                    is ImportUi.Pick -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.selected.size} of ${s.playlists.size} selected", style = ArnavTheme.type.label, color = c.contentMuted, modifier = Modifier.weight(1f))
                            val all = s.selected.size == s.playlists.size
                            Text(
                                if (all) "Select none" else "Select all", style = ArnavTheme.type.label, color = c.accent,
                                modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable { vm.selectAll(!all) }.padding(Space.s),
                            )
                        }
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(s.playlists, key = { it.id }) { p ->
                                val on = p.id in s.selected
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m))
                                        .clickable(role = Role.Checkbox) { vm.toggle(p.id) }.padding(vertical = Space.s),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (p.liked && p.artworkUrl == null) Icon(Icons.Rounded.Favorite, null, tint = c.accent, modifier = Modifier.size(48.dp))
                                    else Artwork(p.artworkUrl, p.id, Modifier.size(48.dp), RoundedCornerShape(Radius.s), decodeSize = 144)
                                    Spacer(Modifier.width(Space.m))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.title, style = ArnavTheme.type.body, color = c.content, maxLines = 1)
                                        Text(
                                            if (p.itemCount >= 0) "${p.itemCount} ${if (p.itemCount == 1) "video" else "videos"}" else "Your likes",
                                            style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                                        )
                                    }
                                    Icon(
                                        if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        if (on) "Selected" else "Not selected", tint = if (on) c.accent else c.contentSubtle,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(Space.m))
                        PrimaryButton("Import ${s.selected.size} ${if (s.selected.size == 1) "playlist" else "playlists"}", { vm.import() }, Modifier.fillMaxWidth(), enabled = s.selected.isNotEmpty())
                    }
                    is ImportUi.Importing -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        val p = s.progress
                        Text(p?.let { (if (refreshing) "Refreshing " else "Importing ") + it.playlist } ?: "Starting…", style = ArnavTheme.type.body, color = c.content, maxLines = 1)
                        if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.outline)
                        else {
                            val inner = if (p.tracksExpected > 0) (p.tracksRead.toFloat() / p.tracksExpected).coerceIn(0f, 1f) else 0f
                            val overall = ((p.playlistIndex + inner) / p.playlistCount).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.outline)
                            Text(
                                "Playlist ${p.playlistIndex + 1} of ${p.playlistCount} · ${p.tracksRead}${if (p.tracksExpected > 0) " / ${p.tracksExpected}" else ""} songs read",
                                style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                            )
                        }
                    }
                    is ImportUi.Done -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.success)
                            Spacer(Modifier.width(Space.s))
                            Text(
                                "Imported ${s.summary.tracks} songs into ${s.summary.playlists} ${if (s.summary.playlists == 1) "playlist" else "playlists"}",
                                style = ArnavTheme.type.body, color = c.content,
                            )
                        }
                        if (s.summary.skipped > 0) Text(
                            "${s.summary.skipped} skipped: private, deleted, or not allowed to play outside YouTube.",
                            style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                        )
                        PrimaryButton("Done", { vm.reset(); onDismiss() }, Modifier.fillMaxWidth())
                    }
                    is ImportUi.Refreshed -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.success)
                            Spacer(Modifier.width(Space.s))
                            Text(
                                if (s.summary.playlists == 0) "Nothing to refresh"
                                else "Refreshed ${s.summary.playlists} ${if (s.summary.playlists == 1) "playlist" else "playlists"} · ${s.summary.tracks} songs",
                                style = ArnavTheme.type.body, color = c.content,
                            )
                        }
                        if (s.summary.missing > 0) Text(
                            "${s.summary.missing} left as they were: deleted, private or empty on YouTube.",
                            style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                        )
                        if (s.summary.skipped > 0) Text(
                            "${s.summary.skipped} videos skipped: private, deleted, or not allowed to play outside YouTube.",
                            style = ArnavTheme.type.bodySmall, color = c.contentMuted,
                        )
                        PrimaryButton("Done", { vm.reset(); onDismiss() }, Modifier.fillMaxWidth())
                    }
                    is ImportUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger)
                            Spacer(Modifier.width(Space.s))
                            Text(s.message, style = ArnavTheme.type.body, color = c.content)
                        }
                        SecondaryButton("Try again", { if (refreshing) refresh() else connect() }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
