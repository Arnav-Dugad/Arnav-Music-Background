package com.arnav.music.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arnav.music.core.lyrics.LyricsTranslation
import com.arnav.music.core.lyrics.LyricsTranslator
import com.arnav.music.core.lyrics.Romanizer
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.theme.ArnavTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Secondary text under one lyric line: romanisation first, then translation. */
@Immutable
data class LineExtras(val romanized: String?, val translated: String?)

private const val UNAVAILABLE_VISIBLE_MS = 5_000L

/** Session-wide: the user cancelled a model download, so don't start one again by itself. */
private object TranslationSession {
    var declined by mutableStateOf(false)
}

/** Romanisations / translations for the lines of one song, and the translation status. */
@Stable
class LyricsExtrasState internal constructor() {
    internal var romanized by mutableStateOf<Map<String, String>>(emptyMap())
    internal var translated by mutableStateOf<Map<String, String>>(emptyMap())
    internal var retryKey by mutableIntStateOf(0)

    /** Null when translation is off. */
    var status by mutableStateOf<LyricsTranslation.Status?>(null)
        internal set

    fun forLine(text: String): LineExtras? {
        val key = text.trim()
        if (key.isEmpty()) return null
        val r = romanized[key]
        val t = translated[key]
        return if (r == null && t == null) null else LineExtras(r, t)
    }

    fun cancelDownload() {
        TranslationSession.declined = true
    }

    fun download() {
        TranslationSession.declined = false
        retryKey++
    }

    fun retry() {
        retryKey++
    }

    companion object {
        /** Call when the user switches translation on: their intent overrides an earlier cancel. */
        fun allowDownloads() {
            TranslationSession.declined = false
        }
    }
}

/**
 * Romanisation (Android 10+, ICU) and on-device translation (ML Kit) for [lines], loaded in the
 * background when the corresponding setting is on. Translation downloads the language model on
 * demand; see [LyricsExtrasState.status] and [TranslationStatus].
 */
@Composable
fun rememberLyricsExtras(track: Track, lines: List<String>, translate: Boolean, romanize: Boolean): LyricsExtrasState {
    val context = LocalContext.current
    val state = remember(track.id) { LyricsExtrasState() }

    LaunchedEffect(state, lines, romanize) {
        state.romanized = if (romanize && Romanizer.supported && lines.isNotEmpty()) {
            withContext(Dispatchers.Default) { runCatching { Romanizer.romanize(lines) }.getOrDefault(emptyMap()) }
        } else {
            emptyMap()
        }
    }

    val allowDownload = !TranslationSession.declined
    val retry = state.retryKey
    LaunchedEffect(state, lines, translate, allowDownload, retry) {
        if (!translate || lines.isEmpty()) {
            state.translated = emptyMap()
            state.status = null
            return@LaunchedEffect
        }
        LyricsTranslator.get(context).translate(track.id.value, lines, allowDownload).collect { t ->
            state.translated = t.lines
            state.status = t.status
        }
    }
    return state
}

/**
 * Inline translation status: model download progress (cancellable), a "Download" offer after a
 * cancel, failures with "Retry", or why a song can't be translated. Nothing while all is well.
 */
@Composable
fun TranslationStatus(state: LyricsExtrasState, on: Color, muted: Color, modifier: Modifier = Modifier) {
    val status = state.status ?: return
    // "Can't translate this song" is informational: show it for a few seconds only.
    var dismissed by remember(status) { mutableStateOf(false) }
    if (status is LyricsTranslation.Status.Unavailable) {
        LaunchedEffect(status) {
            delay(UNAVAILABLE_VISIBLE_MS)
            dismissed = true
        }
    }
    if (dismissed) return
    val caption = ArnavTheme.type.caption
    val haptics = ArnavTheme.haptics
    val text: String
    var action: Pair<String, () -> Unit>? = null
    var spinner = false
    when (status) {
        is LyricsTranslation.Status.Downloading -> {
            text = "Downloading translation model (~${status.approxMb} MB)… Wi-Fi recommended"
            action = "Cancel" to state::cancelDownload
            spinner = true
        }
        is LyricsTranslation.Status.NeedsDownload -> {
            text = "Translating ${status.language} needs a ~${status.approxMb} MB download."
            action = "Download" to state::download
        }
        is LyricsTranslation.Status.Failed -> {
            text = status.message
            action = "Retry" to state::retry
        }
        is LyricsTranslation.Status.Unavailable -> text = status.message
        LyricsTranslation.Status.Working -> {
            if (state.translated.isNotEmpty()) return
            text = "Translating…"
            spinner = true
        }
        LyricsTranslation.Status.Done, LyricsTranslation.Status.SameLanguage -> return
    }
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(on.copy(alpha = 0.10f))
            .padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), color = on, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = caption, color = muted, modifier = Modifier.weight(1f, fill = false))
        val a = action
        if (a != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                a.first,
                style = caption.copy(fontWeight = FontWeight.SemiBold),
                color = on,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = a.first) {
                        haptics.press()
                        a.second()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
