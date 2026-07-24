package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.VerifyTwoFactorOutcome
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.di.librechatJson
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coverage for [AuthRepositoryImpl.verifyTwoFactor] over a **real** Ktor stack (MockEngine
 * + ContentNegotiation + a response validator that mirrors production's `throw ApiException` on
 * non-2xx). This is the churn-stopping guard: it exercises the actual decode path rather than a
 * hand-constructed exception, so a wrong assumption about which exception Ktor throws (as in the
 * round-3 dead `SerializationException` branch) can no longer pass vacuously. It also pins the
 * phase-2 split — a commit fault after the code was consumed must report `SessionIncomplete`, never a
 * connectivity lie.
 */
class AuthRepositoryImplVerifyTest {

    private val userApi = mockk<UserApi>(relaxed = true)
    private val tokenManager = mockk<TokenManager>(relaxed = true)
    private val sessionCacheCleaner = mockk<SessionCacheCleaner>(relaxed = true)
    private val sessionTaskRunner = mockk<SessionTaskRunner>(relaxed = true)
    private val accountSessionEstablisher = mockk<AccountSessionEstablisher>(relaxed = true)
    private val accountRegistry = mockk<AccountRegistry>(relaxed = true)
    private val activeAccountProvider = mockk<ActiveAccountProvider>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val accountSwitcher = mockk<AccountSwitcher>(relaxed = true)
    private val switchGate = mockk<SwitchGate>(relaxed = true)

    @Before
    fun setUp() {
        // currentAccountId() reads state.value; give it a real flow so the sealed AccountState isn't
        // mocked. Warming resolves to a null account id (logged-out / not-yet-resolved), which is the
        // realistic pre-verify state.
        every { activeAccountProvider.state } returns MutableStateFlow(AccountState.Warming)
        // No add-account flow in these tests: the plain sign-in path commits via
        // accountSessionEstablisher.establish(), not accountSwitcher.completeAdd().
        every { accountSwitcher.pendingAdd } returns null
    }

    private fun repo(engine: MockEngine): AuthRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(librechatJson) }
            // Mirror LibreChatHttpClient: every non-2xx becomes an ApiException before body parsing,
            // so the classifier only ever sees a decode exception on a genuine 2xx.
            HttpResponseValidator {
                validateResponse { response ->
                    if (!response.status.isSuccess()) {
                        throw ApiException(response.status.value, response.bodyAsText().ifBlank { "error" })
                    }
                }
            }
            defaultRequest {
                url("https://chat.example.com")
                contentType(ContentType.Application.Json)
            }
        }
        return AuthRepositoryImpl(
            authApi = AuthApi(client),
            userApi = userApi,
            tokenManager = tokenManager,
            sessionCacheCleaner = sessionCacheCleaner,
            sessionTaskRunner = sessionTaskRunner,
            accountSessionEstablisher = accountSessionEstablisher,
            accountRegistry = accountRegistry,
            activeAccountProvider = activeAccountProvider,
            sessionManager = sessionManager,
            accountSwitcher = accountSwitcher,
            switchGate = switchGate,
        )
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun engineReturning(content: String, status: HttpStatusCode) = MockEngine {
        respond(content = content, status = status, headers = jsonHeaders())
    }

    @Test
    fun `an undecodable 2xx body reports SessionIncomplete, not a connectivity failure`() = runTest {
        // Truncated + wrong-typed body: a real ContentConvertException flows out of body<LoginResponse>(),
        // through safeApiCall, into the classifier — no hand-built exception, so it can't go vacuous.
        val outcome = repo(engineReturning("""{"token":"jwt-1","user":{"_id":123}""", HttpStatusCode.OK))
            .verifyTwoFactor(tempToken = "temp-abc", code = "123456")

        assertThat(outcome).isInstanceOf(VerifyTwoFactorOutcome.SessionIncomplete::class.java)
    }

    @Test
    fun `a 2xx missing the user field reports SessionIncomplete`() = runTest {
        val outcome = repo(engineReturning("""{"token":"jwt-1"}""", HttpStatusCode.OK))
            .verifyTwoFactor(tempToken = "temp-abc", code = "123456")

        assertThat(outcome).isInstanceOf(VerifyTwoFactorOutcome.SessionIncomplete::class.java)
    }

    @Test
    fun `a commit fault after the code was consumed reports SessionIncomplete, not ConnectionFailure`() = runTest {
        // Valid session body, so the code is consumed server-side, but establishing the local session
        // throws. This must NOT be misread as a wire/connectivity failure (finding #2 regression).
        coEvery { accountSessionEstablisher.establish(any()) } throws IllegalStateException("registry write failed")

        val outcome = repo(engineReturning(VALID_SESSION_BODY, HttpStatusCode.OK))
            .verifyTwoFactor(tempToken = "temp-abc", code = "123456")

        assertThat(outcome).isInstanceOf(VerifyTwoFactorOutcome.SessionIncomplete::class.java)
    }

    @Test
    fun `a commit fault rolls back the staged tokens so no orphaned session lingers`() = runTest {
        // stageAuthenticatedSession has already staged the pair (tokenManager.setTokens) by the time
        // establish throws. On the fresh sign-in path (pending == null) the rollback must fully tear
        // the staged session down via clearTokens, so a later onAccountResolved can't silently re-home
        // a session the user was told had failed. This is the finding #1 (round 5) regression guard.
        coEvery { accountSessionEstablisher.establish(any()) } throws IllegalStateException("registry write failed")

        repo(engineReturning(VALID_SESSION_BODY, HttpStatusCode.OK))
            .verifyTwoFactor(tempToken = "temp-abc", code = "123456")

        coVerify { tokenManager.clearTokens() }
        coVerify(exactly = 0) { tokenManager.clearStagedTokens() }
    }

    @Test
    fun `a 401 reports CodeRejected`() = runTest {
        val outcome = repo(engineReturning("""{"message":"Invalid 2FA code or backup code"}""", HttpStatusCode.Unauthorized))
            .verifyTwoFactor(tempToken = "temp-abc", code = "000000")

        assertThat(outcome).isInstanceOf(VerifyTwoFactorOutcome.CodeRejected::class.java)
    }

    @Test
    fun `a healthy 2xx that commits cleanly reports Success`() = runTest {
        val outcome = repo(engineReturning(VALID_SESSION_BODY, HttpStatusCode.OK))
            .verifyTwoFactor(tempToken = "temp-abc", code = "123456")

        assertThat(outcome).isEqualTo(VerifyTwoFactorOutcome.Success(User(mongoId = "u1", email = "a@b.com")))
    }

    private companion object {
        const val VALID_SESSION_BODY = """{"token":"jwt-1","user":{"_id":"u1","email":"a@b.com"}}"""
    }
}
