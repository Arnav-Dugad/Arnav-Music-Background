package com.arnav.music.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.music.core.update.UpdateState
import com.arnav.music.domain.format.Formatters
import com.arnav.music.ui.LocalAppViewModel
import com.arnav.music.ui.components.ArnavIconButton
import com.arnav.music.ui.components.PrimaryButton
import com.arnav.music.ui.components.SecondaryButton
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass

/**
 * Update card: what's new, then a live download readout — bytes of total, percent, speed and
 * time left — then verification and install. [alwaysShow] keeps it visible (Settings) even when
 * there's nothing to do; on Home it only appears when an update needs attention.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier, alwaysShow: Boolean = false) {
    val app = LocalAppViewModel.current
    val state by app.updates.state.collectAsStateWithLifecycle()
    val c = ArnavTheme.colors
    val motion = ArnavTheme.motion
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf<Int?>(null) }
    val actionable = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Verifying ||
        state is UpdateState.ReadyToInstall || state is UpdateState.NeedsPermission || state is UpdateState.Installing ||
        (state is UpdateState.Failed && (state as UpdateState.Failed).release != null)
    val versionCode = when (val s = state) {
        is UpdateState.Available -> s.release.versionCode
        is UpdateState.ReadyToInstall -> s.release.versionCode
        else -> null
    }
    if (!alwaysShow && (!actionable || (versionCode != null && dismissed == versionCode))) return
    if (!app.updates.supported && !alwaysShow) return

    Column(
        modifier.fillMaxWidth().glass(GlassMaterial.Regular, RoundedCornerShape(Radius.l)).padding(Space.l).animateContentSize(motion.responsive()),
    ) {
        AnimatedContent(state, contentKey = { it::class }, transitionSpec = { fadeIn(motion.fast()) togetherWith fadeOut(motion.fast()) }, label = "upd") { s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(
                            when (s) {
                                is UpdateState.Failed -> Icons.Rounded.ErrorOutline
                                is UpdateState.UpToDate -> Icons.Rounded.CheckCircle
                                is UpdateState.Downloading -> Icons.Rounded.Download
                                is UpdateState.Verifying -> Icons.Rounded.Security
                                is UpdateState.ReadyToInstall, is UpdateState.Installing -> Icons.Rounded.InstallMobile
                                else -> Icons.Rounded.SystemUpdate
                            }, null, tint = if (s is UpdateState.Failed) c.danger else c.accent, modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(title(s, app.updates.currentVersionName), style = ArnavTheme.type.titleSmall, color = c.content)
                        Text(subtitle(s, app.updates.lastCheckedAt), style = ArnavTheme.type.caption, color = c.contentMuted)
                    }
                    if (!alwaysShow && (s is UpdateState.Available || s is UpdateState.ReadyToInstall)) {
                        ArnavIconButton(Icons.Rounded.Close, "Later", { dismissed = versionCode }, tint = c.contentSubtle, size = 18.dp)
                    }
                }
                if (s is UpdateState.Downloading) {
                    Spacer(Modifier.height(Space.m))
                    DownloadProgress(s)
                }
                val notes = (s as? UpdateState.Available)?.release?.notes ?: (s as? UpdateState.ReadyToInstall)?.release?.notes
                if (!notes.isNullOrBlank() && alwaysShow) {
                    Spacer(Modifier.height(Space.s))
                    Text(notes.lines().take(6).joinToString("\n"), style = ArnavTheme.type.caption, color = c.contentMuted)
                }
                Spacer(Modifier.height(Space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    when (s) {
                        is UpdateState.Available -> {
                            PrimaryButton("Download · ${Formatters.bytes(s.release.apkSize)}", { app.downloadUpdate(s.release) }, icon = Icons.Rounded.Download)
                        }
                        is UpdateState.Downloading -> SecondaryButton("Cancel", { app.cancelUpdateDownload() })
                        is UpdateState.ReadyToInstall -> PrimaryButton("Install now", { app.installUpdate() }, icon = Icons.Rounded.InstallMobile)
                        is UpdateState.NeedsPermission -> {
                            PrimaryButton("Allow installs", { runCatching { context.startActivity(app.updates.unknownSourcesSettingsIntent()) } })
                            SecondaryButton("Try again", { app.installUpdate() })
                        }
                        is UpdateState.Failed -> s.release?.let { r -> PrimaryButton("Retry", { app.downloadUpdate(r) }) } ?: SecondaryButton("Check again", { app.checkForUpdate() })
                        is UpdateState.Checking, is UpdateState.Verifying, is UpdateState.Installing -> Unit
                        else -> if (app.updates.supported) SecondaryButton("Check for updates", { app.checkForUpdate() })
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(s: UpdateState.Downloading) {
    val c = ArnavTheme.colors
    val animated by animateFloatAsState(s.fraction, ArnavTheme.motion.responsive(), label = "dl")
    val pct = (s.fraction * 100).toInt()
    Column(Modifier.semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(s.fraction, 0f..1f)
        stateDescription = "Downloaded ${Formatters.bytes(s.downloaded)} of ${Formatters.bytes(s.total)}, $pct percent"
    }) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.content.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.7f), c.accent))))
        }
        Spacer(Modifier.height(Space.s))
        Row {
            Text("${Formatters.bytes(s.downloaded)} of ${Formatters.bytes(s.total)}", style = ArnavTheme.type.numeric, color = c.content, modifier = Modifier.weight(1f))
            Text("$pct%", style = ArnavTheme.type.numeric, color = c.accent)
        }
        Text(
            buildString {
                if (s.bytesPerSecond > 0) append("${Formatters.bytes(s.bytesPerSecond)}/s")
                s.secondsLeft?.let { append(" · "); append(if (it < 60) "${it}s left" else "${it / 60} min ${it % 60}s left") }
            }.ifBlank { "Starting…" },
            style = ArnavTheme.type.caption, color = c.contentSubtle,
        )
    }
}

private fun title(s: UpdateState, current: String) = when (s) {
    is UpdateState.Available -> "Update available · ${s.release.versionName}"
    is UpdateState.Downloading -> "Downloading ${s.release.versionName}"
    is UpdateState.Verifying -> "Verifying ${s.release.versionName}"
    is UpdateState.ReadyToInstall -> "Ready to install ${s.release.versionName}"
    is UpdateState.NeedsPermission -> "Allow Arnav Music to install updates"
    is UpdateState.Installing -> "Installing ${s.release.versionName}…"
    is UpdateState.Failed -> "Update problem"
    is UpdateState.Checking -> "Checking for updates…"
    is UpdateState.UpToDate -> "You're up to date"
    UpdateState.Idle -> "Version $current"
}

private fun subtitle(s: UpdateState, lastChecked: Long) = when (s) {
    is UpdateState.Available -> "You have a newer version waiting on GitHub."
    is UpdateState.Downloading -> "Keep using the app — the download continues."
    is UpdateState.Verifying -> "Checking the checksum and Arnav Music's signature."
    is UpdateState.ReadyToInstall -> "Verified. Installing restarts the app."
    is UpdateState.NeedsPermission -> "One-time: turn on \"Allow from this source\", then come back."
    is UpdateState.Installing -> "Android may ask you to confirm."
    is UpdateState.Failed -> s.message
    is UpdateState.Checking -> "Looking at GitHub Releases"
    is UpdateState.UpToDate -> "Checked " + Formatters.relative(s.checkedAt, System.currentTimeMillis())
    UpdateState.Idle -> if (lastChecked == 0L) "Updates come from GitHub Releases" else "Last checked " + Formatters.relative(lastChecked, System.currentTimeMillis())
}

