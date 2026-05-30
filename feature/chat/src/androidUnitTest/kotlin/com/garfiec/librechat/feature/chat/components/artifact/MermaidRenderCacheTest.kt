package com.garfiec.librechat.feature.chat.components.artifact

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class MermaidRenderCacheTest {

    @Test
    fun `cache plateaus at maxEntries and evicts oldest first`() {
        val cache = MermaidRenderCache(maxEntries = 100)
        repeat(110) { i -> cache.put("key-$i", "<svg id='$i'/>") }

        for (i in 0 until 10) {
            assertThat(cache["key-$i"]).isNull()
        }
        for (i in 10 until 110) {
            assertThat(cache["key-$i"]).isEqualTo("<svg id='$i'/>")
        }
    }

    @Test
    fun `re-put refreshes recency and updates value without growing the cache`() {
        val cache = MermaidRenderCache(maxEntries = 3)
        cache.put("a", "1")
        cache.put("b", "b1")
        cache.put("c", "c1")
        // Re-putting 'a' updates its value AND marks it most-recently-used, so it
        // must outlive 'b' (now the least-recently-used) on the next eviction.
        cache.put("a", "2")
        cache.put("a", "3")
        cache.put("a", "4")
        cache.put("d", "d1") // over cap -> evicts the LRU entry: 'b'

        assertThat(cache["b"]).isNull()
        assertThat(cache["a"]).isEqualTo("4")
        assertThat(cache["c"]).isEqualTo("c1")
        assertThat(cache["d"]).isEqualTo("d1")
    }

    @Test
    fun `concurrent puts do not throw and bound respected`() = runBlocking {
        val cache = MermaidRenderCache(maxEntries = 50)
        withContext(Dispatchers.Default) {
            (0 until 1000).map { i ->
                async { cache.put("k-$i", "<svg/>") }
            }.awaitAll()
        }
        val present = (0 until 1000).count { cache["k-$it"] != null }
        assertThat(present).isEqualTo(50)
    }
}
