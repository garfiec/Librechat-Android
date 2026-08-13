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
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * Which requests the barrier opts into proactive token renewal, driven through a real Ktor client so
 * the *wiring* is under test and not just the predicate.
 *
 * The auth-path exclusion has no faithful device check: reaching the Login screen normally requires a
 * hard refresh rejection, which clears the tokens first, so "no renewal happened" is vacuously true
 * there. Only here can the stored token and the request path be set independently.
 */
class SwitchBarrierRenewalTest {

    private class RecordingTokenManager(private val token: String?) : TokenManager {
        val renewedPaths = mutableListOf<String>()

        override val isAuthenticated: Boolean get() = token != null
        override suspend fun getAccessToken(): String? = token
        override suspend fun getAccessTokenFor(accountId: String): String? = token
        override suspend fun setTokens(accessToken: String, refreshToken: String) = Unit
        override suspend fun refreshAccessToken(usedAccessToken: String?): RefreshResult =
            RefreshResult.Transient

        override suspend fun refreshAccessTokenFor(
            accountId: String,
            baseUrl: String,
            usedAccessToken: String?,
        ): RefreshResult = RefreshResult.Transient

        override suspend fun ensureFreshAccessToken(
            accountId: String?,
            baseUrl: String,
            currentAccessToken: String?,
        ): String? {
            renewedPaths += baseUrl
            return currentAccessToken
        }

        override suspend fun clearTokens() = Unit
        override suspend fun getStagedAccessToken(): String? = null
        override suspend fun clearStagedTokens() = Unit
        override suspend fun selectAccount(accountId: String) = Unit
        override suspend fun removeAccount(accountId: String) = Unit
        override suspend fun onAccountResolved(accountId: String) = Unit
        override fun emitSessionExpired(expiredAccountId: String?, reason: SessionEndReason) = Unit
        override val sessionExpiredFlow: SharedFlow<SessionEndReason> = MutableSharedFlow()
    }

    private class FixedUrlProvider(private val baseUrl: String) : ServerUrlProvider {
        override fun getBaseUrl(): String = baseUrl
    }

    private fun clientWith(tokenManager: TokenManager): HttpClient {
        val gate = SwitchGate(
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("acct-a"))),
            serverUrlProvider = FixedUrlProvider("https://a.example.com"),
            tokenManager = tokenManager,
            accountReadyGate = null,
        )
        return HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) {
            install(ContentNegotiation) { json() }
            install(SwitchBarrierPlugin) { switchGate = gate }
            install(AuthInterceptorPlugin) { this.tokenManager = tokenManager }
        }
    }

    @Test
    fun `an ordinary api request opts into renewal`() = runTest {
        val tokens = RecordingTokenManager("stored-token")

        clientWith(tokens).get("/api/convos")

        // The positive control. Without it, every "no renewal" assertion below could pass simply
        // because renewal is broken everywhere.
        assertThat(tokens.renewedPaths).containsExactly("https://a.example.com")
    }

    /**
     * A sign-in POST issued while an expired token is still stored must not fire a refresh first —
     * that puts a full round trip in front of the Sign in button, on the server least likely to be
     * answering.
     */
    @Test
    fun `auth endpoints never opt into renewal even with a token stored`() = runTest {
        val paths = listOf(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/requestPasswordReset",
            "/api/auth/resetPassword",
            "/api/auth/2fa/verify-temp",
        )

        for (path in paths) {
            val tokens = RecordingTokenManager("stored-token")
            clientWith(tokens).post(path)
            assertThat(tokens.renewedPaths).isEmpty()
        }
    }

    /**
     * A presigned CDN fetch carries a host of its own at this phase, and the bearer is host-scoped away
     * from it anyway — so renewing the session token on its behalf is work no request will use.
     */
    @Test
    fun `an absolute cross-host request does not opt into renewal`() = runTest {
        val tokens = RecordingTokenManager("stored-token")

        clientWith(tokens).get("https://cdn.example.org/presigned/file.png")

        assertThat(tokens.renewedPaths).isEmpty()
    }

    @Test
    fun `a pending add-account request does not opt into renewal`() = runTest {
        val tokens = RecordingTokenManager("stored-token")
        val client = clientWith(tokens)

        withContext(PendingRequestIdentity("https://b.example.com") { "staged-b" }) {
            client.get("/api/user")
        }

        assertThat(tokens.renewedPaths).isEmpty()
    }
}
