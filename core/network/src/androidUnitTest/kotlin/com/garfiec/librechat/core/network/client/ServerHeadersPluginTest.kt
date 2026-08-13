package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.network.client.SessionEndReason
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Injection and containment for user-configured gateway headers (issue #287).
 *
 * The redirect test is the load-bearing one: a `HttpRequestPipeline.State` host check passes while the
 * secret still escapes, because Ktor's `HttpRedirect` re-executes at the `HttpSend` level and never
 * re-runs the request pipeline. A suite without it goes green on a build that leaks.
 */
class ServerHeadersPluginTest {

    private companion object {
        const val SERVER = "https://chat.example.com"
        const val CLIENT_ID = "CF-Access-Client-Id"
        const val SECRET = "CF-Access-Client-Secret"
        val GATEWAY_HEADERS = mapOf(CLIENT_ID to "id-value", SECRET to "secret-value")
    }

    private class FakeHeadersProvider(
        private val byServer: Map<String, Map<String, String>> = mapOf(SERVER to GATEWAY_HEADERS),
    ) : ServerHeadersProvider {
        var warmAwaited = false
        override suspend fun awaitWarm() {
            warmAwaited = true
        }

        override fun headersFor(baseUrl: String): Map<String, String> = byServer[baseUrl].orEmpty()
    }

    private class FakeServerUrlProvider(private val baseUrl: String) : ServerUrlProvider {
        override fun getBaseUrl(): String = baseUrl
    }

    /** Enough of a [TokenManager] to drive one 401 → refresh → retry cycle. */
    private class FakeTokenManager : TokenManager {
        private var accessToken: String? = "initial-token"
        private val _sessionExpiredFlow = MutableSharedFlow<SessionEndReason>()
        override val sessionExpiredFlow: SharedFlow<SessionEndReason> = _sessionExpiredFlow
        override val isAuthenticated: Boolean get() = accessToken != null
        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun setTokens(accessToken: String, refreshToken: String) {
            this.accessToken = accessToken
        }

        override suspend fun refreshAccessToken(usedAccessToken: String?): RefreshResult {
            accessToken = "refreshed-token"
            return RefreshResult.Refreshed
        }

        override suspend fun clearTokens() {
            accessToken = null
        }

        override suspend fun getAccessTokenFor(accountId: String): String? = accessToken
        override suspend fun getStagedAccessToken(): String? = null
        override suspend fun clearStagedTokens() = Unit
        override suspend fun selectAccount(accountId: String) = Unit
        override suspend fun removeAccount(accountId: String) = Unit
        override suspend fun refreshAccessTokenFor(
            accountId: String,
            baseUrl: String,
            usedAccessToken: String?,
        ): RefreshResult = refreshAccessToken(usedAccessToken)

        override suspend fun onAccountResolved(accountId: String) = Unit
        override fun emitSessionExpired(expiredAccountId: String?, reason: SessionEndReason) = Unit
    }

    private fun createClient(
        engine: MockEngine,
        headersProvider: ServerHeadersProvider,
        serverUrlProvider: ServerUrlProvider? = FakeServerUrlProvider(SERVER),
    ): HttpClient = HttpClient(engine) {
        install(ServerHeadersPlugin) {
            this.serverHeadersProvider = headersProvider
            this.serverUrlProvider = serverUrlProvider
        }
    }

    /**
     * The production install order: `AuthInterceptorPlugin` first, `ServerHeadersPlugin` second, so the
     * header plugin's `HttpSend` hook is the INNER one and therefore runs on the auth plugin's
     * post-refresh 401 retry as well as the first send.
     *
     * [createClient] installs only the header plugin, which cannot observe that ordering at all — a
     * refactor swapping the two installs would leave every test using it green while the 401-retry
     * path re-sent the gateway secret without passing through containment.
     */
    private fun createProductionOrderClient(
        engine: MockEngine,
        headersProvider: ServerHeadersProvider,
        tokenManager: TokenManager,
    ): HttpClient = HttpClient(engine) {
        install(AuthInterceptorPlugin) {
            this.tokenManager = tokenManager
            this.serverUrlProvider = FakeServerUrlProvider(SERVER)
        }
        install(ServerHeadersPlugin) {
            this.serverHeadersProvider = headersProvider
            this.serverUrlProvider = FakeServerUrlProvider(SERVER)
        }
    }

    private fun HttpRequestData.gatewayHeaders(): Map<String, String?> =
        mapOf(CLIENT_ID to headers[CLIENT_ID], SECRET to headers[SECRET])

    @Test
    fun `attaches configured headers to a request to the server`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }

        createClient(engine, FakeHeadersProvider()).get("$SERVER/api/config")

        assertThat(seen.single().gatewayHeaders()).containsExactly(CLIENT_ID, "id-value", SECRET, "secret-value")
    }

    @Test
    fun `auth login and refresh still carry the gateway headers`() = runTest {
        // AuthInterceptorPlugin's skipPaths (which include auth/login and auth/refresh) exist to keep
        // the *bearer* off those routes. Reusing that gate for gateway headers would send the login
        // request without the credential — leaving the feature unable to sign in at all.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }
        val client = createClient(engine, FakeHeadersProvider())

        client.post("$SERVER/api/auth/login") { setBody("{}") }
        client.post("$SERVER/api/auth/refresh") { setBody("{}") }

        assertThat(seen).hasSize(2)
        seen.forEach { assertThat(it.headers[CLIENT_ID]).isEqualTo("id-value") }
    }

    @Test
    fun `a cross-authority redirect drops the gateway headers`() = runTest {
        // FilesApi.downloadFromUrl fetches a SERVER-SUPPLIED absolute URL. A hostile or misconfigured
        // deployment answers with a same-host URL that 302s off-domain; Ktor's HttpRedirect copies
        // every header to the target and strips only Authorization. The secret is long-lived and never
        // rotates, so one leak is network-level access to the whole deployment.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            if (request.url.host == "chat.example.com") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://evil.example.net/steal"),
                )
            } else {
                respond("{}", HttpStatusCode.OK)
            }
        }

        createClient(engine, FakeHeadersProvider()).get("$SERVER/api/files/download/1")

        assertThat(seen).hasSize(2)
        assertThat(seen[0].headers[CLIENT_ID]).isEqualTo("id-value")
        val leaked = seen[1]
        assertThat(leaked.url.host).isEqualTo("evil.example.net")
        assertThat(leaked.gatewayHeaders()).containsExactly(CLIENT_ID, null, SECRET, null)
    }

    @Test
    fun `an absolute cross-host URL never receives the gateway headers`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("bytes", HttpStatusCode.OK)
        }

        // A presigned S3/CloudFront download URL.
        createClient(engine, FakeHeadersProvider()).get("https://cdn.amazonaws.example/presigned?sig=x")

        assertThat(seen.single().gatewayHeaders()).containsExactly(CLIENT_ID, null, SECRET, null)
    }

    @Test
    fun `a same-host scheme downgrade never receives the gateway headers`() = runTest {
        // Host-only scoping (what the bearer uses) would pass this. A non-rotating secret in cleartext
        // is a materially worse outcome than a short-lived bearer, so this gate also matches scheme.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }

        createClient(engine, FakeHeadersProvider()).get("http://chat.example.com/api/config")

        assertThat(seen.single().gatewayHeaders()).containsExactly(CLIENT_ID, null, SECRET, null)
    }

    @Test
    fun `a pinned base URL wins over the live provider`() = runTest {
        // The URL-pinned token refresh: a switch landing mid-flight flips the live provider, and
        // pairing server B's headers with server A's pinned refresh URL produces a 302 that
        // performRefresh classifies as Retryable — a session that silently never recovers.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }
        val provider = FakeHeadersProvider(
            byServer = mapOf(
                SERVER to GATEWAY_HEADERS,
                "https://other.example.com" to mapOf(CLIENT_ID to "other-id"),
            ),
        )
        // Live provider points at `other`, but this request is pinned to SERVER.
        val client = createClient(engine, provider, FakeServerUrlProvider("https://other.example.com"))

        client.post("$SERVER/api/auth/refresh") {
            attributes.put(PinnedServerBaseUrlKey, SERVER)
            setBody("{}")
        }

        assertThat(seen.single().headers[CLIENT_ID]).isEqualTo("id-value")
    }

    @Test
    fun `a snapshot's headers win over the live store`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }
        // Empty store: the only way headers can arrive is off the snapshot the switch barrier captured.
        val client = createClient(engine, FakeHeadersProvider(byServer = emptyMap()))

        client.get("$SERVER/api/config") {
            attributes.put(
                RequestIdentityKey,
                RequestIdentity(baseUrl = SERVER, accountId = "a", bearer = null, customHeaders = GATEWAY_HEADERS),
            )
        }

        assertThat(seen.single().headers[SECRET]).isEqualTo("secret-value")
    }

    @Test
    fun `merges a user Cookie into the app's own rather than sending two`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }
        val provider = FakeHeadersProvider(mapOf(SERVER to mapOf("Cookie" to "CF_Authorization=jwt")))

        createClient(engine, provider).post("$SERVER/api/auth/refresh") {
            header(HttpHeaders.Cookie, "refreshToken=rt-1")
            setBody("{}")
        }

        val cookies = seen.single().headers.getAll(HttpHeaders.Cookie).orEmpty()
        assertThat(cookies).hasSize(1)
        assertThat(cookies.single()).isEqualTo("CF_Authorization=jwt; refreshToken=rt-1")
    }

    @Test
    fun `awaits the store warm-up before building a request with no snapshot`() = runTest {
        // Missing the credential on the FIRST request is not a recoverable miss: an access gateway
        // answers it with a redirect to its own login page, not a retryable error.
        val provider = FakeHeadersProvider()
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }

        createClient(engine, provider).get("$SERVER/api/config")

        assertThat(provider.warmAwaited).isTrue()
    }

    @Test
    fun `sends nothing when the server has no headers configured`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("{}", HttpStatusCode.OK)
        }

        createClient(engine, FakeHeadersProvider(byServer = emptyMap())).get("$SERVER/api/config")

        assertThat(seen.single().gatewayHeaders()).containsExactly(CLIENT_ID, null, SECRET, null)
    }

    @Test
    fun `a redirect back into the server's authority re-attaches the gateway headers`() = runTest {
        // origin -> object store -> origin is an ordinary signed-URL shape, and an Access edge bounces
        // through its own domain by design. The `State` attach phase runs once per CALL, not per hop,
        // so a strip that is never undone leaves the final hop hitting the gateway with no credential
        // — which comes back as a 200 HTML login page and reads as a corrupt download, not an error.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            when {
                seen.size == 1 -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://objects.example.net/blob/1"),
                )
                seen.size == 2 -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "$SERVER/api/files/blob/1"),
                )
                else -> respond("bytes", HttpStatusCode.OK)
            }
        }

        createClient(engine, FakeHeadersProvider()).get("$SERVER/api/files/download/1")

        assertThat(seen).hasSize(3)
        assertThat(seen[0].headers[CLIENT_ID]).isEqualTo("id-value")
        // Still stripped while off-domain — the containment guarantee must not regress.
        assertThat(seen[1].gatewayHeaders()).containsExactly(CLIENT_ID, null, SECRET, null)
        assertThat(seen[2].url.host).isEqualTo("chat.example.com")
        assertThat(seen[2].gatewayHeaders()).containsExactly(CLIENT_ID, "id-value", SECRET, "secret-value")
    }

    @Test
    fun `the strip still runs on the auth plugin's post-refresh retry`() = runTest {
        // Pins the documented install order. With ServerHeadersPlugin installed FIRST its HttpSend hook
        // becomes the outer one and the auth plugin's 401 retry re-executes inside it — re-sending the
        // request without passing back through containment.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            when {
                request.url.host != "chat.example.com" -> respond("{}", HttpStatusCode.OK)
                seen.count { it.url.host == "chat.example.com" } == 1 ->
                    respond("", HttpStatusCode.Unauthorized)
                else -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://evil.example.net/steal"),
                )
            }
        }

        createProductionOrderClient(engine, FakeHeadersProvider(), FakeTokenManager())
            .get("$SERVER/api/files/download/1")

        val leaked = seen.last()
        assertThat(leaked.url.host).isEqualTo("evil.example.net")
        assertThat(leaked.gatewayHeaders()).containsExactly(CLIENT_ID, null, SECRET, null)
        assertThat(leaked.headers[HttpHeaders.Authorization]).isNull()
    }
}
