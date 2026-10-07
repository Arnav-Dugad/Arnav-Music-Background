package com.arnav.music.domain.sync

/**
 * Offline-first sync primitive. Every syncable record carries an update timestamp, a tombstone
 * flag and a dirty flag. Merge rules are last-write-wins per record, with tombstones winning
 * ties so deletes are never resurrected by stale devices.
 */
data class SyncRecord<T>(
    val id: String,
    val value: T?,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)

data class MergePlan<T>(
    /** Remote changes to write into the local database (clean). */
    val applyLocally: List<SyncRecord<T>>,
    /** Local dirty records to upload. */
    val pushRemote: List<SyncRecord<T>>,
    /** Local dirty records that lost to a newer remote; their dirty flag must be cleared. */
    val discardedLocal: List<String>,
)

object SyncMerge {
    fun <T> plan(local: List<SyncRecord<T>>, remote: List<SyncRecord<T>>): MergePlan<T> {
        val localById = local.associateBy { it.id }
        val remoteById = dedupe(remote)
        val apply = ArrayList<SyncRecord<T>>()
        val push = ArrayList<SyncRecord<T>>()
        val discarded = ArrayList<String>()

        for ((id, r) in remoteById) {
            val l = localById[id]
            when {
                l == null -> apply += r.copy(dirty = false)
                l.dirty -> when {
                    l.updatedAt > r.updatedAt -> push += l
                    l.updatedAt == r.updatedAt && l.deleted && !r.deleted -> push += l
                    l.updatedAt == r.updatedAt && l.value == r.value && l.deleted == r.deleted -> discarded += id
                    else -> { apply += r.copy(dirty = false); discarded += id }
                }
                r.updatedAt > l.updatedAt -> apply += r.copy(dirty = false)
                r.updatedAt == l.updatedAt && r.deleted && !l.deleted -> apply += r.copy(dirty = false)
            }
        }
        for (l in local) if (l.dirty && l.id !in remoteById) push += l
        return MergePlan(apply, push, discarded)
    }

    /** Collapses duplicate remote documents (e.g. replays of an offline queue) to the newest. */
    fun <T> dedupe(records: List<SyncRecord<T>>): Map<String, SyncRecord<T>> =
        records.groupBy { it.id }.mapValues { (_, v) ->
            v.maxWith(compareBy<SyncRecord<T>> { it.updatedAt }.thenBy { it.deleted })
        }
}

/** Exponential backoff with full jitter; jitter source injectable for tests. */
object Backoff {
    fun delayMs(attempt: Int, baseMs: Long = 1_000, maxMs: Long = 5 * 60_000, jitter: () -> Double = { Math.random() }): Long {
        val exp = (baseMs * (1L shl attempt.coerceIn(0, 20))).coerceAtMost(maxMs)
        return (exp * (0.5 + 0.5 * jitter())).toLong()
    }
}
