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
) : Exception(message, cause)
