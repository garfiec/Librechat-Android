package com.garfiec.librechat.feature.auth.viewmodel

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result

private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * Returns the server's throttling message when this error is a 429, else null.
 *
 * Auth submission endpoints (password reset, email verification) run their own
 * per-submission limiters with a retry window in the body text, which is strictly more
 * useful than the screen's generic "the link may have expired" copy — that reads as a
 * dead end when the real fix is to wait. Every other failure keeps the generic message,
 * so a server that leaks detail in an error body can't retarget the screen's copy.
 */
internal fun Result.Error.rateLimitMessageOrNull(): String? {
    val apiException = exception as? ApiException ?: return null
    if (apiException.statusCode != HTTP_TOO_MANY_REQUESTS) return null
    return apiException.message.takeIf { it.isNotBlank() }
}
