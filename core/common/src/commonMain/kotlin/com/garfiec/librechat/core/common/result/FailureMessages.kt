package com.garfiec.librechat.core.common.result

/**
 * Thrown when an access gateway in front of the server answered instead of the server itself
 * (issue #287). Distinct from a transport failure: the network is fine and the server is up — the
 * request never got past the gateway, so the fix is a credential in Settings → Account → Server
 * connection, not a retry.
 */
class AccessGatewayException(
    val serverUrl: String? = null,
    cause: Throwable? = null,
) : Exception("Access gateway rejected the request", cause)

/**
 * What went wrong, coarsely. Deliberately broad: it exists so the UI can decide whether to offer a
 * retry (and, later, localize) without ever reading an exception message.
 */
enum class FailureKind {
    /** No usable connection — DNS, socket, offline. */
    Network,

    /** The request was sent but nothing came back in time. */
    Timeout,

    /** A response arrived but wasn't what the API promised: HTML, truncated JSON, wrong shape. */
    MalformedResponse,

    /** An access gateway intercepted the request. See [AccessGatewayException]. */
    AccessGateway,

    /** The server answered with an error status and (usually) an explanation of its own. */
    Server,

    /** Anything unrecognised. */
    Unknown,
}

/**
 * User-facing failure text.
 *
 * These are hardcoded English, matching the existing convention in `LibreChatHttpClient`'s status
 * fallbacks — `:core:common` has no `composeResources`. [FailureKind] rides along on
 * [Result.Error] so a UI layer can localize by kind later without changing any of this.
 */
internal object FailureMessages {
    const val NETWORK = "Network unavailable. Check your connection."
    const val TIMEOUT = "The server took too long to respond."
    const val MALFORMED = "Unexpected response from the server."
    const val GATEWAY = "Your server's access gateway rejected this request. " +
        "Check Server connection in Settings."
    const val UNKNOWN = "Something went wrong. Please try again."
}

/**
 * Longest server-authored message we will render. Past this it is not a sentence aimed at a person.
 */
private const val MAX_SERVER_MESSAGE_LENGTH = 200

/**
 * Above this length a message with no spaces at all is a token, a hash or a blob — never prose.
 */
private const val MAX_UNSPACED_LENGTH = 40

/**
 * Whether server-authored text is safe to put in front of a user.
 *
 * The server's own wording is genuinely useful ("Invalid credentials", "Email already registered"),
 * so it is kept — but only when it actually looks like a sentence. A gateway's HTML login page, a
 * redirect URL with a JWT in the query string, or a raw token would otherwise be rendered verbatim
 * in an error banner (issue #287).
 */
internal fun looksLikeUserMessage(message: String): Boolean {
    val trimmed = message.trim()
    return when {
        trimmed.isEmpty() || trimmed.length > MAX_SERVER_MESSAGE_LENGTH -> false
        // Markup, URLs and multi-line payloads are documents, not messages.
        trimmed.any { it == '<' || it == '>' || it == '\n' || it == '\r' } -> false
        trimmed.contains("://") -> false
        // A long unbroken run with no spaces is a token/hash, not a sentence.
        trimmed.length > MAX_UNSPACED_LENGTH && !trimmed.contains(' ') -> false
        else -> true
    }
}

/**
 * Classifies a failure by walking the cause chain.
 *
 * Matched on exception *type names* rather than the types themselves because `:core:common` sits
 * below `:core:network` and cannot see Ktor or kotlinx-serialization. That is a deliberate trade: a
 * renamed upstream exception degrades one category to [FailureKind.Unknown] — still a safe,
 * non-leaking message — whereas depending on Ktor here would invert the module graph.
 */
internal fun classifyFailure(throwable: Throwable): FailureKind {
    var current: Throwable? = throwable
    // Bounded: a malformed cause chain must not spin forever.
    repeat(TRAVERSAL_LIMIT) {
        val error = current ?: return FailureKind.Unknown
        when (error) {
            is AccessGatewayException -> return FailureKind.AccessGateway
            is ApiException -> return FailureKind.Server
            else -> Unit
        }
        val name = error::class.simpleName.orEmpty()
        when {
            name.contains("Timeout") -> return FailureKind.Timeout
            name.contains("UnresolvedAddress") ||
                name.contains("UnknownHost") ||
                name.contains("ConnectException") ||
                name.contains("SocketException") ||
                name.contains("IOException") -> return FailureKind.Network
            name.contains("NoTransformationFound") ||
                name.contains("Serialization") ||
                name.contains("JsonConvert") ||
                name.contains("JsonDecoding") ||
                name.contains("ContentConvert") -> return FailureKind.MalformedResponse
        }
        current = error.cause?.takeIf { it !== error }
    }
    return FailureKind.Unknown
}

private const val TRAVERSAL_LIMIT = 8

/** The safe, user-facing text for a [FailureKind]. */
internal fun FailureKind.defaultMessage(): String = when (this) {
    FailureKind.Network -> FailureMessages.NETWORK
    FailureKind.Timeout -> FailureMessages.TIMEOUT
    FailureKind.MalformedResponse -> FailureMessages.MALFORMED
    FailureKind.AccessGateway -> FailureMessages.GATEWAY
    FailureKind.Server, FailureKind.Unknown -> FailureMessages.UNKNOWN
}
