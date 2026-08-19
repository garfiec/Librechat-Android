package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpReinitializeResponse
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerStatus
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.core.model.mcp.McpTool

/** Repository for MCP server management. Uses serverName as unique identifier for operations. */
interface McpRepository {
    suspend fun listServers(): Result<List<McpServer>>
    suspend fun createServer(
        name: String,
        description: String? = null,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig? = null,
        oauth: McpOAuthConfig? = null,
    ): Result<McpServer>

    /**
     * Edits an existing server. Distinct from [createServer] on the wire (`PATCH` vs `POST`) and
     * server-side: only the update route re-validates the stored OAuth client secret against the
     * endpoints being saved, so an edit sent as a create both skips that check and collides with
     * the name it is meant to be editing.
     */
    suspend fun updateServer(
        serverName: String,
        name: String,
        description: String? = null,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig? = null,
        oauth: McpOAuthConfig? = null,
    ): Result<McpServer>
    suspend fun deleteServer(serverName: String): Result<Unit>
    suspend fun reinitialize(serverName: String): Result<McpReinitializeResponse>
    suspend fun getTools(): Result<List<McpTool>>
    suspend fun getConnectionStatus(): Result<Map<String, McpServerStatus>>
}
