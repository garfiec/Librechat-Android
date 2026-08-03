package com.garfiec.librechat.core.network.sse

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.AccessGatewayException
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.network.client.GatewayDetectionPlugin
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ClosedByteChannelException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The streaming path's gateway behaviour, end to end: detection plugin → transport → [SseClient].
 *
 * Asserted end to end rather than on the plugin alone because every part of it is a composition
 * property: a plugin that throws correctly still hangs the chat if it is installed on the wrong
 * client, and an [SseClient] that lacks the gateway arm still burns the full retry ladder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseClientGatewayTest {

    private companion object {
        const val SERVER = "https://chat.example.com"
        const val ACCESS_LOGIN =
            "https://team.cloudflareaccess.com/cdn-cgi/access/login/chat.example.com?meta=eyJhbGci"
        const val CF_CHALLENGE = "Cloudflare-Access"
    }

    /**
     * The server 302s with the Access challenge; the redirect target serves the sign-in page as a
     * 200. Pinned to `Dispatchers.Unconfined` for the same reason as `SseClientOriginBindingTest` —
     * a handler on a real thread lets `runTest` fast-forward virtual time into the stall watchdog.
     */
    private fun gatewayClient(onRequest: () -> Unit): HttpClient {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = Dispatchers.Unconfined
                addHandler { request ->
                    if (request.url.host == "chat.example.com") {
                        onRequest()
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(
                                HttpHeaders.Location to listOf(ACCESS_LOGIN),
                                HttpHeaders.WWWAuthenticate to listOf(CF_CHALLENGE),
                            ),
                        )
                    } else {
                        respond(
                            content = "<html>sign in</html>",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType to listOf("text/html")),
                        )
                    }
                }
            },
        )
        return HttpClient(engine) {
            install(GatewayDetectionPlugin)
            defaultRequest { url(SERVER) }
        }
    }

    @Test
    fun `a gateway-blocked stream reports the gateway instead of hanging`() = runTest(UnconfinedTestDispatcher()) {
        var requestCount = 0
        val client = SseClient(
            json = Json { ignoreUnknownKeys = true },
            transport = SseHttpTransport(gatewayClient { requestCount++ }),
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv-1:user-a"))),
        )

        val events = client.connect("api/agents/chat/stream/abc").toList()

        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertThat(errors).hasSize(1)
        // Substance, not the whole string: it must name the gateway and point somewhere actionable.
        assertThat(errors.single().message).contains("gateway")
        assertThat(errors.single().message).contains("Settings")
        // Not a network blip — offering a retry affordance here would retry into the same rejection.
        assertThat(errors.single().isNetworkError).isFalse()
    }

    /**
     * The wrapped form: the transport cancels the byte channel with the error and Ktor hands it back
     * inside a `ClosedByteChannelException`, so a type-exact catch is race-dependent.
     *
     * Injected rather than raced, because the tests above cannot produce this shape at all —
     * MockEngine on an unconfined dispatcher fails the transport before the parse loop suspends.
     */
    @Test
    fun `a gateway error wrapped by the channel close is still terminal`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = Dispatchers.Unconfined
                addHandler { throw ClosedByteChannelException(AccessGatewayException()) }
            },
        )
        val client = SseClient(
            json = Json { ignoreUnknownKeys = true },
            transport = SseHttpTransport(HttpClient(engine) { defaultRequest { url(SERVER) } }),
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv-1:user-a"))),
        )

        val events = client.connect("api/agents/chat/stream/abc").toList()

        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertThat(errors).hasSize(1)
        assertThat(errors.single().message).contains("gateway")
        assertThat(events.filterIsInstance<StreamEvent.Retrying>()).isEmpty()
    }

    /** The generic arm would retry five times with backoff and then report the wrong cause. */
    @Test
    fun `a gateway-blocked stream is not retried`() = runTest(UnconfinedTestDispatcher()) {
        var requestCount = 0
        val client = SseClient(
            json = Json { ignoreUnknownKeys = true },
            transport = SseHttpTransport(gatewayClient { requestCount++ }),
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv-1:user-a"))),
        )

        val events = client.connect("api/agents/chat/stream/abc").toList()

        assertThat(requestCount).isEqualTo(1)
        assertThat(events.filterIsInstance<StreamEvent.Retrying>()).isEmpty()
    }
}
