package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.RefreshResult
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What happens to local state when a session dies. Two properties the app depends on at cold start:
 * a refresh the server rejects must DROP the account's tokens (`isLoggedIn()` is a presence check, so
 * a retained dead token is replayed on every launch), and a dead session must report itself exactly
 * ONCE however many requests discover it (each report replays the logout navigation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommonTokenDataStoreSessionExpiryTest {

    private companion object {
        const val SERVER = "https://chat.example.com"
    }

    private class FakeStore(
        refreshClient: Lazy<HttpClient>,
        seed: Map<String, String> = emptyMap(),
    ) : CommonTokenDataStore(refreshClient) {
        val store = seed.toMutableMap()

        init {
            initializeTokenCache()
        }

        override fun readValue(key: String): String? = store[key]
        override fun writeValue(key: String, value: String) {
            store[key] = value
        }

        override fun writeValues(values: Map<String, String>) {
            store.putAll(values)
        }

        override fun removeValue(key: String) {
            store.remove(key)
        }

        override fun onKeystoreCorruption() = Unit
    }

    private fun accessKey(account: String) = "acct:$account:access_token"
    private fun refreshKeyOf(account: String) = "acct:$account:refresh_token"

    private val noRefresh: Lazy<HttpClient> = lazy { error("refresh client not expected in this test") }

    private fun refreshClientAnswering(status: HttpStatusCode): Lazy<HttpClient> = lazy {
        HttpClient(MockEngine { respondError(status) }) {
            install(ContentNegotiation) { json() }
            defaultRequest {
                url(SERVER)
                contentType(ContentType.Application.Json)
            }
        }
    }

    private fun seededStore(refreshClient: Lazy<HttpClient>) = FakeStore(
        refreshClient,
        seed = mapOf(
            "active_account_id" to "acctA",
            accessKey("acctA") to "A-access",
            refreshKeyOf("acctA") to "A-refresh",
        ),
    )

    @Test
    fun `a rejected refresh drops the account's tokens`() = runTest {
        val store = seededStore(refreshClientAnswering(HttpStatusCode.Unauthorized))

        val result = store.refreshAccessTokenFor("acctA", SERVER)

        assertThat(result).isEqualTo(RefreshResult.HardExpired)
        assertThat(store.store[accessKey("acctA")]).isNull()
        assertThat(store.store[refreshKeyOf("acctA")]).isNull()
        // The cold-start check reads through these, so this is what stops the replay.
        assertThat(store.isAuthenticated).isFalse()
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `a rejected refresh leaves the account itself intact`() = runTest {
        val store = seededStore(refreshClientAnswering(HttpStatusCode.Unauthorized))

        store.refreshAccessTokenFor("acctA", SERVER)

        // Only the tokens go — the account must stay listed and re-loginable.
        assertThat(store.store["active_account_id"]).isEqualTo("acctA")
    }

    @Test
    fun `a server error keeps the session`() = runTest {
        val store = seededStore(refreshClientAnswering(HttpStatusCode.InternalServerError))

        val result = store.refreshAccessTokenFor("acctA", SERVER)

        // A 5xx is a server-side blip, not evidence the session is dead — tearing down here would
        // log the user out over a transient failure a later request would have recovered from.
        assertThat(result).isEqualTo(RefreshResult.Transient)
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
        assertThat(store.isAuthenticated).isTrue()
    }

    @Test
    fun `a successful refresh keeps the session`() = runTest {
        val refreshClient = lazy {
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"token":"A-access-2"}""",
                        headers = headersOf("Content-Type", listOf(ContentType.Application.Json.toString())),
                    )
                },
            ) {
                install(ContentNegotiation) { json() }
                defaultRequest {
                    url(SERVER)
                    contentType(ContentType.Application.Json)
                }
            }
        }
        val store = seededStore(refreshClient)

        val result = store.refreshAccessTokenFor("acctA", SERVER)

        assertThat(result).isEqualTo(RefreshResult.Refreshed)
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access-2")
    }

    @Test
    fun `a dead session reports its expiry once however many requests discover it`() = runTest {
        val store = seededStore(noRefresh)
        var emissions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.sessionExpiredFlow.collect { emissions++ }
        }

        // A cold start fans out a dozen or so requests; each 401 settles independently and lands here.
        repeat(12) { store.emitSessionExpired("acctA") }

        assertThat(emissions).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `a new sign-in re-arms the expiry signal`() = runTest {
        val store = seededStore(noRefresh)
        var emissions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.sessionExpiredFlow.collect { emissions++ }
        }

        store.emitSessionExpired("acctA")
        store.setTokens("new-access", "new-refresh")
        store.emitSessionExpired()

        // A latch left set would swallow the NEXT session's expiry entirely.
        assertThat(emissions).isEqualTo(2)
        job.cancel()
    }

    @Test
    fun `removing an account re-arms the expiry signal`() = runTest {
        val store = seededStore(noRefresh)
        var emissions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.sessionExpiredFlow.collect { emissions++ }
        }

        store.emitSessionExpired("acctA")
        // AccountSwitcher's remove-last-account path: removeAccount, then this same signal is reused
        // to drive nav-to-auth. Swallowing it would strand the user in a logged-out shell.
        store.removeAccount("acctA")
        store.emitSessionExpired()

        assertThat(emissions).isEqualTo(2)
        job.cancel()
    }

    @Test
    fun `an explicit logout re-arms the expiry signal`() = runTest {
        val store = seededStore(noRefresh)
        var emissions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.sessionExpiredFlow.collect { emissions++ }
        }

        store.emitSessionExpired("acctA")
        store.clearTokens()
        store.emitSessionExpired()

        assertThat(emissions).isEqualTo(2)
        job.cancel()
    }
}
