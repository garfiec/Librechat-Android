package com.garfiec.librechat.feature.agents.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.AgentToolsRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.ActionMetadata
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.AgentCategory
import com.garfiec.librechat.core.model.AgentFile
import com.garfiec.librechat.core.model.HandoffEdge
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.mcp.McpTool
import com.garfiec.librechat.core.model.request.FunctionTool
import com.garfiec.librechat.feature.agents.AgentActionDisplayData
import com.garfiec.librechat.feature.agents.AgentHandoffDisplayData
import com.garfiec.librechat.feature.agents.AgentToolDisplayData
import com.garfiec.librechat.feature.agents.components.ModelOption
import com.garfiec.librechat.feature.agents.components.model.AgentAdvancedSettings
import com.garfiec.librechat.feature.agents.components.model.AgentCapabilities
import com.garfiec.librechat.feature.agents.components.model.AgentSharingState
import com.garfiec.librechat.feature.agents.components.model.AgentVersion
import com.garfiec.librechat.feature.agents.components.model.SupportContactState
import com.garfiec.librechat.feature.agents.util.ContentReader
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentActionsDelegate
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentCapabilitiesDelegate
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentFilesDelegate
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentLoaderDelegate
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentSaveDelegate
import com.garfiec.librechat.feature.agents.viewmodel.delegate.CodeToolAuthDelegate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Per-capability file slot. The wire value is the `tool_resource` form field
 * the backend reads on `POST /api/files` and `DELETE /api/files` — it routes
 * the file into `tool_resources.<wire>.file_ids` on the agent.
 */
enum class AgentFileSlot(val wire: String) {
    CODE("execute_code"),
    KNOWLEDGE("file_search"),
    CONTEXT("context"),
}

