package com.garfiec.librechat.core.network.client

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Locks the browser-UA invariant: the stock LibreChat server soft-bans on the first non-browser
 * User-Agent hitting a `ua-parser-js` route. [applyBrowserDefaults] is the single site both the main
 * and streaming clients route through, so a regression that drops the header — or mangles the
 * constant the iOS SSE transport also references — is caught here.
 */
class UserAgentGuardTest {

    private class FakeServerUrlProvider(private val baseUrl: String) : ServerUrlProvider {
        override fun getBaseUrl(): String = baseUrl
    }

    @Test
    fun `applyBrowserDefaults sends the browser UA on every method`() = runTest {
        val captured = mutableMapOf<String, String?>()
        val engine = MockEngine { request ->
            captured[request.method.value] = request.headers[HttpHeaders.UserAgent]
            respond("OK", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            defaultRequest { applyBrowserDefaults(FakeServerUrlProvider("https://chat.example.com")) }
        }

        client.get("/api/agents")
        client.post("/api/agents/chat/x")

        assertThat(captured["GET"]).isEqualTo(LibreChatHttpClient.BROWSER_USER_AGENT)
        assertThat(captured["POST"]).isEqualTo(LibreChatHttpClient.BROWSER_USER_AGENT)
    }

    @Test
    fun `the browser UA constant looks like a real browser`() {
        // ua-parser-js keys off these tokens; a constant edit that drops them re-opens the ban.
        assertThat(LibreChatHttpClient.BROWSER_USER_AGENT).contains("Mozilla")
        assertThat(LibreChatHttpClient.BROWSER_USER_AGENT).contains("Chrome")
    }
}
