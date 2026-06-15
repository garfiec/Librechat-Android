package com.garfiec.librechat.feature.agents.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.feature.agents.components.ModelOption
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.applyAgentData
import com.garfiec.librechat.feature.agents.viewmodel.toDisplayData
import com.garfiec.librechat.feature.agents.viewmodel.toHandoffDisplayData
import kotlinx.coroutines.launch

/**
 * Owns the editor's initial data fetches: the agent being edited (via the
 * `/expanded` endpoint) plus the reference data the form needs — the tool
 * catalog, categories, models, MCP tools, and the agent list for handoff /
 * chain / subagent pickers. Each fetch is best-effort and independent; a
 * failure leaves the corresponding section empty rather than blocking the rest.
 *
 * After applying agent data, [loadAgent] asks [AgentFilesDelegate] to re-merge
 * any file metadata that arrived first (see remergeLoadedFiles).
 */
class AgentLoaderDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val agentRepository: AgentRepository,
    private val configRepository: ConfigRepository,
    private val mcpRepository: McpRepository,
    private val filesDelegate: AgentFilesDelegate,
    private val editAgentId: String?,
) {

    /** Kicks off the form's reference-data fetches (always run, edit or create). */
    fun loadReferenceData() {
        loadAvailableTools()
        loadCategories()
        loadModels()
        loadMcpTools()
        loadAllAgents()
    }

    fun loadAgent(agentId: String) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isLoading = true, error = null) }
            Logger.d { "AgentEditor: Loading agent for editing: $agentId" }
            // Use getAgentForEditing which calls the /expanded endpoint.
            // The standard getAgent endpoint (GET /api/agents/:id) only returns
            // basic view-only fields (id, name, description, avatar, model, provider).
            // It does NOT return instructions, tools, category, conversation_starters,
            // model_parameters, or other configuration needed for the editor.
            when (val result = agentRepository.getAgentForEditing(agentId)) {
                is Result.Success -> {
                    val agent = result.data
                    Logger.d {
                        "AgentEditor: Loaded agent fields BEFORE mapping - " +
                            "name=${agent.name}, description=${agent.description}, " +
                            "instructions=${agent.instructions}, model=${agent.model}, " +
                            "provider=${agent.provider}, category=${agent.category}, tools=${agent.tools}, " +
                            "conversationStarters=${agent.conversationStarters}, avatarUrl=${agent.avatarUrl}, " +
                            "artifacts=${agent.artifacts}, recursionLimit=${agent.recursionLimit}, " +
                            "hideSequentialOutputs=${agent.hideSequentialOutputs}, endAfterTools=${agent.endAfterTools}, " +
                            "isPublic=${agent.isPublic}, isCollaborative=${agent.isCollaborative}, " +
                            "agentIds=${agent.agentIds}, supportContact=${agent.supportContact}, " +
                            "modelParameters=${agent.modelParameters}"
                    }
                    val newState = stateHandle.state
                        .applyAgentData(agent)
                        .copy(isLoading = false)
                    stateHandle.update { newState }
                    // If loadAgentFiles already returned, re-merge now that
                    // the per-capability slot lists are populated. Without
                    // this, an earlier-finishing files request would have
                    // merged against empty lists and produced no enrichment.
                    filesDelegate.remergeLoadedFiles()
                    Logger.d {
                        "AgentEditor: UI state AFTER mapping - " +
                            "name=${newState.name}, description=${newState.description}, " +
                            "instructions=${newState.instructions}, model=${newState.model}, " +
                            "provider=${newState.provider}, category=${newState.category}, selectedTools=${newState.selectedTools}, " +
                            "conversationStarters=${newState.conversationStarters}, avatarUrl=${newState.avatarUrl}, " +
                            "codeInterpreterEnabled=${newState.codeInterpreterEnabled}, fileSearchEnabled=${newState.fileSearchEnabled}, " +
                            "capabilities=${newState.capabilities}, advancedSettings=${newState.advancedSettings}, " +
                            "sharingState=${newState.sharingState}, chainAgentIds=${newState.chainAgentIds}, " +
                            "handoffEdges=${newState.handoffEdges.size} edges, " +
                            "supportContact=${newState.supportContact}"
                    }
                }
                is Result.Error -> {
                    Logger.e { "AgentEditor: Failed to load agent $agentId: ${result.message}" }
                    stateHandle.update {
                        copy(isLoading = false, error = result.message ?: "Failed to load agent")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Runs one best-effort reference-data fetch: applies [onSuccess] on success
     * and silently ignores errors so a single failed section leaves the rest of
     * the form usable. Each fetch launches independently, so the five reference
     * loads run concurrently.
     */
    private fun <T> launchBestEffort(fetch: suspend () -> Result<T>, onSuccess: (T) -> Unit) {
        stateHandle.scope.launch {
            val result = fetch()
            if (result is Result.Success) onSuccess(result.data)
        }
    }

    private fun loadAvailableTools() = launchBestEffort(agentRepository::getAvailableTools) { tools ->
        stateHandle.update { copy(availableTools = tools.map { it.toDisplayData() }) }
    }

    private fun loadCategories() = launchBestEffort(agentRepository::getAgentCategories) { categories ->
        stateHandle.update { copy(categories = categories) }
    }

    private fun loadModels() = launchBestEffort(configRepository::fetchModels) { models ->
        val modelOptions = models.flatMap { (endpoint, modelNames) ->
            modelNames.map { modelName ->
                ModelOption(id = modelName, name = modelName, endpoint = endpoint)
            }
        }
        stateHandle.update { copy(availableModels = modelOptions) }
    }

    private fun loadMcpTools() = launchBestEffort(mcpRepository::getTools) { tools ->
        stateHandle.update { copy(mcpTools = tools) }
    }

    private fun loadAllAgents() = launchBestEffort(agentRepository::getAgents) { agents ->
        stateHandle.update {
            copy(allAgents = agents.filter { it.id != editAgentId }.map { it.toHandoffDisplayData() })
        }
    }
}
