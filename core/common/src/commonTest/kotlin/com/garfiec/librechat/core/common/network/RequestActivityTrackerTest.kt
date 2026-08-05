package com.garfiec.librechat.core.common.network

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestActivityTrackerTest {

    @Test
    fun `concurrent requests are counted together`() {
        val tracker = RequestActivityTracker()

        tracker.begin()
        tracker.begin()
        assertEquals(2, tracker.userInFlight.value)

        tracker.end()
        assertEquals(1, tracker.userInFlight.value)

        tracker.end()
        assertEquals(0, tracker.userInFlight.value)
    }

    /**
     * An unbalanced end is a bug either way, but the two failure modes are not equal: floored at
     * zero the app merely under-counts one request, whereas a negative count reads as "idle" during
     * live requests, which is precisely what this class exists to prevent.
     */
    @Test
    fun `an unbalanced end cannot drive the count below zero`() {
        val tracker = RequestActivityTracker()

        tracker.end()
        assertEquals(0, tracker.userInFlight.value)

        tracker.begin()
        assertEquals(1, tracker.userInFlight.value)
    }

    @Test
    fun `counted releases when its block throws`() = runTest {
        val tracker = RequestActivityTracker()

        runCatching {
            tracker.counted<Unit> {
                assertEquals(1, tracker.userInFlight.value)
                error("boom")
            }
        }

        assertEquals(0, tracker.userInFlight.value)
    }

    @Test
    fun `prefetch marker is visible to code the marked block calls`() = runTest {
        assertFalse(coroutineContext.isPrefetch())

        kotlinx.coroutines.withContext(PrefetchMarker) {
            // Read through a suspend call rather than inline, because the thing that must see the
            // marker is the Ktor interceptor several frames below the prefetcher.
            assertTrue(readIsPrefetch())
        }

        assertFalse(coroutineContext.isPrefetch())
    }

    private suspend fun readIsPrefetch(): Boolean = coroutineContext.isPrefetch()
}
