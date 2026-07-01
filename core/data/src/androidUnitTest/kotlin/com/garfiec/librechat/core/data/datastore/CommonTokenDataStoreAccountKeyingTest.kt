package com.garfiec.librechat.core.data.datastore

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
    fun `switching account without logout re-homes tokens and drops the previous slot`() = runTest {
        val store = FakeKeyedStore(
            noRefresh,
            seed = mapOf(
                "active_account_id" to "acctA",
                accessKey("acctA") to "A-access",
                refreshKeyOf("acctA") to "A-refresh",
            ),
        )
        // Soft-expiry → login B without an explicit logout: setTokens for B lands under A's key
        // (activeAccountKey still A) until the establish hook re-homes it.
        store.setTokens(accessToken = "B-access", refreshToken = "B-refresh")

        store.onAccountResolved("acctB")

        assertThat(store.store[accessKey("acctB")]).isEqualTo("B-access")
        assertThat(store.store[refreshKeyOf("acctB")]).isEqualTo("B-refresh")
        assertThat(store.store).doesNotContainKey(accessKey("acctA"))
        assertThat(store.store).doesNotContainKey(refreshKeyOf("acctA"))
        assertThat(store.store["active_account_id"]).isEqualTo("acctB")
        assertThat(store.getAccessToken()).isEqualTo("B-access")
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
    fun `setTokens after resolve writes under the keyed slot`() = runTest {
        val store = FakeKeyedStore(noRefresh)
        store.setTokens("bootstrap", "bootstrap-refresh")
        store.onAccountResolved("acctA")

        store.setTokens("A-access-2", "A-refresh-2")

        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access-2")
        assertThat(store.store[refreshKeyOf("acctA")]).isEqualTo("A-refresh-2")
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

        assertThat(ok).isTrue()
        assertThat(store.store[accessKey("acctA")]).isEqualTo("A-access-refreshed")
        assertThat(store.getAccessToken()).isEqualTo("A-access-refreshed")
    }
}
