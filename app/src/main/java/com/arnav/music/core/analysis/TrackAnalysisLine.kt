package com.arnav.music.core.analysis

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.arnav.music.core.db.ArnavDatabase
import com.arnav.music.ui.theme.ArnavTheme
import org.koin.compose.koinInject

/** "124 BPM · 8A · A minor · −9 LUFS · High energy" for an analysed local track; renders nothing otherwise. */
@Composable
fun TrackAnalysisLine(trackId: String, color: Color, modifier: Modifier = Modifier) {
    val db = koinInject<ArnavDatabase>()
    val dao = remember(db) { db.audioFeatures() }
    val text by produceState<String?>(null, trackId) {
        value = null
        dao.observe(trackId).collect { row ->
            value = row?.takeIf { it.ok }?.let { AudioFeatures.describe(it.bpm, it.loudnessDb, it.energy, it.musicalKey) }
        }
    }
    val line = text ?: return
    Text(line, style = ArnavTheme.type.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
