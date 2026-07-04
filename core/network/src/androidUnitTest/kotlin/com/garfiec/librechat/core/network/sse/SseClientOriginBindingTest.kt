package com.garfiec.librechat.core.network.sse

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.model.StreamEvent
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The reconnect loop must stay bound to the account the stream started under: every transport
 * (re)connect captures a fresh identity snapshot, so a retry attempted after an account switch would
 * otherwise resume the outgoing account's stream path under the new account's URL and bearer — a
 * cross-account resume. The guard aborts the retry loop instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseClientOriginBindingTest {

    private val accountA = AccountId("srv-1:user-a")
    private val accountB = AccountId("srv-1:user-b")

    // MockEngine defaults its dispatcher to Dispatchers.IO, which runs the handler on a real thread
    // pool outside the test scheduler. While that real thread works, runTest sees an idle queue and
    // fast-forwards virtual time — firing SseLineParser's 120s stall watchdog before the request
    // ever completes, which aborts the attempt with a spurious SseStreamException. Pinning the
    // engine to Dispatchers.Unconfined keeps the whole request inline on the test scheduler, so
    // virtual time can never advance past a request that is still in flight.
    private fun failingTransportClient(onRequest: () -> Unit): HttpClient {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = Dispatchers.Unconfined
                addHandler {
                    onRequest()
                    respond("boom", HttpStatusCode.InternalServerError)
                }
            },
        )
        return HttpClient(engine) {
            defaultRequest { url("https://a.example.com") }
        }
    }

    @Test
    fun `reconnect aborts when the active account changed since the stream started`() = runTest(UnconfinedTestDispatcher()) {
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        var requestCount = 0
        val client = SseClient(
            json = Json { ignoreUnknownKeys = true },
            transport = SseHttpTransport(failingTransportClient { requestCount++ }),
            activeAccountProvider = provider,
        )

        val events = mutableListOf<StreamEvent>()
        client.connect("api/agents/chat/stream/abc").collect { event ->
            events += event
            // Simulate a switch landing between the failed attempt and the retry.
            provider.set(accountB)
        }

        // One real attempt, one Retrying signal, then the guard ends the flow — no reconnect as B.
        assertThat(requestCount).isEqualTo(1)
        assertThat(events.filterIsInstance<StreamEvent.Retrying>()).hasSize(1)
    }

    @Test
    fun `reconnect proceeds while the account is unchanged`() = runTest(UnconfinedTestDispatcher()) {
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        var requestCount = 0
        val client = SseClient(
            json = Json { ignoreUnknownKeys = true },
            transport = SseHttpTransport(failingTransportClient { requestCount++ }),
            activeAccountProvider = provider,
        )

        client.connect("api/agents/chat/stream/abc").collect { }

        // All retries exhausted under the same identity (initial attempt + retries).
        assertThat(requestCount).isGreaterThan(1)
    }
}
