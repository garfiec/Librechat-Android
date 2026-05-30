package com.garfiec.librechat.feature.chat.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class LruSnapshotCacheTest {

    @Test
    fun `get refreshes recency so a re-read key survives eviction`() {
        val cache = LruSnapshotCache<String>(maxEntries = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        // Reading 'a' marks it most-recently-used; 'b' becomes the LRU entry.
        assertThat(cache["a"]).isEqualTo("1")
        cache.put("d", "4") // over cap -> evicts the LRU entry: 'b'

        assertThat(cache["b"]).isNull()
        assertThat(cache["a"]).isEqualTo("1")
        assertThat(cache["c"]).isEqualTo("3")
        assertThat(cache["d"]).isEqualTo("4")
    }

    @Test
    fun `get on a miss does not perturb eviction order`() {
        val cache = LruSnapshotCache<String>(maxEntries = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        // A miss must not refresh anything; 'a' stays the LRU entry.
        assertThat(cache["absent"]).isNull()
        cache.put("d", "4") // over cap -> evicts the LRU entry: 'a'

        assertThat(cache["a"]).isNull()
        assertThat(cache["b"]).isEqualTo("2")
        assertThat(cache["c"]).isEqualTo("3")
        assertThat(cache["d"]).isEqualTo("4")
    }

    @Test
    fun `concurrent gets and puts do not desync map and accessOrder`() = runBlocking {
        // get() now does a read-modify-write under the lock (it bumps recency on
        // a hit). Hammer it against concurrent puts and evictions: the bound must
        // hold and no exception may escape, which only stays true if map and
        // accessOrder are mutated atomically together.
        val cache = LruSnapshotCache<String>(maxEntries = 50)
        withContext(Dispatchers.Default) {
            (0 until 1000).flatMap { i ->
                listOf(
                    async { cache.put("k-$i", "v-$i") },
                    async { cache["k-${i / 2}"] },
                )
            }.awaitAll()
        }
        val present = (0 until 1000).count { cache["k-$it"] != null }
        assertThat(present).isEqualTo(50)
    }
}
