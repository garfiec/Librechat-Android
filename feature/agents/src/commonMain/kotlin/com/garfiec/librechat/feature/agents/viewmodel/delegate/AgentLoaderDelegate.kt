package com.garfiec.librechat.feature.agents.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.feature.agents.components.ModelOption
import com.garfiec.librechat.feature.agents.components.model.buildAgentVersionList
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.applyAgentData
import com.garfiec.librechat.feature.agents.viewmodel.remergeMcpServerNames
import com.garfiec.librechat.feature.agents.viewmodel.toDisplayData
import com.garfiec.librechat.feature.agents.viewmodel.toHandoffDisplayData
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Owns the editor's initial data fetches: the agent being edited (via the
 * `/expanded` endpoint) plus the reference data the form needs — the tool
 * catalog, categories, models, MCP tools, and the agent list for handoff /
 * chain / subagent pickers. Each fetch is best-effort and independent; a
 * failure leaves the corresponding section empty rather than blocking the rest.
 *
 * After applying agent data, [loadAgent] asks [AgentFilesDelegate] to re-merge
 * any file metadata that arrived first (see remergeLoadedFiles). The MCP tool
 * fetch has the mirror-image hazard — it resolves the normalized server names in
 * the agent's tool keys back to their raw configured form — so whichever of the
 * two lands second triggers that merge, via [loadedAgentTools].
 */
class AgentLoaderDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val agentRepository: AgentRepository,
    private val configRepository: ConfigRepository,
    private val mcpRepository: McpRepository,
    private val filesDelegate: AgentFilesDelegate,
    private val editAgentId: String?,
) {

    /** The loaded agent's raw tools list, kept so [loadMcpTools] can re-resolve the
     *  server names in it if the MCP fetch is the one that finishes second. Without
     *  it, the normalized name from the tool key stays in `selectedMcpTools` and the
     *  server's row renders as OFF against its raw configured name. */
    private var loadedAgentTools: List<String>? = null

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
                    loadedAgentTools = agent.tools
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
        // Merged in the same update as the list itself: remergeMcpServerNames resolves against
        // `mcpTools`, so a state that has not yet taken them would resolve nothing. If the agent
        // has not loaded yet, `loadedAgentTools` is null and this is a no-op — applyAgentData
        // will then see a populated list and resolve on its own.
        stateHandle.update { copy(mcpTools = tools).remergeMcpServerNames(loadedAgentTools) }
    }

    private fun loadAllAgents() = launchBestEffort(agentRepository::getAgents) { agents ->
        stateHandle.update {
            copy(allAgents = agents.filter { it.id != editAgentId }.map { it.toHandoffDisplayData() })
        }
    }

    /**
     * Fetches the agent's edit history on demand (v0.8.8 `GET /agents/:id/versions`).
     *
     * Not part of [loadReferenceData]: upstream stopped inlining `versions[]` in `/expanded`
     * precisely because histories get large, so pulling one on every editor open would undo that.
     * On a pre-0.8.8 server the array is already inlined and this never runs — [loadAgent] filled
     * the list, so the caller's emptiness check is false.
     */
    fun loadVersions(agentId: String) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isLoadingVersions = true) }
            val result = agentRepository.getAgentVersions(agentId)
            if (result is Result.Success) {
                val raw = result.data.filterIsInstance<JsonObject>()
                stateHandle.update {
                    copy(
                        isLoadingVersions = false,
                        versions = buildAgentVersionList(raw, versionBasis),
                    )
                }
            } else {
                // Leaves the sheet showing its empty state. A 404 here is a pre-0.8.8 server
                // that had nothing to add anyway, and a real failure is not worth an error
                // banner over a read-only history panel.
                Logger.d { "AgentEditor: version history unavailable for $agentId" }
                stateHandle.update { copy(isLoadingVersions = false) }
            }
        }
    }
}
