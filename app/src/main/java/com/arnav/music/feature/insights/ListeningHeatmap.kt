package com.arnav.music.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnav.music.domain.intelligence.Heatmap
import com.arnav.music.domain.intelligence.HeatmapGrid
import com.arnav.music.domain.intelligence.HeatmapWeek
import com.arnav.music.ui.theme.ArnavTheme
import com.arnav.music.ui.theme.GlassMaterial
import com.arnav.music.ui.theme.Radius
import com.arnav.music.ui.theme.Space
import com.arnav.music.ui.theme.glass
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.format.TextStyle as DateTextStyle

private val CELL = 12.dp
private val GAP = 3.dp
private val STEP = CELL + GAP
private val MONTH_ROW = 16.dp

/**
 * GitHub-style listening calendar: one column per week (Monday on top), one square per day, shaded
 * by minutes listened. Scrolls sideways on narrow screens, starting at the current week. Tapping a
 * square shows that day's minutes; TalkBack reads one summary per week.
 */
@Composable
fun ListeningHeatmap(grid: HeatmapGrid, modifier: Modifier = Modifier) {
    val c = ArnavTheme.colors
    val haptics = ArnavTheme.haptics
    val tiny = ArnavTheme.type.caption.copy(fontSize = 9.sp, lineHeight = 10.sp)
    val locale = remember { Locale.getDefault() }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val weekFormat = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val shades = listOf(
        c.outline.copy(alpha = 0.55f),
        c.accent.copy(alpha = 0.28f),
        c.accent.copy(alpha = 0.5f),
        c.accent.copy(alpha = 0.75f),
        c.accent,
    )
    val selectedColor = c.content
    var selected by remember(grid) { mutableStateOf<LocalDate?>(null) }
    val scroll = rememberScrollState()
    // Start at the current week once the content has been measured.
    LaunchedEffect(grid) {
        val max = snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
        scroll.scrollTo(max)
    }

    Column(modifier.fillMaxWidth().glass(GlassMaterial.Thin, RoundedCornerShape(Radius.l)).padding(Space.l)) {
        Text(summary(grid, locale), style = ArnavTheme.type.bodySmall, color = c.content)
        Spacer(Modifier.height(Space.xs))
        val day = selected?.let { grid.day(it) }
        Text(
            if (day != null) "${day.date.format(dayFormat)} · ${minutesText(day.minutes)}" else "Tap a day to see how long you listened.",
            style = ArnavTheme.type.caption, color = if (day != null) c.content else c.contentSubtle, maxLines = 1,
        )
        Spacer(Modifier.height(Space.s))
        Row {
            // Mon / Wed / Fri, aligned with the rows of squares.
            Column(Modifier.padding(top = MONTH_ROW).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(GAP)) {
                for (row in 0 until 7) {
                    Box(Modifier.height(CELL), contentAlignment = Alignment.CenterStart) {
                        if (row % 2 == 0 && row <= 4) {
                            Text(DayOfWeek.of(row + 1).getDisplayName(DateTextStyle.SHORT, locale), style = tiny, color = c.contentSubtle, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Spacer(Modifier.width(Space.xs))
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Column {
                    Box(Modifier.width(STEP * grid.weeks.size).height(MONTH_ROW).clearAndSetSemantics { }) {
                        grid.monthLabels.forEach { label ->
                            Text(
                                label.month.month.getDisplayName(DateTextStyle.SHORT, locale), style = tiny, color = c.contentMuted,
                                maxLines = 1, softWrap = false, modifier = Modifier.offset(x = STEP * label.weekIndex),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        grid.weeks.forEach { week ->
                            WeekColumn(
                                week = week,
                                description = weekDescription(week, weekFormat),
                                shades = shades,
                                selected = selected,
                                selectedColor = selectedColor,
                                onSelect = { date -> selected = date; haptics.select() },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Lighter squares mean fewer minutes, darker squares more" },
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Less", style = ArnavTheme.type.caption, color = c.contentSubtle)
            Spacer(Modifier.width(Space.xs))
            shades.forEach { shade ->
                Box(Modifier.padding(horizontal = 1.5.dp).size(CELL).clip(RoundedCornerShape(3.dp)).background(shade))
            }
            Spacer(Modifier.width(Space.xs))
            Text("More", style = ArnavTheme.type.caption, color = c.contentSubtle)
        }
    }
}

@Composable
private fun WeekColumn(
    week: HeatmapWeek,
    description: String,
    shades: List<Color>,
    selected: LocalDate?,
    selectedColor: Color,
    onSelect: (LocalDate) -> Unit,
) {
    Box(
        Modifier
            .size(width = CELL, height = STEP * 7 - GAP)
            .semantics { contentDescription = description }
            .pointerInput(week) {
                detectTapGestures { offset ->
                    val row = (offset.y / STEP.toPx()).toInt().coerceIn(0, 6)
                    val d = week.days[row]
                    if (d.inRange) onSelect(d.date)
                }
            }
            .drawBehind {
                val cell = CELL.toPx()
                val step = STEP.toPx()
                val radius = CornerRadius(3.dp.toPx())
                week.days.forEachIndexed { i, d ->
                    if (!d.inRange) return@forEachIndexed
                    val topLeft = Offset(0f, i * step)
                    drawRoundRect(shades[d.level.coerceIn(0, Heatmap.MAX_LEVEL)], topLeft, Size(cell, cell), radius)
                    if (d.date == selected) {
                        val w = 1.5.dp.toPx()
                        drawRoundRect(
                            selectedColor, Offset(topLeft.x + w / 2, topLeft.y + w / 2), Size(cell - w, cell - w), radius, style = Stroke(w),
                        )
                    }
                }
            },
    )
}

private fun minutesText(m: Int): String = when {
    m <= 0 -> "no music"
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "${m / 60} h ${m % 60} min"
}

private fun weekDescription(week: HeatmapWeek, format: DateTimeFormatter): String {
    val start = week.start.format(format)
    val days = week.activeDays
    return if (days == 0) "Week of $start: no music"
    else "Week of $start: ${minutesText(week.totalMinutes)} over ${if (days == 1) "1 day" else "$days days"}"
}

private fun summary(grid: HeatmapGrid, locale: Locale): String {
    if (grid.isEmpty) return "No listening in the past year yet. Days you play music will fill in here."
    val n = NumberFormat.getIntegerInstance(locale)
    val minutes = grid.totalMinutes
    val days = grid.activeDays
    return "${n.format(minutes)} ${if (minutes == 1) "minute" else "minutes"} on ${n.format(days)} ${if (days == 1) "day" else "days"} in the past year"
}
