package com.garfiec.librechat.feature.settings.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.model.error.ServerErrorCode
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
            // Which server the dialog was opened on decides the route, not the shape of the body:
            // an edit is a PATCH against the stored identifier. See McpRepository.updateServer.
            val editing = stateHandle.state.editingMcpServer?.name
            stateHandle.update { copy(mcpOAuthSecretReentryRequired = false) }
            val result = if (editing != null) {
                mcpRepository.updateServer(
                    serverName = editing,
                    name = name,
                    description = description,
                    url = url,
                    type = type,
                    apiKey = apiKey,
                    oauth = oauth,
                )
            } else {
                mcpRepository.createServer(
                    name = name,
                    description = description,
                    url = url,
                    type = type,
                    apiKey = apiKey,
                    oauth = oauth,
                )
            }
            when (result) {
                is Result.Success -> {
                    dismissMcpServerDialog()
                    loadMcpServers()
                }
                is Result.Error -> {
                    // Second of the two MCP save paths (the other is McpViewModel, behind the
                    // standalone MCP screen). Both reach the same update route, so both must
                    // prompt for the secret rather than report a failure — see
                    // SettingsUiState.mcpOAuthSecretReentryRequired.
                    val exception = result.exception as? ApiException
                    val reentry = exception?.statusCode == HTTP_BAD_REQUEST &&
                        ServerErrorCode.from(exception.body) == ServerErrorCode.OAUTH_SECRET_REENTRY_REQUIRED
                    stateHandle.update {
                        copy(
                            mcpOAuthSecretReentryRequired = reentry,
                            error = if (reentry) {
                                "This server's OAuth endpoints changed, so the saved client " +
                                    "secret no longer applies. Enter the client secret again to save."
                            } else {
                                result.message ?: "Failed to save MCP server"
                            },
                        )
                    }
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

/** The MCP write routes report a rejected OAuth secret binding with 400. */
private const val HTTP_BAD_REQUEST = 400
