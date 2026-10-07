package com.arnav.music.widget

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.arnav.music.domain.model.SourceType
import com.arnav.music.domain.queue.QueueState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One "Up next" row. [uid] identifies the queue entry so a tap still hits the right item if the queue moved. */
data class WidgetQueueItem(val index: Int, val uid: Long, val title: String, val artist: String, val youtube: Boolean) {
    companion object {
        const val MAX = 5

        /** The next [MAX] items after the current one. Cheap: never copies the whole queue. */
        fun upNext(queue: QueueState, max: Int = MAX): List<WidgetQueueItem> {
            if (queue.items.isEmpty()) return emptyList()
            val from = (queue.currentIndex + 1).coerceAtLeast(0)
            val to = (from + max).coerceAtMost(queue.items.size)
            if (from >= to) return emptyList()
            return (from until to).map { i ->
                val t = queue.items[i].track
                WidgetQueueItem(i, queue.items[i].uid, t.title, t.artist, t.source == SourceType.YOUTUBE)
            }
        }

        /** Identity of the visible part of the queue, used as part of the app's refresh key. */
        fun key(queue: QueueState): List<Long> {
            if (queue.items.isEmpty()) return emptyList()
            val from = (queue.currentIndex + 1).coerceAtLeast(0)
            val to = (from + MAX).coerceAtMost(queue.items.size)
            return if (from >= to) emptyList() else (from until to).map { queue.items[it].uid }
        }
    }
}

/** Lightweight snapshot the widgets render (written by the app; no player is created to draw it). */
internal data class WidgetSnapshot(
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val playing: Boolean,
    val youtube: Boolean,
    val hasNext: Boolean,
    val upNext: List<WidgetQueueItem> = emptyList(),
) {
    companion object {
        private const val PREFS = "widget_snapshot"

        fun read(context: Context): WidgetSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetSnapshot(
                p.getString("t", null), p.getString("a", null), p.getString("art", null),
                p.getBoolean("p", false), p.getBoolean("yt", false), p.getBoolean("n", false),
                decode(p.getString("q", null)),
            )
        }

        fun write(context: Context, s: WidgetSnapshot) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("t", s.title).putString("a", s.artist).putString("art", s.artworkUrl)
                .putBoolean("p", s.playing).putBoolean("yt", s.youtube).putBoolean("n", s.hasNext)
                .putString("q", encode(s.upNext))
                .apply()
        }

        private fun encode(items: List<WidgetQueueItem>): String {
            val arr = JSONArray()
            items.forEach { i ->
                arr.put(JSONObject().put("i", i.index).put("u", i.uid).put("t", i.title).put("a", i.artist).put("y", i.youtube))
            }
            return arr.toString()
        }

        private fun decode(raw: String?): List<WidgetQueueItem> {
            if (raw.isNullOrEmpty()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { n ->
                    val o = arr.getJSONObject(n)
                    WidgetQueueItem(o.getInt("i"), o.getLong("u"), o.optString("t"), o.optString("a"), o.optBoolean("y"))
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** Artwork shared by the now-playing widgets, keyed by its url. */
internal data class WidgetArt(val url: String?, val bitmap: Bitmap?)

/** What the lyrics widget shows. Only ever text: the widget never drives playback. */
internal data class WidgetLyric(
    val state: State,
    val title: String? = null,
    val artist: String? = null,
    /** The line being sung (null before the first line / during a break → shown as a note). */
    val current: String? = null,
    val next: String? = null,
) {
    enum class State { NOTHING_PLAYING, LOADING, NO_LYRICS, UNSYNCED, SYNCED }

    companion object {
        val Idle = WidgetLyric(State.NOTHING_PLAYING)
    }
}

/**
 * In-process source of truth for running widget compositions. Glance does not re-run `provideGlance`
 * while a session is alive, so the compositions collect these flows and re-render on change.
 */
internal object WidgetBus {
    private val _snapshot = MutableStateFlow<WidgetSnapshot?>(null)
    val snapshot: StateFlow<WidgetSnapshot?> = _snapshot.asStateFlow()
    private val _art = MutableStateFlow(WidgetArt(null, null))
    val art: StateFlow<WidgetArt> = _art.asStateFlow()
    private val _lyric = MutableStateFlow<WidgetLyric?>(null)
    /** Current/next synced lyric line for the lyrics widget; null until the app has computed one. */
    val lyric: StateFlow<WidgetLyric?> = _lyric.asStateFlow()

    fun publishLyric(l: WidgetLyric) {
        _lyric.value = l
    }

    /** Current snapshot, read from disk the first time (e.g. after process death). */
    fun current(context: Context): WidgetSnapshot = _snapshot.value ?: WidgetSnapshot.read(context).also { _snapshot.value = it }

    fun publish(context: Context, s: WidgetSnapshot) {
        WidgetSnapshot.write(context, s)
        _snapshot.value = s
    }

    /** Loads (or reuses) the artwork for [url]; call off the main thread. */
    suspend fun ensureArt(context: Context, url: String?) {
        val cur = _art.value
        if (cur.url == url && (url == null || cur.bitmap != null)) return
        val bmp = url?.let { loadBitmap(context, it) }
        _art.value = WidgetArt(url, bmp)
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? = runCatching {
        val r = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build())
        (r as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}
