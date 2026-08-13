package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.RefreshResult
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The flight mutex serializes same-account refreshes but does not *coalesce* them: on its own, a
 * cold-start fan-out of N 401s costs N sequential refresh POSTs, each waiting out the one before it,
 * before any content renders (issue #323).
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
        const val ACCOUNT = "acctA"
        const val STALE_ACCESS = "T0"
        const val WAITERS = 5
    }

    private fun seeded(
        refreshClient: Lazy<io.ktor.client.HttpClient>,
        access: String? = STALE_ACCESS,
    ): FakeTokenStore = FakeTokenStore(
        refreshClient,
        seed = buildMap {
            put(ACTIVE_ACCOUNT_KEY, ACCOUNT)
            access?.let { put(accessKeyOf(ACCOUNT), it) }
            put(refreshKeyOf(ACCOUNT), "R0")
        },
    )

    /**
     * Park the first POST, queue [WAITERS] more callers behind the flight lock, then release. Returns
     * every caller's result.
     */
    private suspend fun TestScope.burst(
        store: FakeTokenStore,
        release: CompletableDeferred<Unit>,
        usedAccessToken: String?,
    ): List<RefreshResult> {
        val callers = mutableListOf<Deferred<RefreshResult>>()
        repeat(WAITERS + 1) {
            callers += async { store.refreshAccessTokenFor(ACCOUNT, TEST_SERVER, usedAccessToken) }
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
            val engine = unconfinedMockEngine {
                posts++
                release.await()
                respond(refreshResponseBody("T1"), HttpStatusCode.OK, jsonResponseHeaders)
            }
            val store = seeded(refreshClientOf(engine))

            val results = burst(store, release, usedAccessToken = STALE_ACCESS)

            assertThat(posts).isEqualTo(1)
            assertThat(results).containsExactlyElementsIn(List(WAITERS + 1) { RefreshResult.Refreshed })
            assertThat(store.store[accessKeyOf(ACCOUNT)]).isEqualTo("T1")
        }

    @Test
    fun `a null usedAccessToken keeps the pre-existing one POST per caller behaviour`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val release = CompletableDeferred<Unit>()
            val engine = unconfinedMockEngine {
                posts++
                release.await()
                respond(refreshResponseBody("T$posts"), HttpStatusCode.OK, jsonResponseHeaders)
            }
            val store = seeded(refreshClientOf(engine))

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
            val engine = unconfinedMockEngine {
                posts++
                release.await()
                respond(refreshResponseBody(STALE_ACCESS), HttpStatusCode.OK, jsonResponseHeaders)
            }
            val store = seeded(refreshClientOf(engine))

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
            val engine = unconfinedMockEngine {
                posts++
                respond("""{"error":"jwt expired"}""", HttpStatusCode.Unauthorized)
            }
            // No access token in the slot: the shape left behind by a prior hard-expiry's invalidate.
            val store = seeded(refreshClientOf(engine), access = null)

            val result = store.refreshAccessTokenFor(ACCOUNT, TEST_SERVER, usedAccessToken = STALE_ACCESS)
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
        val store = FakeTokenStore(
            lazy { error("no refresh expected") },
            seed = mapOf(
                accessKeyOf("acctB") to "B-access",
                refreshKeyOf("acctB") to "B-refresh",
                CommonTokenDataStore.KEY_ACCESS_TOKEN to "staged-access",
                CommonTokenDataStore.KEY_REFRESH_TOKEN to "staged-refresh",
            ),
        )

        store.onAccountResolved("acctA")

        assertThat(store.store[accessKeyOf("acctA")]).isEqualTo("staged-access")
        assertThat(store.store[accessKeyOf("acctB")]).isEqualTo("B-access")
        assertThat(store.store[refreshKeyOf("acctB")]).isEqualTo("B-refresh")
    }
}
