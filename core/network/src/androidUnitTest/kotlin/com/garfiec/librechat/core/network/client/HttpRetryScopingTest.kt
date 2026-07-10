package com.garfiec.librechat.core.network.client

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Locks the retry-scoping invariant: the shared [configureRetryPolicy] must replay only
 * side-effect-free methods, so a retried chat POST / upload can never mint a duplicate server-side
 * job. Exercises the real production policy on a minimal client (the seam both `create()` installs).
 */
class HttpRetryScopingTest {

    // maxRetries = 2 → 1 initial attempt + 2 retries = 3 total for a retry-safe method.
    private companion object {
        const val SAFE_ATTEMPTS = 3
        const val UNSAFE_ATTEMPTS = 1
    }

    private fun countingClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(HttpRequestRetry) { configureRetryPolicy() }
    }

    @Test
    fun `retries GET on 5xx but not POST or DELETE`() = runTest {
        val attempts = mutableMapOf<String, Int>()
        val engine = MockEngine { request ->
            attempts.merge(request.method.value, 1, Int::plus)
            respond("boom", HttpStatusCode.InternalServerError)
        }
        val client = countingClient(engine)

        client.get("https://example.com/api/data")
        client.post("https://example.com/api/agents/chat/x")
        client.delete("https://example.com/api/files/f1")

        assertThat(attempts["GET"]).isEqualTo(SAFE_ATTEMPTS)
        assertThat(attempts["POST"]).isEqualTo(UNSAFE_ATTEMPTS)
        assertThat(attempts["DELETE"]).isEqualTo(UNSAFE_ATTEMPTS)
    }

    @Test
    fun `retries GET on exception but not POST or DELETE`() = runTest {
        val attempts = mutableMapOf<String, Int>()
        val engine = MockEngine {
            attempts.merge(it.method.value, 1, Int::plus)
            throw IOException("connection reset")
        }
        val client = countingClient(engine)

        // The call ultimately throws once retries are exhausted (or immediately for unsafe methods).
        runCatching { client.get("https://example.com/api/data") }
        runCatching { client.post("https://example.com/api/agents/chat/x") }
        runCatching { client.delete("https://example.com/api/files/f1") }

        assertThat(attempts["GET"]).isEqualTo(SAFE_ATTEMPTS)
        assertThat(attempts["POST"]).isEqualTo(UNSAFE_ATTEMPTS)
        assertThat(attempts["DELETE"]).isEqualTo(UNSAFE_ATTEMPTS)
    }
}
