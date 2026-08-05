package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.network.PrefetchMarker
import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.garfiec.librechat.core.network.di.NetworkGraphTestFakes
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * The idle signal's behaviour, asserted through the real client factory so the production plugin
 * order is what is under test.
 *
 * The exemption test is the load-bearing one. If [PrefetchMarker] does not reach the interceptor's
 * coroutine context, the prefetcher counts its own requests as user activity and blocks against its
 * own idle gate — it then does nothing, forever, raising no error and failing no other test. That
 * failure is invisible to every other kind of check, which is why it is asserted directly here
 * rather than inferred from the feature working.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestActivityPluginTest {

    private companion object {
        const val URL = "https://chat.example.com/api/messages/conv-1"
    }

    /**
     * Handlers run on `Dispatchers.Unconfined` for the same reason as the SSE tests: a handler parked
     * on a real thread lets `runTest` fast-forward virtual time past the request timeout.
     */
    private fun engineFactory(handler: MockRequestHandler) =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(
                    MockEngineConfig().apply(block).apply {
                        dispatcher = Dispatchers.Unconfined
                        addHandler(handler)
                    },
                )
        }

    @Test
    fun `an ordinary request counts as user activity while it is in flight`() = runTest {
        val tracker = RequestActivityTracker()
        var inFlightDuringCall = -1
        val client = NetworkGraphTestFakes.mainClient(
            engineFactory { inFlightDuringCall = tracker.userInFlight.value; respond("{}") },
            tracker,
        )

        client.get(URL)

        assertThat(inFlightDuringCall).isEqualTo(1)
        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }

    /**
     * The whole prefetch design rests on this. A marked coroutine's requests must be invisible to the
     * tracker, or background work can never run.
     */
    @Test
    fun `a prefetch-marked request is not counted`() = runTest {
        val tracker = RequestActivityTracker()
        var inFlightDuringCall = -1
        val client = NetworkGraphTestFakes.mainClient(
            engineFactory { inFlightDuringCall = tracker.userInFlight.value; respond("{}") },
            tracker,
        )

        withContext(PrefetchMarker) {
            client.get(URL)
        }

        assertThat(inFlightDuringCall).isEqualTo(0)
        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }

    /**
     * The plugin is installed ahead of `HttpRequestRetry`, making it the outermost `HttpSend`
     * interceptor, so a call the user perceives as one request stays counted across its retries.
     * Installed the other way round the count would drop to zero between attempts and background
     * work would start in the gap — which is exactly when the network is already struggling.
     */
    @Test
    fun `a retried request stays counted for the whole call`() = runTest {
        val tracker = RequestActivityTracker()
        val observed = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.userInFlight.toList(observed)
        }

        var attempts = 0
        val client = NetworkGraphTestFakes.mainClient(
            engineFactory {
                attempts++
                if (attempts < 3) respondError(HttpStatusCode.InternalServerError) else respond("{}")
            },
            tracker,
        )

        client.get(URL)

        assertThat(attempts).isEqualTo(3)
        // Not [0, 1, 0, 1, 0, 1, 0]: one rise, one fall, no gap the retries can be seen through.
        assertThat(observed).containsExactly(0, 1, 0).inOrder()
    }

    /** A thrown request must not strand the count, or the app looks busy forever. */
    @Test
    fun `a failing request releases the count`() = runTest {
        val tracker = RequestActivityTracker()
        val client = NetworkGraphTestFakes.mainClient(
            engineFactory { respondError(HttpStatusCode.NotFound) },
            tracker,
        )

        runCatching { client.get(URL) }

        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }
}
