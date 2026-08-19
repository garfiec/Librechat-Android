package com.garfiec.librechat.core.model.mcp

import kotlinx.serialization.Serializable

/**
 * Response from GET /api/mcp/connection/status.
 * Backend returns: { success: true, connectionStatus: { "serverName": { connectionState, requiresOAuth, error? } } }
 */
@Serializable
data class McpConnectionStatusResponse(
    val success: Boolean = false,
    val connectionStatus: Map<String, McpServerStatus> = emptyMap(),
    /**
     * Server-configured OAuth completion window in ms (`MCP_OAUTH_HANDLING_TIMEOUT`), so a client
     * waiting on a flow can give up on the same schedule the server does instead of guessing.
     */
    val oauthTimeout: Long? = null,
)

@Serializable
data class McpServerStatus(
    val connectionState: String = "disconnected",
    val requiresOAuth: Boolean = false,
    val error: String? = null,
    /**
     * Where the server is in authorizing this connection:
     * `not_required` | `authorizing` | `authorized` | `needs_authorization` | `error`.
     * See [McpAuthorizationStates]. This is the only signal separating "a flow is already
     * running" from "start one" — a distinction [connectionState] cannot express.
     */
    val authorizationState: String? = null,
) {
    val isConnected: Boolean get() = connectionState == "connected"
}