@Immutable
data class AgentEditorUiState(
    val isEditMode: Boolean = false,
    val agentId: String? = null,
    val name: String = "",
    val description: String = "",
    val instructions: String = "",
    val model: String = "",
    val provider: String = "",
    val category: String = "general",
    val selectedTools: List<String> = emptyList(),
    val conversationStarters: List<String> = emptyList(),
    val availableTools: List<AgentToolDisplayData> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val nameError: String? = null,
    val descriptionError: String? = null,
    val supportContactNameError: String? = null,
    val supportContactEmailError: String? = null,
    // Advanced editor fields
    val avatarUrl: String? = null,
    val categories: List<AgentCategory> = emptyList(),
    val availableModels: List<ModelOption> = emptyList(),
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val advancedSettings: AgentAdvancedSettings = AgentAdvancedSettings(),
    val versions: List<AgentVersion> = emptyList(),
    val showDeleteConfirm: Boolean = false,
    val showDuplicateConfirm: Boolean = false,
    val showVersionHistory: Boolean = false,
    val isDeleting: Boolean = false,
    val isDuplicating: Boolean = false,
    // Actions
    val actions: List<AgentActionDisplayData> = emptyList(),
    // MCP tools
    val mcpTools: List<McpTool> = emptyList(),
    val selectedMcpTools: Set<String> = emptySet(),
    // Capabilities toggles
    val codeInterpreterEnabled: Boolean = false,
    val fileSearchEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val fileContextEnabled: Boolean = false,
    /** Whether code interpreter is available on this server (from agents endpoint capabilities). */
    val isCodeInterpreterAvailable: Boolean = true,
    /** Whether web search is configured on this server (startupConfig.webSearch != null). */
    val isWebSearchAvailable: Boolean = false,
    /** Whether chain (sequential multi-agent) is enabled in the agents endpoint capabilities. */
    val isChainAvailable: Boolean = false,
    /** Whether the handoffs graph feature is supported (v0.8.5+). */
    val isHandoffsAvailable: Boolean = false,
    /** Whether the granular ACL sharing API is supported (v0.8.5+). */
    val isAclAvailable: Boolean = false,
    // Skills (v0.8.6) — agent-editor skills selector.
    /** Whether the agent `skills_enabled` master toggle is on. */
    val skillsEnabled: Boolean = false,
    /** Skill `_id`s on this agent. Empty + enabled = "full catalog" (allowlist
     *  off), not "no skills" — do NOT auto-clear [skillsEnabled] when empty. */
    val selectedSkillIds: List<String> = emptyList(),
    /** Skill catalog from `GET /api/skills`, used to resolve `_id → name` for
     *  chips and to populate the picker. May be empty if the list is denied
     *  (no SKILLS access) — saved ids then render as raw id chips. */
    val availableSkills: List<SkillSummary> = emptyList(),
    /**
     * Whether the Skills section is shown. Gated on the agents endpoint
     * `capabilities` containing "skills" AND the SKILLS permission, both
     * fail-open to match the sibling capability gates (empty caps / unknown
     * role ⇒ shown).
     */
    val isSkillsAvailable: Boolean = false,
    // Subagents config (v0.8.6) — agent-editor subagent section.
    /**
     * Whether the Subagents section is shown. Gated on the agents endpoint
     * `capabilities` containing "subagents" (capability only — no permission
     * type, unlike skills), fail-open like the sibling capability gates.
     */
    val isSubagentsAvailable: Boolean = false,
    /** Master `subagents.enabled` toggle. */
    val subagentsEnabled: Boolean = false,
    /** `subagents.allowSelf` — agent may spawn itself in an isolated context.
     *  Defaults true (upstream `allowSelf !== false`). */
    val subagentAllowSelf: Boolean = true,
    /** `subagents.agent_ids` — other agents that may be spawned (cap 10, self excluded). */
    val selectedSubagentIds: List<String> = emptyList(),
    // Sharing
    val sharingState: AgentSharingState = AgentSharingState(),
    /**
     * Whether to show the Collaborative toggle in [AgentSharingSection]. Upstream
     * v0.8.5 removed `isCollaborative` + `projectIds` from the agent model in favor
     * of ACL permissions, so the toggle is hidden on v0.8.5+. See VERSION_GATES.md.
     */
    val showCollaborativeToggle: Boolean = true,
    // Chain (sequential multi-agent) — saved as agent_ids
    val chainAgentIds: List<String> = emptyList(),
    // Handoffs (graph edges) — saved as edges; v0.8.5+ only
    val handoffEdges: List<HandoffEdge> = emptyList(),
    /** Raw edge payloads that failed to decode into [HandoffEdge] on load
     *  (e.g. upstream added a field the mobile model doesn't know about).
     *  Re-emitted as-is on save so we don't clobber server-side edges with
     *  an empty list just because one was unparseable. */
    val unparsedHandoffEdges: List<JsonElement> = emptyList(),
    val allAgents: List<AgentHandoffDisplayData> = emptyList(),
    // Support contact
    val supportContact: SupportContactState = SupportContactState(),
    // Tool auth (Code Interpreter key entry)
    val codeToolAuthState: ToolAuthState = ToolAuthState.Unknown,
    val showCodeAuthDialog: Boolean = false,
    // Per-capability file attachments
    val codeFiles: List<AgentFile> = emptyList(),
    val knowledgeFiles: List<AgentFile> = emptyList(),
    val contextFiles: List<AgentFile> = emptyList(),
    /** File-id set currently uploading, keyed for spinner state in chips. */
    val uploadingSlots: Set<AgentFileSlot> = emptySet(),
    /** Per-tool MCP options (`{ tool_name: { defer_loading: bool, programmatic:
     *  bool }, … }`). Round-tripped on every save so values set via the web
     *  client (deferred / programmatic flags) survive a mobile edit. The
     *  save path prunes this map to the agent's current tool selection so
     *  deselecting an MCP tool also drops its options. UI for editing comes
     *  in the follow-up parity PR. */
    val toolOptions: JsonObject? = null,
    /** Agent runtime `additional_instructions`. See [Agent.additionalInstructions]
     *  for the wire-level caveat: round-trip is a no-op against the current
     *  upstream Zod schema and is plumbed only for forward compatibility. */
    val additionalInstructions: String? = null,
    /** Agent runtime `tool_kwargs`. See [Agent.toolKwargs] for shape + the
     *  wire-level caveat that the field is stripped server-side today. */
    val toolKwargs: JsonElement? = null,
)

