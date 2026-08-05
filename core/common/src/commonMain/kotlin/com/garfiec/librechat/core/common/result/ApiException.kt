package com.garfiec.librechat.core.common.result

/**
 * Exception thrown when the server returns a non-2xx HTTP response.
 * The [message] field contains a user-friendly error extracted from the response body.
 *
 * [body] is the raw response body text when available. The HTTP client reads it
 * to extract [message]; it's retained here so callers that need a typed error
 * payload (e.g. a 409 conflict carrying the authoritative resource, or a 400
 * carrying a structured `issues` array) can decode it without a second request.
 */
class ApiException(
    val statusCode: Int,
    override val message: String,
    val isBanned: Boolean = false,
    val body: String? = null,
    cause: Throwable? = null,
    /**
     * True when [message] came out of the response body rather than being written by this app.
     *
     * Server-authored text is worth showing ("Invalid credentials" beats "Something went wrong"),
     * but it is arbitrary and unbounded — a gateway can put an HTML login page there. Only text
     * flagged here is screened before display; the app's own wording is trusted as-is, so a long
     * but deliberate message can never be silently swallowed by that screen.
     */
    val serverAuthored: Boolean = false,
    /**
     * `Retry-After` in seconds, when the server sent one — typically alongside a 429.
     *
     * Delta-seconds only; the HTTP-date form parses to null, matching the token-refresh parser this
     * mirrors. Callers that back off must therefore treat null as "no guidance", not as "retry now".
     *
     * Carried on the exception because the response is gone by the time a `Result.Error` reaches a
     * caller, and this is the one header a caller can act on rather than merely report.
     */
    val retryAfterSeconds: Long? = null,
) : Exception(message, cause)
