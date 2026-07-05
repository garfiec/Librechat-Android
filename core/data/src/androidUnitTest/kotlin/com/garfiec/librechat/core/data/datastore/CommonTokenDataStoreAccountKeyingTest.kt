package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.RefreshResult
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
 * Verifies the account-keying of [CommonTokenDataStore]: keys are namespaced by the active account,
 * the sync mirror seeds the right bearer at construction, and the identity hooks migrate/clear the
 * keyed slots. Uses a plain in-memory key/value store so no `EncryptedSharedPreferences`/Keychain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommonTokenDataStoreAccountKeyingTest {

    private class FakeKeyedStore(
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

    private val noRefresh: Lazy<HttpClient> = lazy { error("refresh client not expected in this test") }

    private fun accessKey(account: String) = "acct:$account:access_token"
    private fun refreshKeyOf(account: String) = "acct:$account:refresh_token"

    @Test
    fun `init seeds cached bearer from the mirror's keyed slot`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
            ),
        )

        assertThat(store.isAuthenticated).isTrue()
        assertThat(store.getAccessToken()).isEqualTo("A-access")
    }

    @Test
    fun `init falls back to the bare key for a legacy pre-keying install`() = runTest {
        val store = FakeKeyedStore(noRefresh, seed = mapOf("access_token" to "legacy-access"))

        assertThat(store.isAuthenticated).isTrue()
        assertThat(store.getAccessToken()).isEqualTo("legacy-access")
    }

    @Test
    fun `init reports logged out when storage is empty`() = runTest {
        val store = FakeKeyedStore(noRefresh)

        assertThat(store.isAuthenticated).isFalse()
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `onAccountResolved migrates bare tokens into the keyed slot and writes the mirror`() = runTest {
        val store = FakeKeyedStore(noRefresh)
        // Fresh login: no account resolved yet, so setTokens lands under the bare keys.
        store.setTokens(accessToken = "A-access", refreshToken = "A-refresh")

        store.onAccountResolved("acctA")

        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
        assertThat(store.store["active_account_id"]).isEqualTo("acctA")
        assertThat(store.store).doesNotContainKey("access_token")
        assertThat(store.store).doesNotContainKey("refresh_token")
        assertThat(store.getAccessToken()).isEqualTo("A-access")
    }

    @Test
    fun `onAccountResolved with a torn staging pair does not cache an unpersisted bearer`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                // Torn staging: a bare access token survived but its refresh partner didn't (e.g. a
                // partial keychain write). The keyed slot still holds the prior value.
                "access_token" to "torn-access",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )

        store.onAccountResolved("acctA")

        // The keyed slot is NOT overwritten from the torn pair, and the cached bearer matches storage
        // (the keyed value), never the unpersisted staged token.
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        assertThat(store.getAccessToken()).isEqualTo("A-access")
        // Bare staging keys are cleared regardless.
        assertThat(store.store).doesNotContainKey("access_token")
    }

    @Test
    fun `onAccountResolved is a no-op when the account is unchanged`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf("active_account_id" to "acctA", accessKey("acctA") to "A-access"),
        )

        store.onAccountResolved("acctA")

        assertThat(store.getAccessToken()).isEqualTo("A-access")
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
    }

    @Test
    fun `authenticating a different account while one is active retains the previous account's tokens`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )
        // Soft-expiry → login B without an explicit logout: setTokens stages B under the bare keys
        // (never A's keyed slot), then the establish hook re-homes the staged pair into acctB.
        store.setTokens(accessToken = "B-access", refreshToken = "B-refresh")

        store.onAccountResolved("acctB")

        assertThat(store.store[accessKey("acctB")]).isEqualTo("B-access")
        assertThat(store.store[refreshKeyOf("acctB")]).isEqualTo("B-refresh")
        // A's tokens are RETAINED (multi-account) — a switch back needs no re-login.
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
        assertThat(store.store).doesNotContainKey("access_token")
        assertThat(store.store).doesNotContainKey("refresh_token")
        assertThat(store.store["active_account_id"]).isEqualTo("acctB")
        assertThat(store.getAccessToken()).isEqualTo("B-access")
    }

    @Test
    fun `staged tokens are readable while staged and clearStagedTokens drops only the bare keys`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )
        assertThat(store.getStagedAccessToken()).isNull()

        // An add-account sign-in stages B under the bare keys.
        store.setTokens(accessToken = "B-access", refreshToken = "B-refresh")
        assertThat(store.getStagedAccessToken()).isEqualTo("B-access")

        // Abandoning the flow clears the staging but never a keyed slot.
        store.clearStagedTokens()
        assertThat(store.getStagedAccessToken()).isNull()
        assertThat(store.store).doesNotContainKey("access_token")
        assertThat(store.store).doesNotContainKey("refresh_token")
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
    }

    @Test
    fun `getAccessTokenFor reads an account's own slot even while another sign-in is staged`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )
        // Mid add-account: B's fresh pair is staged, the live cache now holds B's token.
        store.setTokens(accessToken = "B-access", refreshToken = "B-refresh")
        assertThat(store.getAccessToken()).isEqualTo("B-access")

        // A's in-flight requests still read A's own keyed token — the barrier's keyed bearer path.
        assertThat(store.getAccessTokenFor("acctA")).isEqualTo("A-access")
        // And an account with no slot yields null, never the staged cache.
        assertThat(store.getAccessTokenFor("acctC")).isNull()
    }

    @Test
    fun `selectAccount switches to an already-keyed account without writing or deleting tokens`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
                accessKey("acctB") to "B-access",
                refreshKeyOf("acctB") to "B-refresh",
            ),
        )

        store.selectAccount("acctB")

        assertThat(store.store["active_account_id"]).isEqualTo("acctB")
        assertThat(store.getAccessToken()).isEqualTo("B-access")
        // Neither slot is written or deleted; no bare staging keys appear.
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh")
        assertThat(store.store[accessKey("acctB")]).isEqualTo("B-access")
        assertThat(store.store).doesNotContainKey("access_token")

        // Switch back is cold-free.
        store.selectAccount("acctA")
        assertThat(store.getAccessToken()).isEqualTo("A-access")
    }

    @Test
    fun `removeAccount drops a non-active account and leaves the active one untouched`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
                accessKey("acctB") to "B-access",
                refreshKeyOf("acctB") to "B-refresh",
            ),
        )

        store.removeAccount("acctB")

        assertThat(store.store).doesNotContainKey(accessKey("acctB"))
        assertThat(store.store).doesNotContainKey(refreshKeyOf("acctB"))
        assertThat(store.store["active_account_id"]).isEqualTo("acctA")
        assertThat(store.getAccessToken()).isEqualTo("A-access")
    }

    @Test
    fun `removeAccount of the active account clears the mirror and cached bearer`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )

        store.removeAccount("acctA")

        assertThat(store.store).doesNotContainKey(accessKey("acctA"))
        assertThat(store.store).doesNotContainKey("active_account_id")
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `getAccessTokenFor reads a non-active account's keyed slot`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                accessKey("acctB") to "B-access",
            ),
        )

        assertThat(store.getAccessTokenFor("acctB")).isEqualTo("B-access")
        assertThat(store.getAccessTokenFor("acctA")).isEqualTo("A-access")
        assertThat(store.getAccessTokenFor("acctC")).isNull()
        // The active bearer is unchanged by the out-of-band reads.
        assertThat(store.getAccessToken()).isEqualTo("A-access")
    }

    @Test
    fun `onAccountCleared removes the active account's tokens and the mirror`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )

        store.onAccountCleared()

        assertThat(store.store).doesNotContainKey(accessKey("acctA"))
        assertThat(store.store).doesNotContainKey(refreshKeyOf("acctA"))
        assertThat(store.store).doesNotContainKey("active_account_id")
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `setTokens stages under the bare keys even when an account is active`() = runTest {
        val store = FakeKeyedStore(noRefresh)
        store.setTokens("bootstrap", "bootstrap-refresh")
        store.onAccountResolved("acctA")

        // A re-auth (e.g. soft-expiry) stages under the bare keys, leaving acctA's keyed slot intact
        // until the establish hook re-homes it.
        store.setTokens("A-access-2", "A-refresh-2")

        assertThat(store.store["access_token"]).isEqualTo("A-access-2")
        assertThat(store.store[accessKey("acctA")]).isEqualTo("bootstrap")
        assertThat(store.getAccessToken()).isEqualTo("A-access-2")

        // Re-home overwrites the account's slot with the freshly-staged pair.
        store.onAccountResolved("acctA")
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access-2")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh-2")
        assertThat(store.store).doesNotContainKey("access_token")
    }

    @Test
    fun `setTokens clears the persisted mirror so a staging-window cold start can't resurrect the previous account`() =
        runTest {
            val store = FakeKeyedStore(
                noRefresh,
                seed = mapOf(
                    "active_account_id" to "acctA",
                    accessKey("acctA") to "A-access",
                    refreshKeyOf("acctA") to "A-refresh",
                ),
            )
            // Soft-expiry re-auth as B stages under the bare keys. The mirror must NOT still point at
            // A — otherwise a crash before onAccountResolved would cold-start back into A next launch.
            store.setTokens("B-access", "B-refresh")

            assertThat(store.store).doesNotContainKey("active_account_id")
            assertThat(store.store["access_token"]).isEqualTo("B-access")
            // A's keyed tokens are still retained for a later switch back.
            assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access")
        }

    @Test
    fun `clearTokens fully tears down the active session including any leftover bare staging keys`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
                // A stale staged pair left behind by an abandoned/killed login.
                "access_token" to "orphan-access",
                "refresh_token" to "orphan-refresh",
            ),
        )

        store.clearTokens()

        assertThat(store.store).doesNotContainKey(accessKey("acctA"))
        assertThat(store.store).doesNotContainKey(refreshKeyOf("acctA"))
        assertThat(store.store).doesNotContainKey("active_account_id")
        // The orphaned staging pair is purged too, so it can't cold-start a phantom session.
        assertThat(store.store).doesNotContainKey("access_token")
        assertThat(store.store).doesNotContainKey("refresh_token")
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `refresh reads and writes the active account's keyed tokens`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"token":"A-access-refreshed","user":null}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", listOf("application/json")),
            )
        }
        val refreshClient = lazy {
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
                defaultRequest {
                    url("https://chat.example.com")
                    contentType(ContentType.Application.Json)
                }
            }
        }
        val store = FakeKeyedStore(
            refreshClient,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )

        val ok = store.refreshAccessToken()

        assertThat(ok).isEqualTo(RefreshResult.Refreshed)
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access-refreshed")
        assertThat(store.getAccessToken()).isEqualTo("A-access-refreshed")
    }

    @Test
    fun `refreshAccessTokenFor posts to the pinned base url and writes that account's slot`() = runTest {
        var requestedUrl: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = """{"token":"B-access-refreshed","user":null}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", listOf("application/json")),
            )
        }
        val refreshClient = lazy {
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
                // The client's default host is A's server; the pinned URL must win so B's refresh
                // token is never sent to A's host.
                defaultRequest {
                    url("https://a.example.com")
                    contentType(ContentType.Application.Json)
                }
            }
        }
        val store = FakeKeyedStore(
            refreshClient,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
                accessKey("acctB") to "B-access",
                refreshKeyOf("acctB") to "B-refresh",
            ),
        )

        val ok = store.refreshAccessTokenFor("acctB", "https://b.example.com")

        assertThat(ok).isEqualTo(RefreshResult.Refreshed)
        assertThat(requestedUrl).startsWith("https://b.example.com/api/auth/refresh")
        assertThat(store.store[accessKey("acctB")]).isEqualTo("B-access-refreshed")
        // acctB is not the active account, so the cached bearer (A's) is untouched.
        assertThat(store.getAccessToken()).isEqualTo("A-access")
    }

    @Test
    fun `session expiry scoped to the active account emits`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf("active_account_id" to "acctA", accessKey("acctA") to "A-access"),
        )
        var emissions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.sessionExpiredFlow.collect { emissions++ }
        }

        store.emitSessionExpired("acctA")
        store.emitSessionExpired() // unscoped (legacy/active) always emits

        assertThat(emissions).isEqualTo(2)
        job.cancel()
    }

    @Test
    fun `session expiry for a non-active account is suppressed`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf("active_account_id" to "acctA", accessKey("acctA") to "A-access"),
        )
        var emissions = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.sessionExpiredFlow.collect { emissions++ }
        }

        // A switched-away account's straggler failure must not tear down the live session.
        store.emitSessionExpired("acctB")

        assertThat(emissions).isEqualTo(0)
        job.cancel()
    }
}
