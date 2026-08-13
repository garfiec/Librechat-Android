package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.network.client.RefreshResult
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CommonTokenDataStoreConcurrencyTest {

    @Test
    fun `clearTokens does not block on an in-flight refresh and the refresh discards its result`() =
        runTest(UnconfinedTestDispatcher()) {
            val release = CompletableDeferred<Unit>()
            val engine = unconfinedMockEngine {
                release.await()
                respond(
                    content = refreshResponseBody("new-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonResponseHeaders,
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val refreshJob = async { store.refreshAccessToken() }
            // Give the refresh coroutine a chance to read the stored token and suspend inside the
            // MockEngine's release.await() (the network POST) before we start the concurrent clear.
            yield()
            advanceUntilIdle()

            val clearJob: Job = launch { store.clearTokens() }
            yield()
            advanceUntilIdle()

            // The POST no longer holds a lock, so clear runs to completion immediately while the
            // refresh is still parked on HTTP — a switch/logout never stalls behind a slow refresh.
            assertThat(refreshJob.isCompleted).isFalse()
            assertThat(clearJob.isCompleted).isTrue()
            assertThat(store.removeCount).isEqualTo(1)

            release.complete(Unit)
            advanceUntilIdle()

            // The refresh detects the epoch bump from clearTokens and discards its result (a
            // Transient outcome — a teardown owns the routing, so it never re-emits session-expired),
            // so it can never resurrect the cleared session. Final state: logged out.
            assertThat(refreshJob.await()).isEqualTo(RefreshResult.Transient)
            assertThat(store.persistedAccess()).isNull()
            assertThat(store.persistedRefresh()).isNull()
            assertThat(store.removeCount).isEqualTo(1)
        }

    @Test
    fun `refresh does not resurrect a logout that raced its network call`() =
        runTest(UnconfinedTestDispatcher()) {
            val release = CompletableDeferred<Unit>()
            val engine = unconfinedMockEngine {
                release.await()
                respond(
                    content = refreshResponseBody("resurrected-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonResponseHeaders,
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val refreshJob = async { store.refreshAccessToken() }
            yield()
            advanceUntilIdle()

            launch { store.clearTokens() }
            yield()
            advanceUntilIdle()

            release.complete(Unit)
            advanceUntilIdle()

            // Mutex serializes: refresh writes transiently, clear wipes after.
            // The invariant is the FINAL persisted state, not intermediate writes.
            refreshJob.await()
            assertThat(store.persistedAccess()).isNull()
            assertThat(store.persistedRefresh()).isNull()
        }

    @Test
    fun `refresh without a concurrent clear still writes the new token`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = unconfinedMockEngine {
                respond(
                    content = refreshResponseBody("fresh-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonResponseHeaders,
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.Refreshed)
            assertThat(store.writeLog).hasSize(1)
            assertThat(store.writeLog.single().first).isEqualTo("fresh-access")
            assertThat(store.persistedAccess()).isEqualTo("fresh-access")
        }

    @Test
    fun `sequential clearTokens and refreshAccessToken do not deadlock`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = unconfinedMockEngine {
                respond(
                    content = refreshResponseBody("seq-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonResponseHeaders,
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            store.clearTokens()
            assertThat(store.removeCount).isEqualTo(1)

            // Without a refresh token in storage the refresh should short-circuit
            // HardExpired without touching the network — and without deadlocking on a
            // mutex that clearTokens already released.
            val result = store.refreshAccessToken()
            assertThat(result).isEqualTo(RefreshResult.HardExpired)
        }

    @Test
    fun `a persistent refresh 401 retries the full budget then classifies HardExpired`() =
        runTest(UnconfinedTestDispatcher()) {
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                // text/html 401, exactly like the backend's "Refresh token expired or not found".
                respond(
                    content = "Refresh token expired or not found for this user",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf("Content-Type", "text/html"),
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.HardExpired)
            assertThat(requestCount).isEqualTo(3)
            // A settled hard expiry clears the stored tokens: the retry budget above has already
            // absorbed any transient rejection, so reaching here means the session is dead. Retaining
            // them replays it forever — `isLoggedIn()` is a token-PRESENCE check.
            assertThat(store.persistedRefresh()).isNull()
            assertThat(store.persistedAccess()).isNull()
        }

    @Test
    fun `a refresh 401 that clears on a retry recovers without logging out`() =
        runTest(UnconfinedTestDispatcher()) {
            // The exact "occasional logout" scenario: a transient server session-lookup miss returns
            // 401, then the very next attempt with the same token succeeds. Retrying absorbs it.
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                if (requestCount == 1) {
                    respond("transient miss", HttpStatusCode.Unauthorized, headersOf("Content-Type", "text/html"))
                } else {
                    respond(
                        content = """{"token":"recovered-access"}""",
                        status = HttpStatusCode.OK,
                        headers = jsonResponseHeaders,
                    )
                }
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.Refreshed)
            assertThat(requestCount).isEqualTo(2)
            assertThat(store.persistedAccess()).isEqualTo("recovered-access")
        }

    @Test
    fun `a persistent 5xx retries the full budget then classifies Transient`() =
        runTest(UnconfinedTestDispatcher()) {
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                respond("boom", HttpStatusCode.InternalServerError, headersOf("Content-Type", "text/html"))
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            // A server 5xx is recoverable — keep the session (no logout) and leave tokens intact.
            assertThat(result).isEqualTo(RefreshResult.Transient)
            assertThat(requestCount).isEqualTo(3)
            assertThat(store.persistedRefresh()).isEqualTo("initial-refresh")
        }

    @Test
    fun `a 2xx with an unparseable body is transient, not a spurious refresh`() =
        runTest(UnconfinedTestDispatcher()) {
            // A proxy interstitial: HTTP 200 but an HTML body. The body-deserialization failure is
            // recoverable, not a dead session, so it is classified transient and retried.
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                respond("<html>login</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.Transient)
            assertThat(requestCount).isEqualTo(3)
            assertThat(store.persistedAccess()).isEqualTo("initial-access")
        }

    @Test
    fun `a hard rejection on any attempt classifies HardExpired even if the last attempt is transient`() =
        runTest(UnconfinedTestDispatcher()) {
            // A genuinely-dead session that 401s, then hits a 5xx blip on the final attempt, must still
            // route to re-auth — not be masked as Transient by the last attempt.
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                if (requestCount < 3) {
                    respond("expired", HttpStatusCode.Unauthorized, headersOf("Content-Type", "text/html"))
                } else {
                    respond("boom", HttpStatusCode.InternalServerError, headersOf("Content-Type", "text/html"))
                }
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.HardExpired)
            assertThat(requestCount).isEqualTo(3)
        }

    @Test
    fun `a transport failure is terminal-Transient and does not retry under the flight lock`() =
        runTest(UnconfinedTestDispatcher()) {
            // A hung/unreachable server (POST throws) must NOT re-POST twice more while holding the
            // per-account flight lock — one attempt, then keep the session.
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                throw IOException("connection reset")
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.Transient)
            assertThat(requestCount).isEqualTo(1)
            assertThat(store.persistedRefresh()).isEqualTo("initial-refresh")
        }

    @Test
    fun `a transport failure after a 401 still classifies HardExpired`() =
        runTest(UnconfinedTestDispatcher()) {
            // A confirmed-dead session (401) followed by the server going unreachable must still route
            // to re-auth, not be downgraded to Transient by the transport error.
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                if (requestCount == 1) {
                    respond("expired", HttpStatusCode.Unauthorized, headersOf("Content-Type", "text/html"))
                } else {
                    throw IOException("connection reset")
                }
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.HardExpired)
            assertThat(requestCount).isEqualTo(2)
        }

    @Test
    fun `a 429 with a Retry-After beyond the cap stops instead of hammering`() =
        runTest(UnconfinedTestDispatcher()) {
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                respond(
                    content = "slow down",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf("Retry-After", "60"),
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            // Retry-After (60s) exceeds our max backoff, so we back off fully by not re-POSTing.
            assertThat(result).isEqualTo(RefreshResult.Transient)
            assertThat(requestCount).isEqualTo(1)
        }

    @Test
    fun `a 429 is retried and recovers on the next attempt`() =
        runTest(UnconfinedTestDispatcher()) {
            var requestCount = 0
            val engine = unconfinedMockEngine {
                requestCount++
                if (requestCount == 1) {
                    respond(
                        content = "slow down",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf("Retry-After", "1"),
                    )
                } else {
                    respond("""{"token":"post-429-access"}""", HttpStatusCode.OK, headers = jsonResponseHeaders)
                }
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.Refreshed)
            assertThat(requestCount).isEqualTo(2)
            assertThat(store.persistedAccess()).isEqualTo("post-429-access")
        }

    @Test
    fun `a successful refresh persists the rotated refresh token from Set-Cookie`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = unconfinedMockEngine {
                respond(
                    content = """{"token":"rotated-access"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        "Content-Type" to listOf(ContentType.Application.Json.toString()),
                        "Set-Cookie" to listOf("refreshToken=rotated-refresh; HttpOnly; Path=/"),
                    ),
                )
            }
            val store = FakeTokenStore(refreshClientOf(engine), bareSeed())

            val result = store.refreshAccessToken()

            assertThat(result).isEqualTo(RefreshResult.Refreshed)
            assertThat(store.persistedAccess()).isEqualTo("rotated-access")
            // The backend rotates on every refresh; the new token must replace the old one on disk so
            // the next refresh doesn't send a now-dead token.
            assertThat(store.persistedRefresh()).isEqualTo("rotated-refresh")
        }
}
