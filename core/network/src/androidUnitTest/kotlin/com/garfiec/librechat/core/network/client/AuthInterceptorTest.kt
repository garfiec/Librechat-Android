package com.garfiec.librechat.core.network.client

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AuthInterceptorTest {

    private class FakeTokenManager(
        private var accessToken: String? = "initial-token",
        private var refreshSucceeds: Boolean = true,
        private var refreshedToken: String = "refreshed-token",
    ) : TokenManager {
        var refreshCallCount = 0
        var sessionExpiredCount = 0
        private val _sessionExpiredFlow = MutableSharedFlow<Unit>()
        override val sessionExpiredFlow: SharedFlow<Unit> = _sessionExpiredFlow
        override val isAuthenticated: Boolean get() = accessToken != null

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun setTokens(accessToken: String, refreshToken: String) {
            this.accessToken = accessToken
        }

        override suspend fun refreshAccessToken(): Boolean {
            refreshCallCount++
            return if (refreshSucceeds) {
                accessToken = refreshedToken
                true
            } else {
                false
            }
        }

        override suspend fun clearTokens() {
            accessToken = null
        }

        override suspend fun onAccountResolved(accountId: String) = Unit

        override suspend fun onAccountCleared() {
            accessToken = null
        }

        override fun emitSessionExpired() {
            sessionExpiredCount++
        }
    }

    private class FakeServerUrlProvider(private val baseUrl: String) : ServerUrlProvider {
        override fun getBaseUrl(): String = baseUrl
    }

    private fun createClient(
        tokenManager: FakeTokenManager,
        engine: MockEngine,
        serverUrlProvider: ServerUrlProvider? = null,
    ): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json() }
        install(AuthInterceptorPlugin) {
            this.tokenManager = tokenManager
            this.serverUrlProvider = serverUrlProvider
        }
    }

    @Test
    fun `attaches Bearer token to requests`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/api/conversations")
        assertThat(capturedAuth).isEqualTo("Bearer my-token")
    }

    @Test
    fun `skips auth for login endpoint`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/auth/login")
        assertThat(capturedAuth).isNull()
    }

    @Test
    fun `skips auth for register endpoint`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/auth/register")
        assertThat(capturedAuth).isNull()
    }

    @Test
    fun `refreshes token on 401 and retries`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshSucceeds = true,
            refreshedToken = "new-token",
        )
        var requestCount = 0
        val capturedTokens = mutableListOf<String?>()

        val engine = MockEngine { request ->
            requestCount++
            capturedTokens.add(request.headers[HttpHeaders.Authorization])
            if (requestCount == 1) {
                respond("Unauthorized", HttpStatusCode.Unauthorized)
            } else {
                respond("OK", HttpStatusCode.OK)
            }
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(requestCount).isEqualTo(2)
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
        assertThat(capturedTokens[0]).isEqualTo("Bearer expired-token")
        assertThat(capturedTokens[1]).isEqualTo("Bearer new-token")
    }

    @Test
    fun `emits session expired when refresh fails`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshSucceeds = false,
        )

        val engine = MockEngine {
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(1)
    }

    @Test
    fun `returns 401 when refreshed token is also expired`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshSucceeds = true,
            refreshedToken = "still-expired",
        )

        val engine = MockEngine {
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        // Refresh was attempted once, then the retry 401 is returned as-is
        // (execute() inside interceptor doesn't re-enter the same interceptor)
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
    }

    @Test
    fun `does not refresh on non-401 errors`() = runTest {
        val tokenManager = FakeTokenManager()

        val engine = MockEngine {
            respond("Server Error", HttpStatusCode.InternalServerError)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
        assertThat(tokenManager.refreshCallCount).isEqualTo(0)
    }

    @Test
    fun `does not attach token to a non-base host when host-scoped`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = "not-null"

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(
            tokenManager,
            engine,
            serverUrlProvider = FakeServerUrlProvider("https://chat.example.com"),
        )

        // Presigned CDN URL on a different host — token must NOT leak.
        client.get("https://d111.cloudfront.net/file/abc?sig=xyz")
        assertThat(capturedAuth).isNull()
    }

    @Test
    fun `attaches token to the base host when host-scoped`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(
            tokenManager,
            engine,
            serverUrlProvider = FakeServerUrlProvider("https://chat.example.com"),
        )

        client.get("https://chat.example.com/api/files/download/u1/f1")
        assertThat(capturedAuth).isEqualTo("Bearer my-token")
    }

    @Test
    fun `does not attach token to a subdomain of the base host`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = "not-null"

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(
            tokenManager,
            engine,
            serverUrlProvider = FakeServerUrlProvider("https://chat.example.com"),
        )

        // Insidious case: a CDN on a SUBDOMAIN of the base host must NOT get the
        // token — host-matching is exact-equals, not suffix.
        client.get("https://cdn.example.com/file/abc?sig=xyz")
        assertThat(capturedAuth).isNull()
    }

    @Test
    fun `does not refresh-and-retry token to a foreign host on 401`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token", refreshSucceeds = true)
        var requestCount = 0

        val engine = MockEngine {
            requestCount++
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = createClient(
            tokenManager,
            engine,
            serverUrlProvider = FakeServerUrlProvider("https://chat.example.com"),
        )

        val response = client.get("https://cdn.example.com/file/abc?sig=xyz")
        // Foreign-host 401 passes straight through — no refresh, no retry.
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(tokenManager.refreshCallCount).isEqualTo(0)
        assertThat(requestCount).isEqualTo(1)
    }

    @Test
    fun `empty base url falls back to attach-always and exercises 401 retry`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshSucceeds = true,
            refreshedToken = "new-token",
        )
        var requestCount = 0
        val capturedTokens = mutableListOf<String?>()

        val engine = MockEngine { request ->
            requestCount++
            capturedTokens.add(request.headers[HttpHeaders.Authorization])
            if (requestCount == 1) respond("Unauthorized", HttpStatusCode.Unauthorized)
            else respond("OK", HttpStatusCode.OK)
        }
        // Cold-start: provider returns empty base → attach-always fallback, and the
        // 401-retry path stays active (host-scope helper returns true on empty base).
        val client = createClient(
            tokenManager,
            engine,
            serverUrlProvider = FakeServerUrlProvider(""),
        )

        val response = client.get("https://chat.example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
        assertThat(capturedTokens[0]).isEqualTo("Bearer expired-token")
        assertThat(capturedTokens[1]).isEqualTo("Bearer new-token")
    }

    @Test
    fun `attaches token when host-scoping disabled (no provider)`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token")
        var capturedAuth: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        // No serverUrlProvider → legacy behavior, token attached regardless of host.
        val client = createClient(tokenManager, engine)

        client.get("https://anyhost.example.org/api/data")
        assertThat(capturedAuth).isEqualTo("Bearer my-token")
    }

    @Test
    fun `sends request without token when no token available`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = null)
        var capturedAuth: String? = "not-null"

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/api/data")
        assertThat(capturedAuth).isNull()
    }
}
