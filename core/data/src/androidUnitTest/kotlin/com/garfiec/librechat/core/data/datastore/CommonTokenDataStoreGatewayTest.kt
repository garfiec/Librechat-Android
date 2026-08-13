package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.GatewayDetectionPlugin
import com.garfiec.librechat.core.network.client.RefreshResult
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Token refresh when an access gateway answers instead of the server (issue #287). Deliberately not
 * symmetrical with a 401: a gateway rejection says nothing about whether the LibreChat session is
 * alive, so it must never cause a logout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommonTokenDataStoreGatewayTest {

    private companion object {
        const val SERVER = "https://chat.example.com"
        const val CF_CHALLENGE = "Cloudflare-Access"
        const val ACCESS_LOGIN = "https://team.cloudflareaccess.com/cdn-cgi/access/login/chat.example.com"
    }


    private fun seededStore(refreshClient: Lazy<HttpClient>) = FakeTokenStore(
        refreshClient,
        seed = mapOf(
            "active_account_id" to "acctA",
            accessKeyOf("acctA") to "A-access",
            refreshKeyOf("acctA") to "A-refresh",
        ),
    )

    private fun refreshClientWith(engine: MockEngine): Lazy<HttpClient> = lazy {
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
            install(GatewayDetectionPlugin)
            defaultRequest {
                url(SERVER)
                contentType(ContentType.Application.Json)
            }
        }
    }

    /**
     * The gateway's 302 challenge, and nothing else: refresh is a POST, and Ktor only redirects Get
     * and Head, so the sign-in page on the other end is never fetched on this client. Detection
     * reads the challenge off the 302 itself, which is why it works here at all.
     */
    private fun gatewayEngine(onServerRequest: () -> Unit) = MockEngine {
        onServerRequest()
        respond(
            content = "",
            status = HttpStatusCode.Found,
            headers = headersOf(
                HttpHeaders.Location to listOf(ACCESS_LOGIN),
                HttpHeaders.WWWAuthenticate to listOf(CF_CHALLENGE),
            ),
        )
    }

    /** The broken credential is the gateway's — the LibreChat session was never rejected. */
    @Test
    fun `a gateway-blocked refresh keeps the session instead of expiring it`() = runTest {
        val store = seededStore(refreshClientWith(gatewayEngine {}))

        val result = store.refreshAccessToken()

        assertThat(result).isEqualTo(RefreshResult.Transient)
        // The tokens must survive for the retry that follows the header fix.
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
        assertThat(store.store[accessKeyOf("acctA")]).isEqualTo("A-access")
    }

    /** Deterministic until the user edits the credential, and the budget is spent under the flight lock. */
    @Test
    fun `a gateway-blocked refresh is attempted once, not retried`() = runTest {
        var attempts = 0
        val store = seededStore(refreshClientWith(gatewayEngine { attempts++ }))

        store.refreshAccessToken()

        assertThat(attempts).isEqualTo(1)
    }

    /**
     * Pinned because the fold it bypasses is easy to reintroduce: a refresh that saw a 401 normally
     * settles as expired, so this arm must return on its own rather than through `settle()`.
     */
    @Test
    fun `a gateway block after a 401 does not promote the attempt into a logout`() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            if (attempts == 1) {
                respond(content = "", status = HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location to listOf(ACCESS_LOGIN),
                        HttpHeaders.WWWAuthenticate to listOf(CF_CHALLENGE),
                    ),
                )
            }
        }
        val store = seededStore(refreshClientWith(engine))

        val result = store.refreshAccessToken()

        assertThat(result).isEqualTo(RefreshResult.Transient)
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
    }
}
