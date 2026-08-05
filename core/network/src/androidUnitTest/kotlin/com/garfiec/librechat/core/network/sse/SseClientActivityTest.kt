package com.garfiec.librechat.core.network.sse

import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * A live stream counts as user activity for its whole duration, so background work stays off the
 * network while a reply is being written.
 *
 * Reported by [SseClient] rather than by a Ktor plugin because iOS streams over a custom transport
 * no plugin can observe — so these assertions are what make the two platforms agree about when the
 * app is idle. The release cases matter most: a stranded count would leave the app looking
 * permanently busy, and background work would then never run again for the life of the process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseClientActivityTest {

    // Unconfined for the same reason as SseClientOriginBindingTest: a handler on a real thread lets
    // runTest fast-forward virtual time into the parser's stall watchdog.
    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = Dispatchers.Unconfined
                addHandler { respond(body, status) }
            },
        )
        return HttpClient(engine) {
            defaultRequest { url("https://a.example.com") }
        }
    }

    private fun sseClient(http: HttpClient, tracker: RequestActivityTracker) = SseClient(
        json = Json { ignoreUnknownKeys = true },
        transport = SseHttpTransport(http),
        requestActivityTracker = tracker,
    )

    /** Building a Flow opens no stream, so it must not make the app look busy. */
    @Test
    fun `an uncollected stream is not counted`() = runTest(UnconfinedTestDispatcher()) {
        val tracker = RequestActivityTracker()

        sseClient(client("data: {\"created\":true}\n\n"), tracker).connect("api/agents/chat/stream/abc")

        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }

    @Test
    fun `a stream is counted while it is being collected`() = runTest(UnconfinedTestDispatcher()) {
        val tracker = RequestActivityTracker()
        var inFlightDuringStream = -1

        sseClient(client("data: {\"created\":true}\n\n"), tracker)
            .connect("api/agents/chat/stream/abc")
            .collect { inFlightDuringStream = tracker.userInFlight.value }

        assertThat(inFlightDuringStream).isEqualTo(1)
        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }

    /**
     * The retry ladder is exhausted and the flow ends by emitting an error event rather than
     * throwing, so a `finally` on the collector would never see a failure — the count has to be
     * released on ordinary completion too.
     */
    @Test
    fun `a failing stream releases the count`() = runTest(UnconfinedTestDispatcher()) {
        val tracker = RequestActivityTracker()

        sseClient(client("boom", HttpStatusCode.InternalServerError), tracker)
            .connect("api/agents/chat/stream/abc")
            .collect { }

        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }

    /** The common case at the end of a chat: the user leaves and the collector is torn down. */
    @Test
    fun `an abandoned stream releases the count`() = runTest(UnconfinedTestDispatcher()) {
        val tracker = RequestActivityTracker()

        // `first()` cancels the flow as soon as one event arrives, which is a collector going away
        // mid-stream — the path that strands a count when release is tied to completion alone.
        sseClient(client("data: {\"created\":true}\n\n"), tracker)
            .connect("api/agents/chat/stream/abc")
            .first()

        assertThat(tracker.userInFlight.value).isEqualTo(0)
    }
}
