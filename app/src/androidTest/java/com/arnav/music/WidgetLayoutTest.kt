package com.arnav.music

import android.graphics.Rect
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.appwidget.AppWidgetHostView
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.widget.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.math.roundToInt

/** Inflate the actual RemoteViews at their advertised sizes, including long song names. */
@RunWith(AndroidJUnit4::class)
class WidgetLayoutTest {
    @Test fun smallestAndExpandedWidgetsKeepTextAndArtworkWithinTheirBounds() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = GlobalContext.get().get<SettingsRepository>()
        settings.loaded.first { it }
        delay(600) // Let the application's initial empty playback snapshot settle.
        WidgetBus.publish(context, WidgetSnapshot(
            "A very long song title that must stay inside the widget", "An artist with a very long name",
            null, true, true, true,
            (1..3).map { index -> WidgetQueueItem(index, index.toLong(), "Next song $index with a very long title", "Another long artist name", true) },
            61000L, 240000L,
        ))
        WidgetBus.publishLyric(WidgetLyric(WidgetLyric.State.SYNCED, "A very long song title", "Artist",
            "A long lyric line which wraps onto the next line", "The next lyric line"))
        val sizes = listOf(
            ArnavWidget() to DpSize(180.dp, 64.dp),
            ArnavWidget() to DpSize(260.dp, 184.dp),
            ArnavWidget() to DpSize(320.dp, 248.dp),
            QueueWidget() to DpSize(220.dp, 220.dp),
            QueueWidget() to DpSize(300.dp, 280.dp),
            QueueWidget() to DpSize(340.dp, 340.dp),
            LyricsWidget() to DpSize(220.dp, 136.dp),
            LyricsWidget() to DpSize(300.dp, 220.dp),
        )
        for (materialYou in listOf(false, true)) {
            settings.update { it.copy(widgetMaterialYou = materialYou) }
            settings.settings.first { it.widgetMaterialYou == materialYou }
            for (fontScale in listOf(1f, 1.3f)) {
                for ((widget, size) in sizes) verify(widget, size, fontScale)
            }
        }
    }

    private suspend fun verify(widget: GlanceAppWidget, size: DpSize, fontScale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { this.fontScale = fontScale })
        val remote = widget.compose(context, size = size)
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            val width = (size.width.value * density).roundToInt()
            val height = (size.height.value * density).roundToInt()
            // A real host selects the appropriate responsive RemoteViews during layout.
            // RemoteViews.apply alone always selects the smallest variant.
            val host = AppWidgetHostView(context)
            host.setPadding(0, 0, 0, 0)
            host.updateAppWidget(remote)
            repeat(3) {
                host.forceLayout()
                host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, width, height)
            }
            var textCount = 0
            val texts = mutableListOf<String>()
            fun visit(child: View) {
                if (child.visibility != View.VISIBLE) return
                if (child is TextView || child is ImageView) {
                    val bounds = Rect(0, 0, child.width, child.height)
                    host.offsetDescendantRectToMyCoords(child, bounds)
                    val label = "${widget.javaClass.simpleName} $size: ${(child as? TextView)?.text ?: child.contentDescription} $bounds"
                    assertTrue("Clipped widget content: $label", bounds.left >= -1 && bounds.top >= -1 && bounds.right <= width + 1 && bounds.bottom <= height + 1)
                    if (child is TextView && child.text.isNotBlank()) {
                        textCount++
                        texts += child.text.toString()
                        assertTrue("Hidden widget text: $label", child.width > 0 && child.height > 0)
                    }
                }
                if (child is ViewGroup) for (index in 0 until child.childCount) visit(child.getChildAt(index))
            }
            visit(host)
            assertTrue("Widget must contain song text", textCount > 0)
            val expected = if (widget is LyricsWidget) "A long lyric line which wraps onto the next line"
                else "A very long song title that must stay inside the widget"
            assertTrue("Widget must show its real content, not an error layout: $texts", expected in texts)
            if (widget is QueueWidget && size.height.value >= 280f) {
                val rows = if (size.height.value >= 340f) 3 else 1
                for (index in 1..rows) assertTrue("Queue row $index must survive RemoteViews translation: $texts",
                    "Next song $index with a very long title" in texts)
            }
        }
    }
}
