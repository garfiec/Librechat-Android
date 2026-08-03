package com.garfiec.librechat.core.common.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What reaches the UI when a call fails.
 *
 * The rule these pin down: an exception's own message is never user-facing. Ktor builds its
 * messages out of the request URL, so passing one through renders an access gateway's redirect —
 * query string and `meta=` JWT included — verbatim in an error banner.
 */
class SafeErrorMessageTest {

    /** The exact shape that put a JWT on screen: Ktor's type-mismatch message quoting the URL. */
    private class NoTransformationFoundException(message: String) : Exception(message)

    private class HttpRequestTimeoutException : Exception("Request timeout has expired")

    private class IOException(message: String) : Exception(message)

    private val gatewayRedirectMessage =
        "Expected response body of the type 'class com.garfiec.librechat.core.model.User' " +
            "but was 'class io.ktor.utils.io.SourceByteReadChannel'\n" +
            "In response from `https://team.cloudflareaccess.com/cdn-cgi/access/login/" +
            "chat.example.com?kid=0000000000000000&meta=eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9`"

    @Test
    fun `the gateway redirect message never reaches the user`() {
        val error = NoTransformationFoundException(gatewayRedirectMessage).toSafeError()

        assertEquals(FailureMessages.MALFORMED, error.message)
        assertEquals(FailureKind.MalformedResponse, error.kind)
        // The specifics that must not be on screen.
        assertFalse(error.message.orEmpty().contains("cloudflareaccess"))
        assertFalse(error.message.orEmpty().contains("meta="))
        assertFalse(error.message.orEmpty().contains("eyJ"))
        // ...but they are still recoverable for a bug report.
        assertTrue(error.exception is NoTransformationFoundException)
    }

    @Test
    fun `transport failures map to their own category`() {
        assertEquals(FailureKind.Timeout, HttpRequestTimeoutException().toSafeError().kind)
        assertEquals(FailureMessages.TIMEOUT, HttpRequestTimeoutException().toSafeError().message)

        assertEquals(FailureKind.Network, IOException("Connection reset").toSafeError().kind)
        assertEquals(FailureMessages.NETWORK, IOException("Connection reset").toSafeError().message)
    }

    @Test
    fun `an access gateway rejection is its own actionable category`() {
        val error = AccessGatewayException().toSafeError()

        assertEquals(FailureKind.AccessGateway, error.kind)
        assertEquals(FailureMessages.GATEWAY, error.message)
    }

    /** The classifier walks the chain — Ktor wraps the interesting exception more often than not. */
    @Test
    fun `a wrapped cause is still classified`() {
        val wrapped = IllegalStateException("outer", HttpRequestTimeoutException())

        assertEquals(FailureKind.Timeout, wrapped.toSafeError().kind)
    }

    /** A self-referencing cause chain must not hang the classifier. */
    @Test
    fun `a cyclic cause chain terminates`() {
        val cyclic = object : Exception("loops") {
            override val cause: Throwable get() = this
        }

        assertEquals(FailureKind.Unknown, cyclic.toSafeError().kind)
    }

    @Test
    fun `useful server wording survives`() {
        listOf("Invalid credentials", "Email already registered", "Too many requests. Try later.")
            .forEach { serverText ->
                val error = ApiException(
                    statusCode = 400,
                    message = serverText,
                    serverAuthored = true,
                ).toSafeError()
                assertEquals(serverText, error.message)
                assertEquals(FailureKind.Server, error.kind)
            }
    }

    @Test
    fun `server text that is not a message falls back`() {
        val hostile = listOf(
            "<html><body>Sign in to continue</body></html>",
            "https://gateway.example.com/login?token=eyJhbGciOiJIUzI1NiJ9",
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6IjQ4MmYxNTFkMTA3NzE4OGU1ODc0N2VkODl9",
            "a".repeat(500),
            "line one\nline two",
        )
        hostile.forEach { body ->
            val error = ApiException(
                statusCode = 500,
                message = body,
                serverAuthored = true,
            ).toSafeError()
            assertEquals(FailureMessages.UNKNOWN, error.message, "should not render: $body")
        }
    }

    /**
     * The app's own wording is trusted outright — several deliberate in-app messages run close to
     * the screening length limit, and silently swallowing one would be worse than the leak this
     * screen exists to stop.
     */
    @Test
    fun `app-authored text is never screened, however long`() {
        val longAppMessage = "Server returned an unexpected response when starting the chat. " +
            "This usually indicates a backend version incompatibility — please check that the " +
            "server is running a supported LibreChat release, and that no proxy is rewriting it."
        val error = ApiException(statusCode = 200, message = longAppMessage).toSafeError()

        assertEquals(longAppMessage, error.message)
    }

    @Test
    fun `the message gate accepts prose and rejects payloads`() {
        assertTrue(looksLikeUserMessage("Invalid credentials"))
        assertTrue(looksLikeUserMessage("Access denied"))
        // Exactly at the unspaced limit, still fine.
        assertTrue(looksLikeUserMessage("a".repeat(40)))

        assertFalse(looksLikeUserMessage(""))
        assertFalse(looksLikeUserMessage("   "))
        assertFalse(looksLikeUserMessage("a".repeat(41)))
        assertFalse(looksLikeUserMessage("see <b>here</b>"))
        assertFalse(looksLikeUserMessage("go to https://example.com"))
    }
}
