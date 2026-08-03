package com.garfiec.librechat.feature.settings.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.feature.settings.viewmodel.SettingsStateHandle
import kotlinx.coroutines.launch

/**
 * Handles MCP server management, connection status, and reinitialization.
 */
class McpServerDelegate(
    private val stateHandle: SettingsStateHandle,
    private val mcpRepository: McpRepository,
) {

    fun loadMcpServers() {
        stateHandle.scope.launch {
            when (val result = mcpRepository.listServers()) {
                is Result.Success -> {
                    stateHandle.update { copy(mcpServers = result.data, mcpError = null) }
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load MCP servers: ${result.message}" }
                    stateHandle.update { copy(mcpError = result.message ?: "MCP not available on this server") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
        stateHandle.scope.launch {
            when (val result = mcpRepository.getConnectionStatus()) {
                is Result.Success -> {
                    stateHandle.update { copy(mcpConnectionStatus = result.data) }
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load MCP connection status: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun showAddMcpServerDialog() {
        stateHandle.update { copy(showMcpServerDialog = true, editingMcpServer = null) }
    }

    fun showEditMcpServerDialog(server: McpServer) {
        stateHandle.update { copy(showMcpServerDialog = true, editingMcpServer = server) }
    }

    fun dismissMcpServerDialog() {
        stateHandle.update { copy(showMcpServerDialog = false, editingMcpServer = null) }
    }

    fun saveMcpServer(
        name: String,
        description: String? = null,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig? = null,
        oauth: McpOAuthConfig? = null,
    ) {
        stateHandle.scope.launch {
            val result = mcpRepository.createServer(
                name = name,
                description = description,
                url = url,
                type = type,
                apiKey = apiKey,
                oauth = oauth,
            )
            when (result) {
                is Result.Success -> {
                    dismissMcpServerDialog()
                    loadMcpServers()
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to save MCP server") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun deleteMcpServer(serverName: String) {
        stateHandle.scope.launch {
            when (val result = mcpRepository.deleteServer(serverName)) {
                is Result.Success -> loadMcpServers()
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to delete MCP server") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun reinitializeMcpServer(serverName: String) {
        stateHandle.scope.launch {
            stateHandle.update { copy(mcpReinitializingServers = mcpReinitializingServers + serverName) }
            when (val result = mcpRepository.reinitialize(serverName)) {
                is Result.Success -> {
                    val response = result.data
                    // Same rule as the dedicated MCP screen: an oauthRequired ack means the
                    // server is waiting on the user, not that it connected. This compact section
                    // has no room for a consent dialog, so it points at the screen that does.
                    val needsOAuth = response.oauthRequired == true && !response.oauthUrl.isNullOrBlank()
                    stateHandle.update {
                        copy(
                            mcpReinitializingServers = mcpReinitializingServers - serverName,
                            mcpReinitializeMessage = when {
                                needsOAuth -> "$serverName needs you to sign in — open MCP Servers to authorize it"
                                response.connectionDeferred == true ->
                                    "Connecting in the background"
                                else -> "Server reinitialized successfully"
                            },
                        )
                    }
                    loadMcpServers()
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            mcpReinitializingServers = mcpReinitializingServers - serverName,
                            mcpReinitializeMessage = result.message ?: "Failed to reinitialize server",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissMcpReinitializeMessage() {
        stateHandle.update { copy(mcpReinitializeMessage = null) }
    }
}
