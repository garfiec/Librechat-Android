package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Compose-observable LRU cache. Reads via [get] participate in Compose snapshots
 * through the backing [SnapshotStateMap], so a [put] from a background thread
 * triggers recomposition of any composable that read the same key.
 *
 * Eviction is least-recently-used: both [get] (on a hit) and [put] move the key
 * to the most-recently-used end of [accessOrder], so the entry dropped when the
 * cache is over capacity is the one untouched longest. A read must refresh
 * recency, not just a write — a settled entry is typically written once and then
 * only read back on re-display, so a put-only policy would degrade to FIFO for
 * exactly the hot entries we want to keep.
 *
 * [SnapshotStateMap] already routes writes through Compose's snapshot system —
 * an explicit `Snapshot.withMutableSnapshot { ... }` wrap would create one
 * mutable snapshot per writing thread and cause `SnapshotApplyConflictException`
 * under concurrent puts. The lock guards [accessOrder] (a plain `ArrayDeque`,
 * not thread-safe) and serializes the read-modify-write pair on [map]. [get]'s
 * recency bump touches only [accessOrder], never [map], so it performs no
 * snapshot write during composition and cannot trigger a recomposition by reading.
 */
open class LruSnapshotCache<V>(private val maxEntries: Int) {

    private val map: SnapshotStateMap<String, V> = mutableStateMapOf()

    // Ordered least- (front) to most-recently-used (back).
    private val accessOrder = ArrayDeque<String>()
    private val lock = SynchronizedObject()

    operator fun get(key: String): V? = synchronized(lock) {
        val value = map[key]
        if (value != null) touch(key)
        value
    }

    fun put(key: String, value: V) {
        synchronized(lock) {
            touch(key)
            map[key] = value
            while (accessOrder.size > maxEntries) {
                map.remove(accessOrder.removeFirst())
            }
        }
    }

    /**
     * Move [key] to the most-recently-used end of [accessOrder]. O(n), n <=
     * maxEntries. Must be called while holding [lock]. The [remove] is a no-op
     * when the key is absent (a fresh [put]).
     */
    private fun touch(key: String) {
        accessOrder.remove(key)
        accessOrder.addLast(key)
    }
}
