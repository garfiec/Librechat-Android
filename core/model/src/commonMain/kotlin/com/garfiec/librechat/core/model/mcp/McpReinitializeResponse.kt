package com.garfiec.librechat.core.model.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpReinitializeResponse(
    val success: Boolean = false,
    val message: String? = null,
    val serverName: String? = null,
    val oauthRequired: Boolean? = null,
    val oauthUrl: String? = null,
)
