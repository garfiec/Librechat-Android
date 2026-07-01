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

@OptIn(ExperimentalCoroutinesApi::class)
class CommonTokenDataStoreConcurrencyTest {

    /**
     * Test double that records every platform storage mutation. It deliberately
     * mirrors `TokenDataStore` semantics (read-through reads, write-through
     * writes) without pulling in `EncryptedSharedPreferences`.
     */
    private class FakeTokenDataStore(
        refreshClient: Lazy<HttpClient>,
        initialAccess: String? = "initial-access",
        initialRefresh: String? = "initial-refresh",
    ) : CommonTokenDataStore(refreshClient) {

        private val store = mutableMapOf<String, String>()

        val writeLog = mutableListOf<Pair<String, String>>()
        var removeCount = 0

        // No account is resolved in these tests, so the store uses the bare token keys.
        fun persistedAccess(): String? = store[KEY_ACCESS_TOKEN]
        fun persistedRefresh(): String? = store[KEY_REFRESH_TOKEN]

        init {
            initialAccess?.let { store[KEY_ACCESS_TOKEN] = it }
            initialRefresh?.let { store[KEY_REFRESH_TOKEN] = it }
            initializeTokenCache()
        }

        override fun readValue(key: String): String? = store[key]

        override fun writeValue(key: String, value: String) {
            store[key] = value
            // setTokens writes access then refresh; log the pair once, keyed off the access write.
            if (key == KEY_ACCESS_TOKEN) writeLog += value to (store[KEY_REFRESH_TOKEN] ?: "")
        }

        override fun writeValues(values: Map<String, String>) {
            values.forEach { (key, value) -> writeValue(key, value) }
        }

        override fun removeValue(key: String) {
            store.remove(key)
            // clearTokens removes access then refresh; count the logical clear off the access removal.
            if (key == KEY_ACCESS_TOKEN) removeCount++
        }

        override fun onKeystoreCorruption() = Unit
    }

    private fun mockRefreshClient(engine: MockEngine): Lazy<HttpClient> = lazy {
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
            defaultRequest {
                url("https://chat.example.com")
                contentType(ContentType.Application.Json)
            }
        }
    }

    private fun refreshResponseBody(token: String): String =
        """{"token":"$token"}"""

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    @Test
    fun `clearTokens blocks until in-flight refresh releases the mutex`() =
        runTest(UnconfinedTestDispatcher()) {
            val release = CompletableDeferred<Unit>()
            val engine = MockEngine {
                release.await()
                respond(
                    content = refreshResponseBody("new-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val store = FakeTokenDataStore(mockRefreshClient(engine))

            val refreshJob = async { store.refreshAccessToken() }
            // Give the refresh coroutine a chance to acquire the mutex and
            // suspend inside the MockEngine's release.await() before we start
            // the concurrent clear.
            yield()
            advanceUntilIdle()

            val clearJob: Job = launch { store.clearTokens() }
            yield()
            advanceUntilIdle()

            // Clear must still be suspended on the mutex while refresh waits on HTTP.
            assertThat(refreshJob.isCompleted).isFalse()
            assertThat(clearJob.isCompleted).isFalse()
            assertThat(store.removeCount).isEqualTo(0)

            release.complete(Unit)
            advanceUntilIdle()

            // Refresh completes and transiently writes the new token, but the
            // queued clearTokens immediately wipes it. Final state: logged out.
            assertThat(refreshJob.await()).isTrue()
            assertThat(clearJob.isCompleted).isTrue()
            assertThat(store.persistedAccess()).isNull()
            assertThat(store.persistedRefresh()).isNull()
            assertThat(store.removeCount).isEqualTo(1)
        }

    @Test
    fun `refresh does not resurrect a logout that raced its network call`() =
        runTest(UnconfinedTestDispatcher()) {
            val release = CompletableDeferred<Unit>()
            val engine = MockEngine {
                release.await()
                respond(
                    content = refreshResponseBody("resurrected-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val store = FakeTokenDataStore(mockRefreshClient(engine))

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
            val engine = MockEngine {
                respond(
                    content = refreshResponseBody("fresh-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val store = FakeTokenDataStore(mockRefreshClient(engine))

            val result = store.refreshAccessToken()

            assertThat(result).isTrue()
            assertThat(store.writeLog).hasSize(1)
            assertThat(store.writeLog.single().first).isEqualTo("fresh-access")
            assertThat(store.persistedAccess()).isEqualTo("fresh-access")
        }

    @Test
    fun `sequential clearTokens and refreshAccessToken do not deadlock`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MockEngine {
                respond(
                    content = refreshResponseBody("seq-access"),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val store = FakeTokenDataStore(mockRefreshClient(engine))

            store.clearTokens()
            assertThat(store.removeCount).isEqualTo(1)

            // Without a refresh token in storage the refresh should short-circuit
            // false without touching the network — and without deadlocking on a
            // mutex that clearTokens already released.
            val result = store.refreshAccessToken()
            assertThat(result).isFalse()
        }
}
