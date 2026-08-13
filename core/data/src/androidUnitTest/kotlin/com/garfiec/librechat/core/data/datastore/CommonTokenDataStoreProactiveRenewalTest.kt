package com.garfiec.librechat.core.data.datastore

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

/**
 * Proactive renewal (issue #323): renew an access token whose `exp` has passed *before* the request
 * goes out, instead of paying a 401-then-refresh-then-retry round trip per fanned-out request.
 *
 * Two families of test carry the weight. The **runaway-loop** ones cover a token that reads as stale
 * the instant it is issued (a fast device clock, or a `SESSION_EXPIRY` shorter than the skew window),
 * which would otherwise be one refresh POST per request forever — strictly worse than the reactive
 * path this replaces. The **best-effort** ones pin the rule that a renewal nobody is waiting on can
 * neither log the user out nor stall the requests behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class CommonTokenDataStoreProactiveRenewalTest {

    private companion object {
        const val ACCOUNT = "acctA"
        const val CALLS = 4
    }

    /** A JWT whose `exp` is [secondsFromNow] away. Only the payload is real; nothing verifies it. */
    private fun jwt(secondsFromNow: Long, id: String = "u1"): String {
        val exp = Clock.System.now().toEpochMilliseconds() / 1000 + secondsFromNow
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256"}""".encodeToByteArray())
        val payload = encoder.encode("""{"id":"$id","exp":$exp}""".encodeToByteArray())
        return "$header.$payload.sig"
    }

    private fun seeded(refreshClient: Lazy<HttpClient>, access: String?): FakeTokenStore = FakeTokenStore(
        refreshClient,
        seed = buildMap {
            put(ACTIVE_ACCOUNT_KEY, ACCOUNT)
            access?.let { put(accessKeyOf(ACCOUNT), it) }
            put(refreshKeyOf(ACCOUNT), "R0")
        },
    )

    /** Answers every refresh with a token expiring [secondsFromNow] away, counting the POSTs. */
    private fun renewingEngine(secondsFromNow: Long, counter: IntArray): MockEngine = unconfinedMockEngine {
        counter[0]++
        respond(
            refreshResponseBody(jwt(secondsFromNow, id = "renewed${counter[0]}")),
            HttpStatusCode.OK,
            jsonResponseHeaders,
        )
    }

    @Test
    fun `an expired access token is renewed before the request goes out`() =
        runTest(UnconfinedTestDispatcher()) {
            val posts = intArrayOf(0)
            val stale = jwt(-60)
            val store = seeded(refreshClientOf(renewingEngine(900, posts)), access = stale)

            val bearer = store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, stale)

            assertThat(posts[0]).isEqualTo(1)
            assertThat(bearer).isNotEqualTo(stale)
            assertThat(bearer).isEqualTo(store.getAccessTokenFor(ACCOUNT))
            // The renewed token is good, so a second pass must not fire again.
            store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, bearer)
            assertThat(posts[0]).isEqualTo(1)
        }

    @Test
    fun `a token still inside its lifetime is left alone`() = runTest(UnconfinedTestDispatcher()) {
        val posts = intArrayOf(0)
        val fresh = jwt(900)
        val store = seeded(refreshClientOf(renewingEngine(900, posts)), access = fresh)

        repeat(CALLS) { assertThat(store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, fresh)).isEqualTo(fresh) }

        assertThat(posts[0]).isEqualTo(0)
    }

    @Test
    fun `a token with no readable deadline is left to the reactive path`() =
        runTest(UnconfinedTestDispatcher()) {
            val posts = intArrayOf(0)
            // An OpenID pass-through: opaque, no `exp` to read. Unknown must mean "do nothing".
            val opaque = "opaque-provider-token"
            val store = seeded(refreshClientOf(renewingEngine(900, posts)), access = opaque)

            assertThat(store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, opaque)).isEqualTo(opaque)

            assertThat(posts[0]).isEqualTo(0)
        }

    @Test
    fun `an absent token does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val posts = intArrayOf(0)
        val store = seeded(refreshClientOf(renewingEngine(900, posts)), access = null)

        assertThat(store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, null)).isNull()

        assertThat(posts[0]).isEqualTo(0)
    }

    /**
     * Clock skew / a `SESSION_EXPIRY` under the skew window: the server issues a **new** token that is
     * already stale by our reading. Without the guard this is one POST per request, forever.
     */
    @Test
    fun `a freshly-issued token that still reads as stale suppresses further renewal`() =
        runTest(UnconfinedTestDispatcher()) {
            val posts = intArrayOf(0)
            val store = seeded(refreshClientOf(renewingEngine(-30, posts)), access = jwt(-60))

            var bearer: String? = jwt(-60)
            repeat(CALLS) { bearer = store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, bearer) }

            assertThat(posts[0]).isEqualTo(1)
        }

    /**
     * The OpenID reuse path (`OPENID_REUSE_EXPIRY_BUFFER_SECONDS`): a 2xx that hands back *the same*
     * token with no rotation. The stored value never moves, so the coalescing short-circuit can never
     * fire — only the runaway guard stops this one.
     */
    @Test
    fun `a refresh that returns the same stale token suppresses further renewal`() =
        runTest(UnconfinedTestDispatcher()) {
            val posts = intArrayOf(0)
            val stale = jwt(-60)
            val engine = unconfinedMockEngine {
                posts[0]++
                respond(refreshResponseBody(stale), HttpStatusCode.OK, jsonResponseHeaders)
            }
            val store = seeded(refreshClientOf(engine), access = stale)

            repeat(CALLS) { store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, stale) }

            assertThat(posts[0]).isEqualTo(1)
        }

    /**
     * A null account means "the live active binding", which is the *keyed* slot on any post-#179
     * install — not the bare key. Reading the wrong one would silently no-op and the whole feature
     * would be dead on the main client.
     */
    @Test
    fun `a null account renews the active binding's keyed slot`() = runTest(UnconfinedTestDispatcher()) {
        val posts = intArrayOf(0)
        val stale = jwt(-60)
        val store = seeded(refreshClientOf(renewingEngine(900, posts)), access = stale)

        store.ensureFreshAccessToken(null, TEST_SERVER, stale)

        assertThat(posts[0]).isEqualTo(1)
        assertThat(store.store[accessKeyOf(ACCOUNT)]).isNotEqualTo(stale)
    }

    /**
     * **Every** branch posts to the server the caller snapshotted — including the null-account one,
     * which the reactive path leaves aimed at the *live* base URL. A refresh token must never be
     * reachable by a deployment the request was not bound to, and "an account happened to be resolved"
     * is not a property worth making that guarantee depend on.
     */
    @Test
    fun `the renewal POST is pinned to the snapshot's server for both keyed and null accounts`() =
        runTest(UnconfinedTestDispatcher()) {
            val pinnedServer = "https://pinned.example.com"
            for (account in listOf(ACCOUNT, null)) {
                val urls = mutableListOf<String>()
                val stale = jwt(-60)
                val engine = unconfinedMockEngine { request ->
                    urls += request.url.toString()
                    respond(refreshResponseBody(jwt(900)), HttpStatusCode.OK, jsonResponseHeaders)
                }
                // The client's own default URL is a DIFFERENT server, standing in for a live base URL
                // that a switch has already moved on to. The POST must ignore it.
                val store = seeded(refreshClientOf(engine, baseUrl = "https://live-elsewhere.example.com"), stale)

                store.ensureFreshAccessToken(account, pinnedServer, stale)

                assertThat(urls).containsExactly("$pinnedServer/api/auth/refresh")
            }
        }

    /**
     * **A proactive renewal must never log anyone out.** The backend answers `401` identically for a
     * dead session and a transiently-missed one, which is why the reactive path retries before
     * settling. A single best-effort attempt cannot tell them apart, so it must not settle the
     * question at all: the slot stays, the request proceeds on its old bearer, and the reactive ladder
     * keeps sole ownership of the teardown.
     */
    @Test
    fun `a rejected renewal neither drops the slot nor reports the session expired`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            var reported = 0
            val stale = jwt(-60)
            val engine = unconfinedMockEngine {
                posts++
                respond("", HttpStatusCode.Unauthorized)
            }
            val store = seeded(refreshClientOf(engine), access = stale)
            backgroundScope.launch { store.sessionExpiredFlow.collect { reported++ } }

            val bearer = store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, stale)

            assertThat(reported).isEqualTo(0)
            assertThat(store.store[accessKeyOf(ACCOUNT)]).isEqualTo(stale)
            assertThat(store.store[refreshKeyOf(ACCOUNT)]).isEqualTo("R0")
            assertThat(bearer).isEqualTo(stale)
            // One attempt, not the reactive ladder's three: retrying cannot change the answer for a
            // caller that is going to ignore it either way.
            assertThat(posts).isEqualTo(1)
        }

    /**
     * Negative caching. A renewal that cannot succeed changes nothing for the coalescing check to see,
     * so without suppression every request in a cold-start fan-out queues behind the flight lock and
     * spends its own timeout *before being sent* — far worse than the reactive path, where the same
     * requests fail in parallel.
     */
    @Test
    fun `a failed renewal is not retried by every subsequent request`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val stale = jwt(-60)
            val engine = unconfinedMockEngine {
                posts++
                respond("", HttpStatusCode.ServiceUnavailable)
            }
            val store = seeded(refreshClientOf(engine), access = stale)

            repeat(CALLS) { store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, stale) }

            assertThat(posts).isEqualTo(1)
        }

    /**
     * The slot has an access token but no refresh token — a torn pair, or the bare staging slot after a
     * re-home. A best-effort renewal must return without POSTing and **without dropping the access
     * token**: the reactive path decides what a missing refresh token means, not this one.
     */
    @Test
    fun `a slot with no refresh token skips the POST and keeps the access token`() =
        runTest(UnconfinedTestDispatcher()) {
            var posts = 0
            val stale = jwt(-60)
            val engine = unconfinedMockEngine {
                posts++
                respond(refreshResponseBody(jwt(900)), HttpStatusCode.OK, jsonResponseHeaders)
            }
            val store = FakeTokenStore(
                refreshClientOf(engine),
                seed = mapOf(ACTIVE_ACCOUNT_KEY to ACCOUNT, accessKeyOf(ACCOUNT) to stale),
            )

            val bearer = store.ensureFreshAccessToken(ACCOUNT, TEST_SERVER, stale)

            assertThat(posts).isEqualTo(0)
            assertThat(bearer).isEqualTo(stale)
            assertThat(store.store[accessKeyOf(ACCOUNT)]).isEqualTo(stale)
        }

    /**
     * Suppression is per account, not per process: the conditions that trigger it are properties of
     * one deployment, and one account on a misconfigured server must not silently turn the feature off
     * for another account on a healthy one.
     */
    @Test
    fun `suppressing one account leaves another account renewing`() = runTest(UnconfinedTestDispatcher()) {
        var posts = 0
        val staleA = jwt(-60, id = "a")
        val staleB = jwt(-60, id = "b")
        val engine = unconfinedMockEngine { request ->
            posts++
            // Account A's server is down; account B's answers normally.
            if (request.url.toString().startsWith("https://a.example.com")) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(refreshResponseBody(jwt(900, id = "b-renewed")), HttpStatusCode.OK, jsonResponseHeaders)
            }
        }
        val store = FakeTokenStore(
            refreshClientOf(engine),
            seed = mapOf(
                ACTIVE_ACCOUNT_KEY to "acctA",
                accessKeyOf("acctA") to staleA,
                refreshKeyOf("acctA") to "RA",
                accessKeyOf("acctB") to staleB,
                refreshKeyOf("acctB") to "RB",
            ),
        )

        // A fails and is suppressed; a second attempt for A must not POST again.
        store.ensureFreshAccessToken("acctA", "https://a.example.com", staleA)
        store.ensureFreshAccessToken("acctA", "https://a.example.com", staleA)
        assertThat(posts).isEqualTo(1)

        val bearerB = store.ensureFreshAccessToken("acctB", "https://b.example.com", staleB)

        assertThat(posts).isEqualTo(2)
        assertThat(bearerB).isNotEqualTo(staleB)
        assertThat(store.store[accessKeyOf("acctB")]).isEqualTo(bearerB)
    }
}