/**
 * Per-tool authentication state derived from `GET /agents/tools/:id/auth`.
 * [Unknown] is the pre-fetch default; the toggle stays disabled until we
 * learn whether the user has a key (or the server has one configured).
 */
sealed interface ToolAuthState {
    data object Unknown : ToolAuthState

    /** Server has a key configured for this user (`message=system_defined`). */
    data object SystemDefined : ToolAuthState

    /** User has installed their own key (`message=user_provided`, authenticated). */
    data object UserProvided : ToolAuthState

    /** Tool requires a user key but none is installed. */
    data object Unauthenticated : ToolAuthState
}

sealed interface AgentEditorEvent {
    data class SaveSuccess(val agentId: String) : AgentEditorEvent
    data class DuplicateSuccess(val agentId: String) : AgentEditorEvent
    data object DeleteSuccess : AgentEditorEvent
}

class AgentEditorViewModel(
    private val agentRepository: AgentRepository,
    private val configRepository: ConfigRepository,
    private val mcpRepository: McpRepository,
    private val agentToolsRepository: AgentToolsRepository,
    private val fileRepository: FileRepository,
    private val skillsRepository: SkillsRepository,
    private val roleRepository: RoleRepository,
    private val contentReader: ContentReader,
    private val ioDispatcher: CoroutineDispatcher,
    initialAgentId: String? = null,
) : ViewModel() {

    private val editAgentId: String? = initialAgentId

    private val _uiState = MutableStateFlow(
        AgentEditorUiState(
            isEditMode = editAgentId != null,
            agentId = editAgentId,
        ),
    )
    val uiState: StateFlow<AgentEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AgentEditorEvent>()
    val events: SharedFlow<AgentEditorEvent> = _events.asSharedFlow()

    private val stateHandle = AgentEditorStateHandle(_uiState, viewModelScope)

    private val filesDelegate = AgentFilesDelegate(
        stateHandle = stateHandle,
        agentRepository = agentRepository,
        fileRepository = fileRepository,
        contentReader = contentReader,
        ioDispatcher = ioDispatcher,
    )

    private val loaderDelegate = AgentLoaderDelegate(
        stateHandle = stateHandle,
        agentRepository = agentRepository,
        configRepository = configRepository,
        mcpRepository = mcpRepository,
        filesDelegate = filesDelegate,
        editAgentId = editAgentId,
    )

    private val codeAuthDelegate = CodeToolAuthDelegate(
        stateHandle = stateHandle,
        agentToolsRepository = agentToolsRepository,
    )

    private val capabilitiesDelegate = AgentCapabilitiesDelegate(
        stateHandle = stateHandle,
        configRepository = configRepository,
        roleRepository = roleRepository,
        skillsRepository = skillsRepository,
    )

    private val actionsDelegate = AgentActionsDelegate(
        stateHandle = stateHandle,
        agentRepository = agentRepository,
        editAgentId = editAgentId,
    )

    private val saveDelegate = AgentSaveDelegate(
        stateHandle = stateHandle,
        agentRepository = agentRepository,
        filesDelegate = filesDelegate,
        events = _events,
    )

    init {
        loaderDelegate.loadReferenceData()
        capabilitiesDelegate.observeAvailability()
        codeAuthDelegate.verifyCodeToolAuth()
        if (editAgentId != null) {
            loaderDelegate.loadAgent(editAgentId)
            actionsDelegate.loadActions()
            filesDelegate.loadAgentFiles(editAgentId)
        }
    }

    // --- Basic fields ---

    fun onNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(name = name, nameError = null)
    }

    fun onDescriptionChanged(description: String) {
        _uiState.value = _uiState.value.copy(description = description, descriptionError = null)
    }

    fun onInstructionsChanged(instructions: String) {
        _uiState.value = _uiState.value.copy(instructions = instructions)
    }

    fun onModelChanged(model: String) {
        _uiState.value = _uiState.value.copy(model = model)
    }

    fun onModelSelected(modelId: String, provider: String) {
        _uiState.value = _uiState.value.copy(model = modelId, provider = provider)
    }

    fun onCategoryChanged(category: String) {
        _uiState.value = _uiState.value.copy(category = category)
    }

    fun onToolToggled(toolId: String) {
        val current = _uiState.value.selectedTools
        val updated = if (toolId in current) {
            current - toolId
        } else {
            current + toolId
        }
        _uiState.value = _uiState.value.copy(selectedTools = updated)
    }

    fun onToolAdded(toolId: String) {
        val current = _uiState.value.selectedTools
        if (toolId !in current) {
            _uiState.value = _uiState.value.copy(selectedTools = current + toolId)
        }
    }

    fun onToolRemoved(toolId: String) {
        _uiState.value = _uiState.value.copy(
            selectedTools = _uiState.value.selectedTools - toolId,
        )
    }

    fun onConversationStarterAdded(starter: String) {
        if (starter.isBlank()) return
        _uiState.value = _uiState.value.copy(
            conversationStarters = _uiState.value.conversationStarters + starter.trim(),
        )
    }

    fun onConversationStarterRemoved(index: Int) {
        val updated = _uiState.value.conversationStarters.toMutableList()
        if (index in updated.indices) {
            updated.removeAt(index)
        }
        _uiState.value = _uiState.value.copy(conversationStarters = updated)
    }

    fun onCapabilitiesChanged(capabilities: AgentCapabilities) {
        _uiState.value = _uiState.value.copy(capabilities = capabilities)
    }

    fun onAdvancedSettingsChanged(settings: AgentAdvancedSettings) {
        _uiState.value = _uiState.value.copy(advancedSettings = settings)
    }

    // --- Support Contact ---

    fun onSupportContactChanged(supportContact: SupportContactState) {
        _uiState.value = _uiState.value.copy(
            supportContact = supportContact,
            supportContactNameError = null,
            supportContactEmailError = null,
        )
    }

    // --- Actions ---

    fun saveAction(
        actionId: String?,
        metadata: ActionMetadata,
        functions: List<FunctionTool>,
    ) = actionsDelegate.saveAction(actionId, metadata, functions)

    fun deleteAction(actionId: String) = actionsDelegate.deleteAction(actionId)

    // --- MCP Tools ---

    fun onMcpToolToggled(toolName: String) {
        val current = _uiState.value.selectedMcpTools
        val updated = if (toolName in current) {
            current - toolName
        } else {
            current + toolName
        }
        _uiState.value = _uiState.value.copy(selectedMcpTools = updated)
    }

    // --- Capability toggles ---

    fun onCodeInterpreterToggled(enabled: Boolean) = codeAuthDelegate.onCodeInterpreterToggled(enabled)

    fun showCodeToolAuthDialog() = codeAuthDelegate.showCodeToolAuthDialog()

    fun dismissCodeToolAuthDialog() = codeAuthDelegate.dismissCodeToolAuthDialog()

    fun submitCodeToolApiKey(apiKey: String) = codeAuthDelegate.submitCodeToolApiKey(apiKey)

    fun revokeCodeToolApiKey() = codeAuthDelegate.revokeCodeToolApiKey()

    fun onFileSearchToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(fileSearchEnabled = enabled)
    }

    fun onWebSearchToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(webSearchEnabled = enabled)
    }

    fun onFileContextToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(fileContextEnabled = enabled)
    }

    // --- Per-capability file attachments ---

    fun uploadAgentFile(fileRef: Any, slot: AgentFileSlot) = filesDelegate.uploadAgentFile(fileRef, slot)

    fun removeAgentFile(fileId: String, slot: AgentFileSlot) = filesDelegate.removeAgentFile(fileId, slot)

    // --- Sharing ---

    fun onSharingChanged(sharingState: AgentSharingState) {
        _uiState.value = _uiState.value.copy(sharingState = sharingState)
    }

    // --- Skills (v0.8.6) ---

    fun onSkillsToggled(enabled: Boolean) = capabilitiesDelegate.onSkillsToggled(enabled)

    fun onSkillSelectionToggled(skillId: String) = capabilitiesDelegate.onSkillSelectionToggled(skillId)

    fun onSkillRemoved(skillId: String) = capabilitiesDelegate.onSkillRemoved(skillId)

    // --- Subagents (v0.8.6) ---

    fun onSubagentsToggled(enabled: Boolean) = capabilitiesDelegate.onSubagentsToggled(enabled)

    fun onSubagentAllowSelfToggled(allow: Boolean) = capabilitiesDelegate.onSubagentAllowSelfToggled(allow)

    fun addSubagent(agentId: String) = capabilitiesDelegate.addSubagent(agentId)

    fun removeSubagent(agentId: String) = capabilitiesDelegate.removeSubagent(agentId)

    // --- Chain (sequential multi-agent) ---

    fun addChainAgent(agentId: String) = capabilitiesDelegate.addChainAgent(agentId)

    fun removeChainAgent(agentId: String) = capabilitiesDelegate.removeChainAgent(agentId)

    // --- Handoffs (graph edges) ---

    fun addHandoffEdge(edge: HandoffEdge) = capabilitiesDelegate.addHandoffEdge(edge)

    fun updateHandoffEdge(index: Int, edge: HandoffEdge) = capabilitiesDelegate.updateHandoffEdge(index, edge)

    fun removeHandoffEdge(index: Int) = capabilitiesDelegate.removeHandoffEdge(index)

    // --- Dialog state ---

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun showDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = true)
    }

    fun dismissDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = false)
    }

    fun showDuplicateConfirmation() {
        _uiState.value = _uiState.value.copy(showDuplicateConfirm = true)
    }

    fun dismissDuplicateConfirmation() {
        _uiState.value = _uiState.value.copy(showDuplicateConfirm = false)
    }

    fun showVersionHistory() {
        _uiState.value = _uiState.value.copy(showVersionHistory = true)
    }

    fun dismissVersionHistory() {
        _uiState.value = _uiState.value.copy(showVersionHistory = false)
    }

    // --- Avatar ---

    fun uploadAvatar(uri: Any) = filesDelegate.uploadAvatar(uri)

    fun resetAvatar() = filesDelegate.resetAvatar()

    // --- Duplicate / Delete / Revert ---

    fun duplicate() = saveDelegate.duplicate()

    fun delete() = saveDelegate.delete()

    fun revertToVersion(version: Int) = saveDelegate.revertToVersion(version)

    // --- Save ---

    fun save() = saveDelegate.save()

    companion object {

        /** Upstream caps chain (sequential multi-agent) at 10 entries. */
        const val CHAIN_MAX = 10

        /** Upstream `MAX_SUBAGENTS` (config.ts) — subagent agent_ids cap. */
        const val MAX_SUBAGENTS = 10

        /**
         * Avatar size cap. Upstream default in fileConfig.avatarSizeLimit is 2MB
         * (packages/data-provider/src/file-config.ts:430). Mobile StartupConfig
         * doesn't surface fileConfig yet, so this hardcodes the default.
         */
        const val AVATAR_SIZE_LIMIT_BYTES = 2 * 1024 * 1024L

        /**
         * Per-file cap for agent attachments. Upstream's default for the agents
         * endpoint is 512MB (packages/data-provider/src/file-config.ts:399).
         */
        const val AGENT_FILE_SIZE_LIMIT_BYTES = 512L * 1024 * 1024

        // Sentinel error strings the screen layer recognizes and substitutes with
        // localized resources. Routing errors as identifiable markers keeps the
        // VM string-resource-agnostic without growing a parallel "errorKind" channel.
        const val AGENT_FILES_SAVE_FIRST_MARKER = "agent_files_save_first"
        const val AGENT_FILES_TOO_LARGE_MARKER = "agent_file_too_large:"
        const val AGENT_FILE_UPLOAD_FAILED_MARKER = "agent_file_upload_failed"
        const val AGENT_FILE_REMOVE_FAILED_MARKER = "agent_file_remove_failed"
    }
}
