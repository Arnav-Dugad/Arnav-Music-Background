package com.arnav.music.feature.insights

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.arnav.music.core.repo.LibraryRepository
import com.arnav.music.domain.intelligence.PlayStats
import com.arnav.music.domain.model.TrackId
import com.arnav.music.ui.theme.ArnavTheme
import org.koin.compose.koinInject
import java.time.ZoneId

/**
 * "Played 23 times · first on 4 Mar 2025" or "Not played yet" (plays of 30 s or more on this
 * device). Shows nothing until loaded, and nothing if the count can't be read.
 */
@Composable
fun TrackPlayCountLine(trackId: TrackId, color: Color, modifier: Modifier = Modifier) {
    val library = koinInject<LibraryRepository>()
    val text by produceState<String?>(null, trackId) {
        runCatching { library.playStats(trackId) }
            .onSuccess { value = PlayStats.line(it, ZoneId.systemDefault()) }
    }
    val line = text ?: return
    Text(line, style = ArnavTheme.type.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
