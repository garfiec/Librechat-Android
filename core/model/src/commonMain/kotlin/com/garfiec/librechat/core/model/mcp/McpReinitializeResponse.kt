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
)
