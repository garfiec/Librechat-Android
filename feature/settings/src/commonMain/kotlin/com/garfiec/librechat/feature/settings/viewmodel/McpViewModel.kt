package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.model.error.ServerErrorCode
import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerStatus
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.core.model.mcp.McpTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class McpUiState(
    val servers: List<McpServer> = emptyList(),
    val connectionStatus: Map<String, McpServerStatus> = emptyMap(),
    val tools: List<McpTool> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val reinitializingServers: Set<String> = emptySet(),
    val error: String? = null,
    val showServerDialog: Boolean = false,
    val editingServer: McpServer? = null,
    val showToolsSheet: Boolean = false,
    val toolsSheetServerName: String? = null,
    val successMessage: String? = null,
    /**
     * An MCP server that answered a reinitialize by asking the user to authorize it
     * (v0.8.8 `oauthRequired` + `oauthUrl`).
     *
     * Held as state rather than acted on immediately because the consent has to be the user's:
     * silently launching a browser off a "reconnect" tap would hand a third-party provider the
     * session without anyone having agreed to it.
     */
    val pendingOAuth: McpOAuthPrompt? = null,
    /**
     * The last save was refused with `OAUTH_SECRET_REENTRY_REQUIRED`.
     *
     * The stored client secret is bound to the authorization/token endpoint it was issued for, so
     * changing either endpoint invalidates it and every retry of the same body fails identically.
     * This is a prompt-for-input outcome, not a retryable failure: the dialog stays open and asks
     * for the secret again, because nothing the user can do from a generic "save failed" would
     * ever succeed.
     */
    val oauthSecretReentryRequired: Boolean = false,
)

/** A server waiting on the user to authorize it, with the provider URL to send them to. */
data class McpOAuthPrompt(
    val serverName: String,
    val authorizationUrl: String,
)

class McpViewModel(
    private val mcpRepository: McpRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(McpUiState())
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    init {
        loadServers()
        loadConnectionStatus()
        loadTools()
    }

    fun loadServers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.servers.isEmpty())
            when (val result = mcpRepository.listServers()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        servers = result.data,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = result.message ?: "Failed to load servers",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadConnectionStatus() {
        viewModelScope.launch {
            when (val result = mcpRepository.getConnectionStatus()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(connectionStatus = result.data)
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load connection status: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadTools() {
        viewModelScope.launch {
            when (val result = mcpRepository.getTools()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(tools = result.data)
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load tools: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadServers()
        loadConnectionStatus()
        loadTools()
    }

    fun showAddServerDialog() {
        _uiState.value = _uiState.value.copy(showServerDialog = true, editingServer = null)
    }

    fun showEditServerDialog(server: McpServer) {
        _uiState.value = _uiState.value.copy(showServerDialog = true, editingServer = server)
    }

    fun dismissServerDialog() {
        _uiState.value = _uiState.value.copy(showServerDialog = false, editingServer = null)
    }

    fun saveServer(
        name: String,
        description: String? = null,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig? = null,
        oauth: McpOAuthConfig? = null,
    ) {
        viewModelScope.launch {
            // An edit addresses the stored server (PATCH), a new one does not (POST). Only the
            // update route re-binds the OAuth client secret to the endpoints being saved, so
            // sending an edit as a create skips that check and collides with the existing name.
            val editing = _uiState.value.editingServer?.name
            _uiState.value = _uiState.value.copy(oauthSecretReentryRequired = false)
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
                    dismissServerDialog()
                    loadServers()
                    loadConnectionStatus()
                }
                is Result.Error -> {
                    // The stored OAuth client secret is bound to the endpoints it was issued for,
                    // so editing authorization_url or token_url invalidates it and the write keeps
                    // failing until the secret is re-entered. Retrying the same body never works,
                    // which is exactly what a generic save error invites the user to do.
                    val exception = result.exception as? ApiException
                    val reentry = exception?.statusCode == HTTP_BAD_REQUEST &&
                        ServerErrorCode.from(exception.body) == ServerErrorCode.OAUTH_SECRET_REENTRY_REQUIRED
                    _uiState.value = _uiState.value.copy(
                        oauthSecretReentryRequired = reentry,
                        error = if (reentry) {
                            "This server's OAuth endpoints changed, so the saved client secret " +
                                "no longer applies. Enter the client secret again to save."
                        } else {
                            result.message ?: "Failed to save server"
                        },
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun deleteServer(serverName: String) {
        viewModelScope.launch {
            when (val result = mcpRepository.deleteServer(serverName)) {
                is Result.Success -> {
                    loadServers()
                    loadConnectionStatus()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to delete server",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun reinitializeServer(serverName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                reinitializingServers = _uiState.value.reinitializingServers + serverName,
            )
            when (val result = mcpRepository.reinitialize(serverName)) {
                is Result.Success -> {
                    val response = result.data
                    // An oauthRequired ack is NOT a connection: the server is telling us it cannot
                    // proceed until the user authorizes it. Reporting it as "initialized
                    // successfully" leaves the user staring at a server that never connects.
                    val oauthUrl = response.oauthUrl?.takeIf { response.oauthRequired == true }
                    val message = when {
                        oauthUrl != null -> null
                        response.connectionDeferred == true -> DEFERRED_MARKER
                        else -> response.message ?: if (response.success) {
                            "Server initialized successfully"
                        } else {
                            "Failed to initialize server"
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        reinitializingServers = _uiState.value.reinitializingServers - serverName,
                        successMessage = message,
                        pendingOAuth = oauthUrl?.let { McpOAuthPrompt(serverName, it) },
                    )
                    loadServers()
                    loadConnectionStatus()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        reinitializingServers = _uiState.value.reinitializingServers - serverName,
                        error = result.message ?: "Failed to reinitialize server",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /** Dismisses the consent prompt without authorizing. The server stays unconnected. */
    fun dismissOAuthPrompt() {
        _uiState.value = _uiState.value.copy(pendingOAuth = null)
    }

    /**
     * The user agreed: clear the prompt so returning from the browser does not find it still up.
     *
     * The connection is not re-checked here — the authorization happens out of process and can
     * take as long as the provider takes, so the user reconnects when they are back.
     */
    fun onOAuthLaunched() {
        _uiState.value = _uiState.value.copy(pendingOAuth = null)
    }

    fun showToolsSheet(serverName: String? = null) {
        _uiState.value = _uiState.value.copy(
            showToolsSheet = true,
            toolsSheetServerName = serverName,
        )
    }

    fun dismissToolsSheet() {
        _uiState.value = _uiState.value.copy(
            showToolsSheet = false,
            toolsSheetServerName = null,
        )
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    companion object {
        /**
         * Sentinel the screen swaps for a localized string.
         *
         * The VM has no access to compose resources, and a deferred connection is the one
         * reinitialize outcome whose wording is ours rather than the server's.
         */
        const val DEFERRED_MARKER = "mcp_connection_deferred"

        /** The MCP write routes report a rejected OAuth secret binding with 400. */
        private const val HTTP_BAD_REQUEST = 400
    }
}
