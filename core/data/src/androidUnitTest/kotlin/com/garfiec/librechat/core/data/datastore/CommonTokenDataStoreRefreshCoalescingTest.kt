package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.RefreshResult
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The flight mutex serializes same-account refreshes but does not *coalesce* them: before this, a
 * cold-start fan-out of N 401s meant N sequential refresh POSTs, each waiting out the one before it,
 * before any content rendered (issue #323).
 *
 * A caller that passes the bearer its request actually sent lets a waiter detect that the holder
 * before it already rotated the slot, and skip its own POST. These tests pin both halves: the burst
 * collapses to one POST, and every case where the stored value did *not* move must still POST — a
 * false short-circuit would report a refresh that never happened and, on a dead session, swallow the
 * logout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommonTokenDataStoreRefreshCoalescingTest {

    private companion object {
        const val SERVER = "https://chat.example.com"
        const val ACCOUNT = "acctA"
        const val STALE_ACCESS = "T0"
        const val WAITERS = 5
    }

    private class FakeStore(
        refreshClient: Lazy<HttpClient>,
        seed: Map<String, String>,
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

    // MockEngine defaults to Dispatchers.IO, which hops the handler onto a real thread pool outside the
    // virtual scheduler; yield()/advanceUntilIdle() cannot wait for that, so the assertions would race
    // it. Pinning to Unconfined keeps request handling synchronous with the test dispatcher.
    private fun mockEngine(handler: MockRequestHandler): MockEngine = MockEngine(
        MockEngineConfig().apply {
            requestHandlers.add(handler)
            dispatcher = Dispatchers.Unconfined
        },
    )

    private fun client(engine: MockEngine): Lazy<HttpClient> = lazy {
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
            defaultRequest {
                url(SERVER)
                contentType(ContentType.Application.Json)
            }
        }
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun seeded(
        refreshClient: Lazy<HttpClient>,
        access: String? = STALE_ACCESS,
    ): FakeStore = FakeStore(
        refreshClient,
        seed = buildMap {
            put("active_account_id", ACCOUNT)
            access?.let { put(accessKey(ACCOUNT), it) }
            put(refreshKeyOf(ACCOUNT), "R0")
        },
    )

    /**
     * Park the first POST, queue [WAITERS] more callers behind the flight lock, then release. Returns
     * the POST count and every caller's result.
     */
    private suspend fun TestScope.burst(
        store: FakeStore,
        release: CompletableDeferred<Unit>,
        usedAccessToken: String?,
    ): List<RefreshResult> {
        val callers = mutableListOf<Deferred<RefreshResult>>()
        repeat(WAITERS + 1) {
            callers += async { store.refreshAccessTokenFor(ACCOUNT, SERVER, usedAccessToken) }
            // Let each caller reach the flight lock (or the parked POST) before starting the next, so
            // they genuinely queue rather than interleaving arbitrarily.
            yield()
        }
        release.complete(Unit)
        advanceUntilIdle()
        return callers.map { it.await() }
    }

    @Test
    fun `a queued burst collapses to one refresh POST once the token has rotated`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val release = CompletableDeferred<Unit>()
            val engine = mockEngine {
                posts++
                release.await()
                respond(content = """{"token":"T1"}""", status = HttpStatusCode.OK, headers = jsonHeaders)
            }
            val store = seeded(client(engine))

            val results = burst(store, release, usedAccessToken = STALE_ACCESS)

            assertThat(posts).isEqualTo(1)
            assertThat(results).containsExactlyElementsIn(List(WAITERS + 1) { RefreshResult.Refreshed })
            assertThat(store.store[accessKey(ACCOUNT)]).isEqualTo("T1")
        }

    @Test
    fun `a null usedAccessToken keeps the pre-existing one POST per caller behaviour`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val release = CompletableDeferred<Unit>()
            val engine = mockEngine {
                posts++
                release.await()
                respond(content = """{"token":"T$posts"}""", status = HttpStatusCode.OK, headers = jsonHeaders)
            }
            val store = seeded(client(engine))

            val results = burst(store, release, usedAccessToken = null)

            assertThat(posts).isEqualTo(WAITERS + 1)
            assertThat(results).containsExactlyElementsIn(List(WAITERS + 1) { RefreshResult.Refreshed })
        }

    /**
     * The OpenID reuse path: the server answers 2xx with the *same* token. Nothing rotated, so nothing
     * may short-circuit — a comparison that treated "a token is present" as "someone refreshed" would
     * report success here off a token that is still the stale one.
     */
    @Test
    fun `an unchanged stored token does not short-circuit`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val release = CompletableDeferred<Unit>()
            val engine = mockEngine {
                posts++
                release.await()
                respond(
                    content = """{"token":"$STALE_ACCESS"}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val store = seeded(client(engine))

            burst(store, release, usedAccessToken = STALE_ACCESS)

            assertThat(posts).isEqualTo(WAITERS + 1)
        }

    /**
     * A dropped slot reads back null, not "changed". If that counted as a rotation the whole burst
     * would report [RefreshResult.Refreshed] against an account with no tokens at all and the user
     * would never be routed to re-auth.
     */
    @Test
    fun `a dropped slot still settles hard-expired instead of short-circuiting`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val engine = mockEngine {
                posts++
                respond(content = """{"error":"jwt expired"}""", status = HttpStatusCode.Unauthorized)
            }
            // No access token in the slot: the shape left behind by a prior hard-expiry's invalidate.
            val store = seeded(client(engine), access = null)

            val result = store.refreshAccessTokenFor(ACCOUNT, SERVER, usedAccessToken = STALE_ACCESS)
            advanceUntilIdle()

            assertThat(result).isEqualTo(RefreshResult.HardExpired)
            assertThat(posts).isAtLeast(1)
            assertThat(store.store[refreshKeyOf(ACCOUNT)]).isNull()
        }

    /**
     * The short-circuit compares against `accountKey`'s own slot, so it is only sound while nothing
     * writes one account's token into another's. `onAccountResolved` is the one path that re-homes a
     * token into a keyed slot, and it must take the id derived from the staged pair.
     */
    @Test
    fun `onAccountResolved writes only its own account's slot`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(
            lazy { error("no refresh expected") },
            seed = mapOf(
                accessKey("acctB") to "B-access",
                refreshKeyOf("acctB") to "B-refresh",
                CommonTokenDataStore.KEY_ACCESS_TOKEN to "staged-access",
                CommonTokenDataStore.KEY_REFRESH_TOKEN to "staged-refresh",
            ),
        )

        store.onAccountResolved("acctA")

        assertThat(store.store[accessKey("acctA")]).isEqualTo("staged-access")
        assertThat(store.store[accessKey("acctB")]).isEqualTo("B-access")
        assertThat(store.store[refreshKeyOf("acctB")]).isEqualTo("B-refresh")
    }
}
