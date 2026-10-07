package com.arnav.music.domain.queue

import com.arnav.music.domain.model.Track
import kotlin.random.Random

/** A queue entry. [uid] keeps duplicates of the same track distinct for animation keys. */
data class QueueItem(val uid: Long, val track: Track)

/**
 * Immutable queue model. Every operation returns a new state; the playback layer mirrors it.
 */
data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffled: Boolean = false,
    /** Original order, restored when shuffle is turned off. */
    val originalOrder: List<Long> = emptyList(),
    val nextUid: Long = 1L,
) {
    val current: QueueItem? get() = items.getOrNull(currentIndex)
    val history: List<QueueItem> get() = if (currentIndex <= 0) emptyList() else items.subList(0, currentIndex)
    val upNext: List<QueueItem> get() = if (currentIndex < 0) items else items.drop(currentIndex + 1)
    val hasNext: Boolean get() = currentIndex < items.lastIndex
    val hasPrevious: Boolean get() = currentIndex > 0

    fun replace(tracks: List<Track>, startIndex: Int = 0): QueueState {
        var uid = nextUid
        val newItems = tracks.map { QueueItem(uid++, it) }
        return QueueState(newItems, if (newItems.isEmpty()) -1 else startIndex.coerceIn(0, newItems.lastIndex), false, newItems.map { it.uid }, uid)
    }

    fun playNext(tracks: List<Track>): QueueState {
        if (tracks.isEmpty()) return this
        var uid = nextUid
        val add = tracks.map { QueueItem(uid++, it) }
        val at = (currentIndex + 1).coerceIn(0, items.size)
        val newItems = items.toMutableList().apply { addAll(at, add) }
        return copy(items = newItems, currentIndex = if (currentIndex < 0) 0 else currentIndex, originalOrder = originalOrder + add.map { it.uid }, nextUid = uid)
    }

    fun append(tracks: List<Track>): QueueState {
        if (tracks.isEmpty()) return this
        var uid = nextUid
        val add = tracks.map { QueueItem(uid++, it) }
        return copy(items = items + add, currentIndex = if (currentIndex < 0) 0 else currentIndex, originalOrder = originalOrder + add.map { it.uid }, nextUid = uid)
    }

    fun move(from: Int, to: Int): QueueState {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        val newCurrent = when (currentIndex) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) currentIndex - 1 else currentIndex + 1
            else -> currentIndex
        }
        return copy(items = list, currentIndex = newCurrent)
    }

    fun removeAt(index: Int): QueueState {
        if (index !in items.indices) return this
        val list = items.toMutableList().apply { removeAt(index) }
        val newCurrent = when {
            list.isEmpty() -> -1
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> currentIndex.coerceAtMost(list.lastIndex)
            else -> currentIndex
        }
        val removedUid = items[index].uid
        return copy(items = list, currentIndex = newCurrent, originalOrder = originalOrder - removedUid)
    }

    /** Swaps the track at [index] (e.g. another upload of the same song), keeping its slot and uid. */
    fun replaceAt(index: Int, track: Track): QueueState =
        if (index !in items.indices) this else copy(items = items.toMutableList().also { it[index] = it[index].copy(track = track) })

    fun skipTo(index: Int): QueueState = if (index in items.indices) copy(currentIndex = index) else this
    fun next(repeatAll: Boolean = false): QueueState = when {
        hasNext -> copy(currentIndex = currentIndex + 1)
        repeatAll && items.isNotEmpty() -> copy(currentIndex = 0)
        else -> this
    }
    fun previous(): QueueState = if (hasPrevious) copy(currentIndex = currentIndex - 1) else this

    /** Shuffles upcoming items only; history and the current track stay put. */
    fun shuffle(seed: Long): QueueState {
        if (shuffled || items.size < 2) return copy(shuffled = true)
        val head = items.take(currentIndex + 1)
        val tail = items.drop(currentIndex + 1).shuffled(Random(seed))
        return copy(items = head + tail, shuffled = true)
    }

    fun unshuffle(): QueueState {
        if (!shuffled) return this
        val byUid = items.associateBy { it.uid }
        val restored = originalOrder.mapNotNull { byUid[it] } + items.filter { it.uid !in originalOrder.toSet() }
        val cur = current?.uid
        return copy(items = restored, currentIndex = restored.indexOfFirst { it.uid == cur }.coerceAtLeast(if (restored.isEmpty()) -1 else 0), shuffled = false)
    }
}
