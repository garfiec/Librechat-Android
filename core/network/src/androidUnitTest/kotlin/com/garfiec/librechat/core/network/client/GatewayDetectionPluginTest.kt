package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.result.AccessGatewayException
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Detection of an access gateway answering instead of the server (issue #287).
 *
 * The case that matters is the one no status check can see: the gateway 302s, Ktor follows it, and
 * the sign-in page comes back as a perfectly valid **200 `text/html`**. Nothing fails until a typed
 * call tries to decode it — and Ktor's decode error quotes the request URL, so a Cloudflare Access
 * `meta=` JWT would render in an error banner.
 */
class GatewayDetectionPluginTest {

    private companion object {
        const val SERVER = "https://chat.example.com"
        const val ACCESS_LOGIN =
            "https://team.cloudflareaccess.com/cdn-cgi/access/login/chat.example.com?meta=eyJhbGci"
        const val CF_CHALLENGE =
            "Cloudflare-Access resource_metadata=\"https://chat.example.com/.well-known\""
    }

    private class FakeServerUrlProvider(private val baseUrl: String) : ServerUrlProvider {
        override fun getBaseUrl(): String = baseUrl
    }

    private fun clientWith(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(GatewayDetectionPlugin) {
            serverUrlProvider = FakeServerUrlProvider(SERVER)
        }
    }

    /**
     * Ktor follows the 302 to a 200 sign-in page, so without this plugin the call "succeeds" and
     * blows up later with a URL-quoting decode error.
     */
    @Test
    fun `a Cloudflare Access rejection surfaces as a typed gateway error`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "chat.example.com") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location to listOf(ACCESS_LOGIN),
                        HttpHeaders.WWWAuthenticate to listOf(CF_CHALLENGE),
                        HttpHeaders.ContentType to listOf("text/html; charset=UTF-8"),
                    ),
                )
            } else {
                respond("<html>sign in</html>", HttpStatusCode.OK)
            }
        }

        val failure = assertFailsWith<AccessGatewayException> {
            clientWith(engine).get("$SERVER/api/config")
        }

        assertThat(failure.serverUrl).isEqualTo(SERVER)
        // The gateway's URL and JWT must not ride along on the exception the UI classifies.
        assertThat(failure.message.orEmpty()).doesNotContain("cloudflareaccess")
        assertThat(failure.message.orEmpty()).doesNotContain("meta=")
    }

    /** An ordinary successful call must be untouched — this interceptor runs on every response. */
    @Test
    fun `a normal response passes through`() = runTest {
        val engine = MockEngine { respond("""{"ok":true}""", HttpStatusCode.OK) }

        assertThat(clientWith(engine).get("$SERVER/api/config").bodyAsText())
            .isEqualTo("""{"ok":true}""")
    }

    /**
     * The server legitimately redirects downloads off-authority to object storage. Keying on the
     * redirect alone would break every file download, which is why detection keys on the
     * `WWW-Authenticate` scheme instead.
     */
    @Test
    fun `an off-authority download redirect is not mistaken for a gateway`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "chat.example.com") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location to listOf("https://objectstore.example.net/file.png"),
                    ),
                )
            } else {
                respond("binary-image-bytes", HttpStatusCode.OK)
            }
        }

        assertThat(clientWith(engine).get("$SERVER/api/files/download/1").bodyAsText())
            .isEqualTo("binary-image-bytes")
    }

    /** A 401 carrying a normal auth challenge is the app's own concern, not a gateway. */
    @Test
    fun `an ordinary WWW-Authenticate challenge is not a gateway`() = runTest {
        val engine = MockEngine {
            respond(
                content = "unauthorized",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.WWWAuthenticate to listOf("Bearer realm=\"api\"")),
            )
        }

        assertThat(clientWith(engine).get("$SERVER/api/config").status)
            .isEqualTo(HttpStatusCode.Unauthorized)
    }
}
