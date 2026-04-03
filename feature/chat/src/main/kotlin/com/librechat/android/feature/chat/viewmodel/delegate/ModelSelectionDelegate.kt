package com.librechat.android.feature.chat.viewmodel.delegate

import com.librechat.android.core.common.EndpointConstants
import com.librechat.android.core.common.ToolConstants
import com.librechat.android.core.common.result.Result
import com.librechat.android.core.data.datastore.SettingsDataStore
import com.librechat.android.core.data.repository.AgentRepository
import com.librechat.android.core.data.repository.ConfigRepository
import com.librechat.android.core.data.repository.McpRepository
import com.librechat.android.core.model.EModelEndpoint
import com.librechat.android.core.model.mcp.McpServer
import com.librechat.android.core.model.request.AddedConversation
import com.librechat.android.core.ui.components.ModelParameters
import com.librechat.android.feature.chat.McpServerDisplayData
import com.librechat.android.feature.chat.viewmodel.ChatScreenState
import com.librechat.android.feature.chat.viewmodel.ChatStateHandle
import com.librechat.android.feature.chat.viewmodel.ComparisonState
import kotlinx.coroutines.launch
import timber.log.Timber

class ModelSelectionDelegate(
    private val stateHandle: ChatStateHandle,
    private val configRepository: ConfigRepository,
    private val agentRepository: AgentRepository,
    private val mcpRepository: McpRepository,
    private val settingsDataStore: SettingsDataStore,
) {

    // --- Model Comparison ---
    val primaryComparisonBuffer = StringBuilder()
    val secondaryComparisonBuffer = StringBuilder()

    /**
     * Cached last-used endpoint/model from DataStore. Loaded eagerly so
     * [refilterModels] can use them as a fallback without waiting for a
     * separate coroutine to complete. Null until the DataStore read finishes.
     */
    var cachedLastUsedEndpoint: String? = null
    var cachedLastUsedModel: String? = null

    /** True once the DataStore read for last-used model has completed. */
    var lastUsedModelLoaded = false

    /**
     * True once the conversation's model has been loaded from the server.
     * Used to prevent [refilterModels] from overwriting the conversation
     * model with a fallback before loadConversationModel has had a chance
     * to set it.
     */
    var conversationModelLoaded = false

    /**
     * Re-filters availableModels by endpointConfigs keys and validates the
     * currently selected model against the filtered list.
     */
    fun refilterModels(isNewConversation: Boolean) {
        val rawModels = configRepository.availableModels.value
        val endpointCfgs = configRepository.endpointConfigs.value
        val filtered = if (endpointCfgs.isEmpty()) {
            rawModels.filterValues { it.isNotEmpty() }
        } else {
            rawModels.filterKeys { it in endpointCfgs }
                .filterValues { it.isNotEmpty() }
        }
        stateHandle.update { copy(availableModels = filtered) }

        // Don't validate against an empty models list — models haven't
        // loaded yet. When they arrive this method will be called again.
        if (filtered.isEmpty()) return

        // For existing conversations, don't apply fallbacks until the
        // conversation model has been loaded.
        if (!isNewConversation && !conversationModelLoaded) return

        // Validate current selection
        val currentEndpoint = stateHandle.state.selectedEndpoint
        val currentModel = stateHandle.state.selectedModel
        val isAgentSelection = currentEndpoint == EModelEndpoint.AGENTS.toSerialName()
        val modelsForEndpoint = filtered[currentEndpoint]
        val selectionValid = isAgentSelection || (currentModel != null &&
            modelsForEndpoint != null &&
            currentModel in modelsForEndpoint)

        if (selectionValid) return

        // --- Fallback chain ---

        // Fallback 1: Try the last-used model from DataStore
        val lastEndpoint = cachedLastUsedEndpoint
        val lastModel = cachedLastUsedModel
        if (lastEndpoint != null && lastModel != null) {
            val lastIsAgent = lastEndpoint == EModelEndpoint.AGENTS.toSerialName()
            val lastModelsForEndpoint = filtered[lastEndpoint]
            if (lastIsAgent || (lastModelsForEndpoint != null && lastModel in lastModelsForEndpoint)) {
                stateHandle.update {
                    copy(
                        selectedEndpoint = lastEndpoint,
                        selectedModel = lastModel,
                    )
                }
                return
            }
        }

        // Fallback 2: First available model from any endpoint
        val firstEndpoint = filtered.entries.firstOrNull()
        if (firstEndpoint != null) {
            val fallbackModel = firstEndpoint.value.firstOrNull()
            stateHandle.update {
                copy(
                    selectedEndpoint = firstEndpoint.key,
                    selectedModel = fallbackModel,
                )
            }
            // Persist fallback so the next new chat starts with a valid model
            if (fallbackModel != null) {
                stateHandle.scope.launch {
                    settingsDataStore.setLastUsedModel(firstEndpoint.key, fallbackModel)
                }
            }
        }
    }

    fun onModelSelected(endpoint: String, model: String) {
        stateHandle.update {
            copy(
                selectedEndpoint = endpoint,
                selectedModel = model,
            )
        }
        // Keep cached values in sync so refilterModels uses the latest choice
        cachedLastUsedEndpoint = endpoint
        cachedLastUsedModel = model
        stateHandle.scope.launch {
            settingsDataStore.setLastUsedModel(endpoint, model)
        }
    }

    // ── Model Comparison ─────────────────────────────────────────────

    /**
     * Toggles comparison mode on/off.
     */
    fun toggleComparison() {
        val currentComparison = stateHandle.state.comparisonState
        if (currentComparison.isEnabled) {
            // Disable: clear all comparison state
            primaryComparisonBuffer.clear()
            secondaryComparisonBuffer.clear()
            stateHandle.update { copy(comparisonState = ComparisonState()) }
        } else {
            // Enable: inherit primary endpoint/model
            stateHandle.update {
                copy(
                    comparisonState = ComparisonState(
                        isEnabled = true,
                        secondaryEndpoint = selectedEndpoint,
                        secondaryModel = selectedModel,
                    ),
                    // When enabling on LANDING, switch to ACTIVE so comparison tabs render
                    screenState = if (screenState == ChatScreenState.LANDING) ChatScreenState.ACTIVE else screenState,
                )
            }
        }
    }

    /**
     * Updates the secondary model selection for comparison mode.
     */
    fun setSecondaryModel(endpoint: String, model: String) {
        val comparison = stateHandle.state.comparisonState
        if (!comparison.isEnabled) return
        stateHandle.update {
            copy(
                comparisonState = comparison.copy(
                    secondaryEndpoint = endpoint,
                    secondaryModel = model,
                ),
            )
        }
    }

    /**
     * Resolves a display-friendly name for the secondary model.
     */
    fun getSecondaryModelDisplayName(): String? {
        val comparison = stateHandle.state.comparisonState
        val endpoint = comparison.secondaryEndpoint ?: return null
        val model = comparison.secondaryModel ?: return null
        return if (endpoint == EndpointConstants.AGENTS) {
            stateHandle.state.agents.find { it.id == model }?.name ?: model
        } else {
            model
        }
    }

    /**
     * Builds an [AddedConversation] for the secondary agent/model in comparison mode.
     * Returns null if comparison is not enabled or secondary selection is incomplete.
     */
    fun buildAddedConvo(parentMessageId: String? = null): AddedConversation? {
        val comparison = stateHandle.state.comparisonState
        if (!comparison.isEnabled) return null
        val endpoint = comparison.secondaryEndpoint ?: return null
        val model = comparison.secondaryModel ?: return null
        val isAgent = endpoint == EndpointConstants.AGENTS
        val resolvedEndpoint = resolveEndpointEnum(endpoint)
        val added = AddedConversation(
            conversationId = stateHandle.state.conversationId,
            parentMessageId = parentMessageId,
            endpoint = resolvedEndpoint,
            endpointType = resolvedEndpoint,
            agentId = if (isAgent) model else null,
            model = if (isAgent) null else model,
        )
        return added
    }

    /**
     * Determines whether a stream event belongs to the secondary (added) agent.
     *
     * The server gives both agents the same `groupId` (they share a parallel
     * execution group), so groupId alone cannot distinguish them. Instead, the
     * server suffixes added-agent IDs with `"____N"` (e.g. `openAI__gpt-5.2____1`).
     * The primary never has this suffix.
     */
    fun isSecondaryEvent(agentId: String?): Boolean {
        if (agentId == null) return false
        val comparison = stateHandle.state.comparisonState
        // If we've already resolved the secondary agentId from earlier SSE events, use that
        if (comparison.secondaryAgentId != null) {
            return agentId == comparison.secondaryAgentId
        }
        // The "____N" suffix identifies the addedConvo (added/secondary) agent.
        return agentId.contains("____")
    }

    /**
     * Resolves an endpoint string (e.g. "openAI") to its [EModelEndpoint] enum value.
     * Defaults to [EModelEndpoint.OPENAI] for unrecognized values.
     */
    private fun resolveEndpointEnum(endpoint: String): EModelEndpoint {
        return try {
            EModelEndpoint.entries.firstOrNull { it.toSerialName() == endpoint }
                ?: EModelEndpoint.valueOf(endpoint.uppercase())
        } catch (_: IllegalArgumentException) {
            EModelEndpoint.OPENAI
        }
    }

    fun loadAgents() {
        stateHandle.scope.launch {
            when (val result = agentRepository.getAgents()) {
                is Result.Success -> {
                    stateHandle.update { copy(agents = result.data) }
                }
                is Result.Error -> {
                    // Agents are optional; servers may disable the feature or return 403/404.
                    Timber.d(result.exception, "Agents list unavailable: ${result.message}")
                    stateHandle.update { copy(agents = emptyList()) }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun loadMcpServers() {
        stateHandle.scope.launch {
            when (val serversResult = mcpRepository.listServers()) {
                is Result.Success -> {
                    val servers = serversResult.data
                    // Enrich servers with connection status
                    val statusResult = mcpRepository.getConnectionStatus()
                    val statusMap = (statusResult as? Result.Success)?.data ?: emptyMap()
                    val enriched = servers.map { server ->
                        val status = statusMap[server.name]
                        server.copy(isConnected = status?.isConnected ?: false)
                    }
                    stateHandle.update {
                        copy(mcpServers = enriched.map { it.toDisplayData() })
                    }
                }
                is Result.Error -> {
                    Timber.d(serversResult.exception, "Failed to load MCP servers: ${serversResult.message}")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun toggleMcpServer(serverName: String) {
        val current = stateHandle.state.selectedMcpServerNames
        val updated = if (serverName in current) current - serverName else current + serverName
        stateHandle.update { copy(selectedMcpServerNames = updated) }
        stateHandle.scope.launch { settingsDataStore.setSelectedMcpServers(updated) }
    }

    fun toggleTool(toolName: String) {
        if (toolName == ToolConstants.WEB_SEARCH) {
            // Web search is backed by modelParameters.webSearch (single source of truth).
            val current = stateHandle.state.modelParameters.webSearch
            stateHandle.update {
                copy(modelParameters = modelParameters.copy(webSearch = !current))
            }
        } else if (toolName == ToolConstants.CODE_INTERPRETER && !stateHandle.state.isCodeInterpreterAvailable) {
            // Code interpreter is not available on this server; ignore toggle attempt.
            return
        } else {
            val current = stateHandle.state.enabledTools
            val updated = if (toolName in current) current - toolName else current + toolName
            stateHandle.update { copy(enabledTools = updated) }
            stateHandle.scope.launch { settingsDataStore.setEnabledTools(updated) }
        }
    }

    fun showModelParameters() {
        stateHandle.update { copy(showModelParameters = true) }
    }

    fun hideModelParameters() {
        stateHandle.update { copy(showModelParameters = false) }
    }

    fun updateModelParameters(parameters: ModelParameters) {
        stateHandle.update { copy(modelParameters = parameters) }
    }
}

// --- Display data mapping extensions ---

internal fun McpServer.toDisplayData() = McpServerDisplayData(
    name = name,
    title = title,
    description = description,
    isConnected = isConnected,
)

internal fun EModelEndpoint.toSerialName(): String = when (this) {
    EModelEndpoint.AZURE_OPENAI -> "azureOpenAI"
    EModelEndpoint.OPENAI -> "openAI"
    EModelEndpoint.GOOGLE -> "google"
    EModelEndpoint.ANTHROPIC -> "anthropic"
    EModelEndpoint.ASSISTANTS -> "assistants"
    EModelEndpoint.AZURE_ASSISTANTS -> "azureAssistants"
    EModelEndpoint.AGENTS -> "agents"
    EModelEndpoint.CUSTOM -> "custom"
    EModelEndpoint.BEDROCK -> "bedrock"
}
