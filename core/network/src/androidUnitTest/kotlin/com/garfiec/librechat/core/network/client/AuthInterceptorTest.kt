package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
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
import kotlinx.coroutines.withContext
import org.junit.Test

class AuthInterceptorTest {

    private class FakeTokenManager(
        private var accessToken: String? = "initial-token",
        private var refreshOutcome: RefreshResult = RefreshResult.Refreshed,
        private var refreshedToken: String = "refreshed-token",
    ) : TokenManager {
        var refreshCallCount = 0
        var sessionExpiredCount = 0
        var lastExpiredAccountId: String? = null
        private val _sessionExpiredFlow = MutableSharedFlow<Unit>()
        override val sessionExpiredFlow: SharedFlow<Unit> = _sessionExpiredFlow
        override val isAuthenticated: Boolean get() = accessToken != null

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun setTokens(accessToken: String, refreshToken: String) {
            this.accessToken = accessToken
        }

        override suspend fun refreshAccessToken(): RefreshResult {
            refreshCallCount++
            if (refreshOutcome == RefreshResult.Refreshed) accessToken = refreshedToken
            return refreshOutcome
        }

        override suspend fun clearTokens() {
            accessToken = null
        }

        override suspend fun getAccessTokenFor(accountId: String): String? = accessToken

        override suspend fun getStagedAccessToken(): String? = null

        override suspend fun clearStagedTokens() = Unit

        override suspend fun selectAccount(accountId: String) = Unit

        override suspend fun removeAccount(accountId: String) {
            accessToken = null
        }

        override suspend fun refreshAccessTokenFor(accountId: String, baseUrl: String): RefreshResult =
            refreshAccessToken()

        override suspend fun onAccountResolved(accountId: String) = Unit

        override suspend fun onAccountCleared() {
            accessToken = null
        }

        override fun emitSessionExpired(expiredAccountId: String?) {
            sessionExpiredCount++
            lastExpiredAccountId = expiredAccountId
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
    fun `401 from verify-temp passes through without refresh or session expiry`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = null, refreshOutcome = RefreshResult.HardExpired)
        var requestCount = 0

        val engine = MockEngine {
            requestCount++
            respond("""{"message":"Invalid 2FA code."}""", HttpStatusCode.Unauthorized)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/auth/2fa/verify-temp")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(response.bodyAsText()).contains("Invalid 2FA code.")
        assertThat(requestCount).isEqualTo(1)
        assertThat(tokenManager.refreshCallCount).isEqualTo(0)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(0)
    }

    @Test
    fun `401 from the authenticated verify endpoint still refreshes`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "expired-token", refreshOutcome = RefreshResult.HardExpired)

        val engine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/api/auth/2fa/verify")
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(1)
    }

    @Test
    fun `a base path containing a skip token does not exempt the whole deployment`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "expired-token", refreshOutcome = RefreshResult.HardExpired)
        var capturedAuth: String? = null

        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/apps/auth/login-portal/api/conversations")
        assertThat(capturedAuth).isEqualTo("Bearer expired-token")
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(1)
    }

    @Test
    fun `a skip endpoint under a mounted base path is still exempt`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = null, refreshOutcome = RefreshResult.HardExpired)

        val engine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val client = createClient(tokenManager, engine)

        client.get("https://example.com/librechat/api/auth/2fa/verify-temp")
        assertThat(tokenManager.refreshCallCount).isEqualTo(0)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(0)
    }

    @Test
    fun `a query string containing a skip token does not exempt the request`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshOutcome = RefreshResult.Refreshed,
            refreshedToken = "new-token",
        )
        var requestCount = 0

        val engine = MockEngine {
            requestCount++
            if (requestCount == 1) respond("Unauthorized", HttpStatusCode.Unauthorized)
            else respond("OK", HttpStatusCode.OK)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/search?q=auth/login")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
    }

    @Test
    fun `refreshes token on 401 and retries`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshOutcome = RefreshResult.Refreshed,
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
            refreshOutcome = RefreshResult.HardExpired,
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
    fun `transient refresh failure does not emit session expired`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshOutcome = RefreshResult.Transient,
        )

        val engine = MockEngine {
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(tokenManager.refreshCallCount).isEqualTo(1)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(0)
    }

    @Test
    fun `returns 401 when refreshed token is also expired`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshOutcome = RefreshResult.Refreshed,
            refreshedToken = "still-expired",
        )

        val engine = MockEngine {
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val client = createClient(tokenManager, engine)

        val response = client.get("https://example.com/api/data")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
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

        client.get("https://cdn.example.com/file/abc?sig=xyz")
        assertThat(capturedAuth).isNull()
    }

    @Test
    fun `does not refresh-and-retry token to a foreign host on 401`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "my-token", refreshOutcome = RefreshResult.Refreshed)
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
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(tokenManager.refreshCallCount).isEqualTo(0)
        assertThat(requestCount).isEqualTo(1)
    }

    @Test
    fun `empty base url falls back to attach-always and exercises 401 retry`() = runTest {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-token",
            refreshOutcome = RefreshResult.Refreshed,
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
        val client = createClient(tokenManager, engine)

        client.get("https://anyhost.example.org/api/data")
        assertThat(capturedAuth).isEqualTo("Bearer my-token")
    }

    @Test
    fun `pending-identity request carries the staged bearer and its 401 passes through untouched`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "live-a-token", refreshOutcome = RefreshResult.Refreshed)
        var requestCount = 0
        var capturedAuth: String? = null
        var capturedHost: String? = null
        val engine = MockEngine { request ->
            requestCount++
            capturedAuth = request.headers[HttpHeaders.Authorization]
            capturedHost = request.url.host
            respond("Unauthorized", HttpStatusCode.Unauthorized)
        }
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("acct-a"))),
            serverUrlProvider = FakeServerUrlProvider("https://a.example.com"),
            tokenManager = tokenManager,
            accountReadyGate = null,
        )
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
            install(SwitchBarrierPlugin) { switchGate = gate }
            install(AuthInterceptorPlugin) { this.tokenManager = tokenManager }
        }

        val response = withContext(PendingRequestIdentity("https://b.example.com") { "staged-b" }) {
            client.get("/api/user")
        }

        assertThat(capturedHost).isEqualTo("b.example.com")
        assertThat(capturedAuth).isEqualTo("Bearer staged-b")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(requestCount).isEqualTo(1)
        assertThat(tokenManager.refreshCallCount).isEqualTo(0)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(0)
    }

    @Test
    fun `refresh failure on a snapshot request scopes the expiry signal to the snapshot account`() = runTest {
        val tokenManager = FakeTokenManager(accessToken = "a-token", refreshOutcome = RefreshResult.HardExpired)
        val engine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("acct-a"))),
            serverUrlProvider = FakeServerUrlProvider("https://a.example.com"),
            tokenManager = tokenManager,
            accountReadyGate = null,
        )
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
            install(SwitchBarrierPlugin) { switchGate = gate }
            install(AuthInterceptorPlugin) { this.tokenManager = tokenManager }
        }

        val response = client.get("/api/user")

        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(tokenManager.sessionExpiredCount).isEqualTo(1)
        assertThat(tokenManager.lastExpiredAccountId).isEqualTo("acct-a")
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
