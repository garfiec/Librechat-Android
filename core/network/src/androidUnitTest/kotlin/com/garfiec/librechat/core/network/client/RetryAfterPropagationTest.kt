package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.network.di.NetworkGraphTestFakes
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `Retry-After` has to survive onto [ApiException], because by the time a caller holds a
 * `Result.Error` the response is gone. Without this the only sane back-off for a rate-limited caller
 * is a guess, and a guess that is too short is what gets a client throttled harder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryAfterPropagationTest {

    private companion object {
        const val URL = "https://chat.example.com/api/messages/conv-1"
    }

    private fun client(status: HttpStatusCode, retryAfter: String?) =
        NetworkGraphTestFakes.mainClient(
            object : HttpClientEngineFactory<MockEngineConfig> {
                override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                    MockEngine(
                        MockEngineConfig().apply(block).apply {
                            dispatcher = Dispatchers.Unconfined
                            addHandler {
                                respond(
                                    content = "{}",
                                    status = status,
                                    headers = retryAfter
                                        ?.let { headersOf(HttpHeaders.RetryAfter, it) }
                                        ?: headersOf(),
                                )
                            }
                        },
                    )
            },
            RequestActivityTracker(),
        )

    private suspend fun apiExceptionFrom(status: HttpStatusCode, retryAfter: String?): ApiException =
        runCatching { client(status, retryAfter).get(URL) }
            .exceptionOrNull() as ApiException

    @Test
    fun `a rate limit carries its retry-after`() = runTest {
        val error = apiExceptionFrom(HttpStatusCode.TooManyRequests, "30")

        assertThat(error.statusCode).isEqualTo(429)
        assertThat(error.retryAfterSeconds).isEqualTo(30L)
    }

    @Test
    fun `a response without the header carries no guidance`() = runTest {
        assertThat(apiExceptionFrom(HttpStatusCode.TooManyRequests, null).retryAfterSeconds).isNull()
    }

    /**
     * The HTTP-date form parses to null rather than to zero. Zero would read as "retry immediately",
     * turning the one response that asks a client to slow down into the one that speeds it up.
     */
    @Test
    fun `the http-date form is treated as no guidance rather than as zero`() = runTest {
        val error = apiExceptionFrom(HttpStatusCode.TooManyRequests, "Wed, 21 Oct 2026 07:28:00 GMT")

        assertThat(error.retryAfterSeconds).isNull()
    }
}
