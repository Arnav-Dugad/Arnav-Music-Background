package com.arnav.music.feature.credits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.arnav.music.core.db.TagOverrideEntity
import com.arnav.music.core.metadata.AutoTagger
import com.arnav.music.domain.metadata.AutoTagQuery
import com.arnav.music.domain.model.Track
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Lets the user fix an on-device song's title, artist and album. Saved as a tag override on this
 * device (the file itself is never changed); "Use the file's info" removes the correction.
 */
@Composable
fun EditSongInfoDialog(track: Track, onDismiss: () -> Unit) {
    val c = ArnavTheme.colors
    val tagger = koinInject<AutoTagger>()
    // App-wide scope: the save must finish even though the sheet closes right away.
    val appScope = koinInject<CoroutineScope>()
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist.takeUnless { AutoTagQuery.isPlaceholderArtist(it) }.orEmpty()) }
    var album by remember(track.id) { mutableStateOf(track.album.orEmpty()) }
    val existing by produceState<TagOverrideEntity?>(null, track.id) { value = tagger.override(track.id) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        title = { Text("Edit song info", style = ArnavTheme.type.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(Radius.m), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("Artist") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(Radius.m), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it.take(200) }, label = { Text("Album") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(Radius.m), modifier = Modifier.fillMaxWidth())
                Text(
                    "Saved in Arnav only — your music file isn't changed.",
                    style = ArnavTheme.type.caption, color = c.contentSubtle,
                )
                val row = existing
                if (row != null) {
                    Text(
                        if (row.source == AutoTagger.SOURCE_MUSICBRAINZ) "Filled in from MusicBrainz. Use the file's own info instead" else "Use the file's own info instead",
                        style = ArnavTheme.type.label, color = c.accent,
                        modifier = Modifier.clip(RoundedCornerShape(Radius.s)).clickable {
                            appScope.launch { runCatching { tagger.reset(track.id) } }
                            onDismiss()
                        }.padding(vertical = Space.xs),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    val t = title; val a = artist; val al = album
                    appScope.launch { runCatching { tagger.editTags(track, t, a, al) } }
                    onDismiss()
                },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
