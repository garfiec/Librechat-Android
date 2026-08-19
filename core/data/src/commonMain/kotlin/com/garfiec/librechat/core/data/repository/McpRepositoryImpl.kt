package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpReinitializeResponse
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerStatus
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.core.model.mcp.McpTool
import com.garfiec.librechat.core.network.api.McpApi

class McpRepositoryImpl(
    private val mcpApi: McpApi,
) : McpRepository {

    override suspend fun listServers(): Result<List<McpServer>> =
        safeApiCall { mcpApi.listServers() }

    override suspend fun createServer(
        name: String,
        description: String?,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig?,
        oauth: McpOAuthConfig?,
    ): Result<McpServer> =
        safeApiCall { mcpApi.createServer(name, description, url, type, apiKey, oauth) }

    override suspend fun updateServer(
        serverName: String,
        name: String,
        description: String?,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig?,
        oauth: McpOAuthConfig?,
    ): Result<McpServer> =
        safeApiCall { mcpApi.updateServer(serverName, name, description, url, type, apiKey, oauth) }

    override suspend fun deleteServer(serverName: String): Result<Unit> =
        safeApiCall { mcpApi.deleteServer(serverName) }

    override suspend fun reinitialize(serverName: String): Result<McpReinitializeResponse> =
        safeApiCall { mcpApi.reinitialize(serverName) }

    override suspend fun getTools(): Result<List<McpTool>> =
        safeApiCall { mcpApi.getTools() }

    override suspend fun getConnectionStatus(): Result<Map<String, McpServerStatus>> =
        safeApiCall { mcpApi.getConnectionStatus() }
}
