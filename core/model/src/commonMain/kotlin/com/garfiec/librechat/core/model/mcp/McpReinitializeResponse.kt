package com.garfiec.librechat.core.model.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpReinitializeResponse(
    val success: Boolean = false,
    val message: String? = null,
    val serverName: String? = null,
    val oauthRequired: Boolean? = null,
    val oauthUrl: String? = null,
    /**
     * True when the server accepted the reinitialize but is establishing the connection in the
     * background, so [success] does not yet mean the server is reachable. Callers should refresh
     * connection status rather than treat the ack as a completed connection.
     */
    val connectionDeferred: Boolean? = null,
    /**
     * Shared identifier for this OAuth attempt, for polling durable flow state through the
     * already-existing `GET /api/mcp/oauth/status/:flowId`. Present when [oauthRequired] is set.
     */
    val flowId: String? = null,
    /** Milliseconds left in the OAuth window before the server abandons the attempt. */
    val oauthTimeout: Long? = null,
    /**
     * Why the reinitialize did not connect, as a machine-readable reason:
     * `unreachable` | `missing_custom_user_vars` | `oauth_required` | `initialization_failed`.
     * See [McpFailureReasons]. An unknown value must degrade to the generic message.
     */
    val failureReason: String? = null,
    /** With `failureReason == missing_custom_user_vars`, the variables the user still owes. */
    val missingUserVars: List<String>? = null,
    /**
     * Where the server is in authorizing this connection:
     * `not_required` | `authorizing` | `authorized` | `needs_authorization` | `error`.
     * See [McpAuthorizationStates]. Notably this is the only thing that separates "a flow is
     * already running" from "start one" — a distinction the connection-state model cannot express.
     */
    val authorizationState: String? = null,
)

/** Wire values of [McpReinitializeResponse.failureReason]. */
object McpFailureReasons {
    const val UNREACHABLE = "unreachable"
    const val MISSING_CUSTOM_USER_VARS = "missing_custom_user_vars"
    const val OAUTH_REQUIRED = "oauth_required"
    const val INITIALIZATION_FAILED = "initialization_failed"
}

/** Wire values of [McpReinitializeResponse.authorizationState]. */
object McpAuthorizationStates {
    const val NOT_REQUIRED = "not_required"
    const val AUTHORIZING = "authorizing"
    const val AUTHORIZED = "authorized"
    const val NEEDS_AUTHORIZATION = "needs_authorization"
    const val ERROR = "error"
}
