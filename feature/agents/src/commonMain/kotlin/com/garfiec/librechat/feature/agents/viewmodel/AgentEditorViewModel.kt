package com.garfiec.librechat.feature.agents.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.result.Result
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
import com.garfiec.librechat.core.model.AgentSubagentsConfig
import com.garfiec.librechat.core.model.HandoffEdge
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.SupportContact
import com.garfiec.librechat.core.model.mcp.McpTool
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.model.request.CreateActionRequest
import com.garfiec.librechat.core.model.request.CreateAgentRequest
import com.garfiec.librechat.core.model.request.FunctionTool
import com.garfiec.librechat.core.model.request.RevertAgentRequest
import com.garfiec.librechat.core.model.request.UpdateAgentRequest
import com.garfiec.librechat.feature.agents.AgentActionDisplayData
import com.garfiec.librechat.feature.agents.AgentHandoffDisplayData
import com.garfiec.librechat.feature.agents.AgentToolDisplayData
import com.garfiec.librechat.feature.agents.components.ModelOption
import com.garfiec.librechat.feature.agents.components.model.AgentAdvancedSettings
import com.garfiec.librechat.feature.agents.components.model.AgentCapabilities
import com.garfiec.librechat.feature.agents.components.model.AgentSharingState
import com.garfiec.librechat.feature.agents.components.model.AgentVersion
import com.garfiec.librechat.feature.agents.components.model.AgentVisibility
import com.garfiec.librechat.feature.agents.components.model.SupportContactState
import com.garfiec.librechat.feature.agents.util.ContentReader
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentFilesDelegate
import com.garfiec.librechat.feature.agents.viewmodel.delegate.AgentLoaderDelegate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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

    init {
        loaderDelegate.loadReferenceData()
        loadCodeInterpreterAvailability()
        observeWebSearchAvailability()
        observeSkillsAvailability()
        observeSubagentsAvailability()
        observeServerVersion()
        verifyCodeToolAuth()
        if (editAgentId != null) {
            loaderDelegate.loadAgent(editAgentId)
            loadActions()
            filesDelegate.loadAgentFiles(editAgentId)
        }
    }

    /**
     * Observes the detected backend version and hides the Collaborative toggle
     * on v0.8.5+ where the server no longer honors `isCollaborative`/`projectIds`.
     * See VERSION_GATES.md at the repo root.
     */
    private fun observeServerVersion() {
        viewModelScope.launch {
            configRepository.detectedBackendVersion.collect { version ->
                val show = version == null ||
                    !BackendVersion.isCompatibleOrNewer(version, "0.8.5")
                // Handoffs (graph edges) require v0.8.5+; on older servers the field is ignored.
                val handoffsAvailable = version != null &&
                    BackendVersion.isCompatibleOrNewer(version, "0.8.5")
                _uiState.value = _uiState.value.copy(
                    showCollaborativeToggle = show,
                    isHandoffsAvailable = handoffsAvailable,
                    isAclAvailable = handoffsAvailable,
                )
            }
        }
    }

    /**
     * Observes the agents endpoint config capabilities to determine
     * whether code interpreter (execute_code) is available on this server.
     */
    private fun loadCodeInterpreterAvailability() {
        viewModelScope.launch {
            configRepository.endpointConfigs.collect { configs ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                // If capabilities list is non-empty, check for the capability.
                // If empty (no config loaded yet), default to available for known-default
                // capabilities (execute_code) and unavailable for opt-in ones (chain).
                val codeAvailable = agentsCapabilities.isEmpty() || "execute_code" in agentsCapabilities
                val chainAvailable = "chain" in agentsCapabilities
                _uiState.value = _uiState.value.copy(
                    isCodeInterpreterAvailable = codeAvailable,
                    isChainAvailable = chainAvailable,
                )
                // NOTE: do NOT auto-disable [codeInterpreterEnabled] here.
                // endpointConfigs is a StateFlow that re-emits whenever any
                // config changes (e.g., a sibling VM calls fetchEndpoints
                // after a provider-key edit). If applyAgentData ran before
                // the second emission and set codeInterpreterEnabled=true,
                // an unrelated config refresh would silently stomp the
                // user's just-loaded capability. Availability gating is
                // applied at save time in [buildToolsList] instead, so
                // the in-memory toggle survives transient mismatches.
            }
        }
    }

    /**
     * Observes the agents endpoint capabilities to determine whether web
     * search is available. Inspired by upstream `useAgentCapabilities`
     * (client/src/hooks/Agents/useAgentCapabilities.ts), but with a
     * deliberate divergence in fail-open semantics.
     *
     * Upstream is FAIL-CLOSED: `capabilities?.includes(web_search) ?? false`
     * — an empty/undefined capabilities array hides the toggle. Mobile is
     * FAIL-OPEN: an empty list (or older backends that don't ship the
     * capabilities array at all) treats the feature as available. This
     * matches the heuristic [loadCodeInterpreterAvailability] uses for
     * `execute_code` and avoids hiding the toggle on legacy servers that
     * never enumerated capabilities. Admins who intentionally ship an empty
     * `agents.capabilities` to disable agent tooling will see the mobile
     * toggle remain visible — save-time tools-list filtering still applies
     * if the field is present-but-excluded.
     */
    private fun observeWebSearchAvailability() {
        viewModelScope.launch {
            configRepository.endpointConfigs.collect { configs ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                val available = agentsCapabilities.isEmpty() ||
                    "web_search" in agentsCapabilities
                _uiState.value = _uiState.value.copy(isWebSearchAvailable = available)
                // NOTE: do NOT auto-disable [webSearchEnabled] here. See the
                // matching note in [loadCodeInterpreterAvailability] — a
                // late-arriving endpointConfigs emission can race past
                // applyAgentData and silently strip the capability from a
                // freshly-loaded agent. Availability gating is applied at
                // save time in [buildToolsList].
            }
        }
    }

    /**
     * Observes the agents endpoint `capabilities` array and the user's SKILLS
     * permission to gate the Skills section (upstream `showSkills =
     * hasSkillsAccess && skillsEnabled`, where `skillsEnabled =
     * capabilities.includes('skills')`). Both checks are FAIL-OPEN to match
     * the sibling capability gates ([observeWebSearchAvailability]): an empty
     * capabilities list or an unknown role (timeout / not yet loaded) treats
     * the feature as available. When the section first becomes visible we
     * fetch the skill catalog (for `_id → name` resolution and the picker).
     */
    private fun observeSkillsAvailability() {
        viewModelScope.launch {
            combine(
                configRepository.endpointConfigs,
                roleRepository.userPermissions,
            ) { configs, role ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                val capabilityAvailable = agentsCapabilities.isEmpty() ||
                    "skills" in agentsCapabilities
                val permissionAvailable =
                    role.hasAccessOrPermissive(PermissionType.SKILLS, Permission.USE)
                capabilityAvailable && permissionAvailable
            }.collect { available ->
                val wasAvailable = _uiState.value.isSkillsAvailable
                _uiState.value = _uiState.value.copy(isSkillsAvailable = available)
                // Lazily load the catalog the first time the section is shown.
                if (available && !wasAvailable && _uiState.value.availableSkills.isEmpty()) {
                    loadSkills()
                }
            }
        }
    }

    /** Fetches the skill catalog for the picker + chip-name resolution. Best
     *  effort — a denied/empty list leaves saved ids rendering as raw chips. */
    private fun loadSkills() {
        viewModelScope.launch {
            when (val result = skillsRepository.listSkills()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(availableSkills = result.data.skills)
                }
                is Result.Error -> {
                    Logger.d { "AgentEditor: skills list failed: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /** Master `skills_enabled` toggle. Turning off keeps [selectedSkillIds] so
     *  re-enabling restores the prior allowlist; the save path drops the
     *  allowlist from the payload when disabled. */
    fun onSkillsToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(skillsEnabled = enabled)
    }

    fun onSkillSelectionToggled(skillId: String) {
        val current = _uiState.value.selectedSkillIds
        val next = if (skillId in current) current - skillId else current + skillId
        _uiState.value = _uiState.value.copy(selectedSkillIds = next)
    }

    fun onSkillRemoved(skillId: String) {
        _uiState.value = _uiState.value.copy(
            selectedSkillIds = _uiState.value.selectedSkillIds - skillId,
        )
    }

    /**
     * Gates the Subagents section on the agents endpoint `capabilities`
     * containing "subagents" (upstream `AgentCapabilities.subagents`, same
     * source the skills/web-search gates read). Capability-only — subagents has
     * no PermissionType. Fail-open like the sibling gates (empty caps ⇒ shown).
     */
    private fun observeSubagentsAvailability() {
        viewModelScope.launch {
            configRepository.endpointConfigs.collect { configs ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                val available = agentsCapabilities.isEmpty() || "subagents" in agentsCapabilities
                _uiState.value = _uiState.value.copy(isSubagentsAvailable = available)
            }
        }
    }

    /** Master `subagents.enabled` toggle. Keeps [selectedSubagentIds] /
     *  [subagentAllowSelf] so re-enabling restores them; the save path sends an
     *  explicit `enabled:false` config when off. */
    fun onSubagentsToggled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(subagentsEnabled = enabled)
    }

    fun onSubagentAllowSelfToggled(allow: Boolean) {
        _uiState.value = _uiState.value.copy(subagentAllowSelf = allow)
    }

    fun addSubagent(agentId: String) {
        val current = _uiState.value.selectedSubagentIds
        // Upstream caps subagents at MAX_SUBAGENTS; never list the agent itself.
        if (agentId != _uiState.value.agentId &&
            agentId !in current &&
            current.size < MAX_SUBAGENTS
        ) {
            _uiState.value = _uiState.value.copy(selectedSubagentIds = current + agentId)
        }
    }

    fun removeSubagent(agentId: String) {
        _uiState.value = _uiState.value.copy(
            selectedSubagentIds = _uiState.value.selectedSubagentIds - agentId,
        )
    }

    private fun loadActions() {
        viewModelScope.launch {
            when (val result = agentRepository.getAgentActions()) {
                is Result.Success -> {
                    val agentId = editAgentId ?: return@launch
                    val agentActions = result.data
                        .filter { it.agentId == agentId }
                        .map { it.toDisplayData() }
                    _uiState.value = _uiState.value.copy(actions = agentActions)
                }
                is Result.Error -> { /* Actions are optional */ }
                is Result.Loading -> { /* no-op */ }
            }
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
    ) {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val request = CreateActionRequest(
                actionId = actionId,
                metadata = metadata,
                functions = functions,
            )
            when (val result = agentRepository.addOrUpdateAction(agentId, request)) {
                is Result.Success -> {
                    val (_, action) = result.data
                    val existing = _uiState.value.actions.toMutableList()
                    val idx = existing.indexOfFirst { it.actionId == action.actionId }
                    if (idx >= 0) {
                        existing[idx] = action.toDisplayData()
                    } else {
                        existing.add(action.toDisplayData())
                    }
                    _uiState.value = _uiState.value.copy(
                        actions = existing,
                        isSaving = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to save action",
                        isSaving = false,
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun deleteAction(actionId: String) {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = agentRepository.deleteAction(agentId, actionId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        actions = _uiState.value.actions.filter { it.actionId != actionId },
                    )
                }

                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to delete action",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

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

    fun onCodeInterpreterToggled(enabled: Boolean) {
        if (!enabled) {
            // Turning OFF never needs auth.
            _uiState.value = _uiState.value.copy(codeInterpreterEnabled = false)
            return
        }
        // Turning ON: gate on the latest verify result. If the tool is
        // unauthenticated, surface the key dialog instead of flipping the
        // toggle -- the toggle flips on after a successful key install.
        when (_uiState.value.codeToolAuthState) {
            ToolAuthState.Unauthenticated -> {
                _uiState.value = _uiState.value.copy(showCodeAuthDialog = true)
            }
            ToolAuthState.Unknown -> {
                // Race: verify hasn't returned yet. Re-verify and bail; user
                // can retap once the result lands.
                verifyCodeToolAuth()
            }
            ToolAuthState.SystemDefined, ToolAuthState.UserProvided -> {
                _uiState.value = _uiState.value.copy(codeInterpreterEnabled = true)
            }
        }
    }

    fun showCodeToolAuthDialog() {
        _uiState.value = _uiState.value.copy(showCodeAuthDialog = true)
    }

    fun dismissCodeToolAuthDialog() {
        _uiState.value = _uiState.value.copy(showCodeAuthDialog = false)
    }

    fun submitCodeToolApiKey(apiKey: String) {
        if (apiKey.isBlank()) return
        viewModelScope.launch {
            val result = agentToolsRepository.installToolKey(
                toolId = TOOL_EXECUTE_CODE,
                authFields = mapOf(CODE_AUTH_FIELD to apiKey),
            )
            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        codeToolAuthState = ToolAuthState.UserProvided,
                        codeInterpreterEnabled = true,
                        showCodeAuthDialog = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to save API key",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun revokeCodeToolApiKey() {
        viewModelScope.launch {
            val result = agentToolsRepository.removeToolKey(
                toolId = TOOL_EXECUTE_CODE,
                authFieldNames = listOf(CODE_AUTH_FIELD),
            )
            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        codeToolAuthState = ToolAuthState.Unauthenticated,
                        codeInterpreterEnabled = false,
                        showCodeAuthDialog = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to revoke API key",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun verifyCodeToolAuth() {
        viewModelScope.launch {
            val result = agentToolsRepository.verifyToolAuth(TOOL_EXECUTE_CODE)
            if (result is Result.Success) {
                val data = result.data
                val next = when {
                    data.authenticated != true -> ToolAuthState.Unauthenticated
                    data.isSystemDefined -> ToolAuthState.SystemDefined
                    data.isUserProvided -> ToolAuthState.UserProvided
                    // authenticated = true with an unknown message; treat as configured.
                    else -> ToolAuthState.SystemDefined
                }
                _uiState.value = _uiState.value.copy(codeToolAuthState = next)
            }
            // On error, leave state at Unknown -- user can retap and we'll retry.
        }
    }

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

    // --- Chain (sequential multi-agent) ---

    fun addChainAgent(agentId: String) {
        val current = _uiState.value.chainAgentIds
        // Upstream caps the chain at 10 agents.
        if (agentId !in current && current.size < CHAIN_MAX) {
            _uiState.value = _uiState.value.copy(chainAgentIds = current + agentId)
        }
    }

    fun removeChainAgent(agentId: String) {
        _uiState.value = _uiState.value.copy(
            chainAgentIds = _uiState.value.chainAgentIds - agentId,
        )
    }

    // --- Handoffs (graph edges) ---

    fun addHandoffEdge(edge: HandoffEdge) {
        _uiState.value = _uiState.value.copy(
            handoffEdges = _uiState.value.handoffEdges + edge,
        )
    }

    fun updateHandoffEdge(index: Int, edge: HandoffEdge) {
        val list = _uiState.value.handoffEdges.toMutableList()
        if (index in list.indices) {
            list[index] = edge
            _uiState.value = _uiState.value.copy(handoffEdges = list)
        }
    }

    fun removeHandoffEdge(index: Int) {
        val list = _uiState.value.handoffEdges.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _uiState.value = _uiState.value.copy(handoffEdges = list)
        }
    }

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

    fun duplicate() {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDuplicating = true,
                showDuplicateConfirm = false,
            )
            when (val result = agentRepository.duplicateAgent(agentId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isDuplicating = false)
                    _events.emit(AgentEditorEvent.DuplicateSuccess(result.data.id))
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isDuplicating = false,
                        error = result.message ?: "Failed to duplicate agent",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun delete() {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDeleting = true,
                showDeleteConfirm = false,
            )
            when (val result = agentRepository.deleteAgent(agentId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isDeleting = false)
                    _events.emit(AgentEditorEvent.DeleteSuccess)
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = result.message ?: "Failed to delete agent",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun revertToVersion(version: Int) {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(showVersionHistory = false, isLoading = true)
            when (val result = agentRepository.revertAgent(agentId, RevertAgentRequest(version))) {
                is Result.Success -> {
                    _uiState.value = _uiState.value
                        .applyAgentData(result.data)
                        .copy(isLoading = false)
                    // A reverted version often has a different file set (different
                    // execute_code / file_search / context attachments). Clear the
                    // stale enrichment cache and re-fetch /api/files/agent/:id so
                    // the new file_ids resolve to filename/bytes/type instead of
                    // showing bare IDs in the chips.
                    filesDelegate.resetFileCache()
                    filesDelegate.loadAgentFiles(agentId)
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to revert agent",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    // --- Save ---

    fun save() {
        val state = _uiState.value

        // Validate -- mirror upstream zod schema constraints from
        // packages/data-provider/src/schemas.ts agentSchema.
        val nameError = when {
            state.name.isBlank() -> "Name is required"
            state.name.length > NAME_MAX -> "Name must be at most $NAME_MAX characters"
            else -> null
        }
        val descriptionError = when {
            state.description.length > DESCRIPTION_MAX ->
                "Description must be at most $DESCRIPTION_MAX characters"
            else -> null
        }
        val contactName = state.supportContact.name
        val contactEmail = state.supportContact.email
        val supportContactNameError = when {
            contactName.isNotBlank() && contactName.length < SUPPORT_NAME_MIN ->
                "Support contact name must be at least $SUPPORT_NAME_MIN characters"
            else -> null
        }
        val supportContactEmailError = when {
            contactEmail.isNotBlank() && !EMAIL_REGEX.matches(contactEmail) ->
                "Enter a valid email address"
            else -> null
        }
        val hasErrors = nameError != null || descriptionError != null ||
            supportContactNameError != null || supportContactEmailError != null
        if (hasErrors) {
            _uiState.value = state.copy(
                nameError = nameError,
                descriptionError = descriptionError,
                supportContactNameError = supportContactNameError,
                supportContactEmailError = supportContactEmailError,
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)

            val isPublic = state.sharingState.visibility == AgentVisibility.PUBLIC
            // On v0.8.5+ the server dropped `isCollaborative` / `projectIds` in favor
            // of ACL permissions. When the toggle is hidden we omit the field so the
            // server doesn't silently ignore it. See VERSION_GATES.md.
            val isCollaborative = if (state.showCollaborativeToggle) {
                state.sharingState.isCollaborative
            } else {
                null
            }

            val supportContact = if (state.supportContact.name.isNotBlank() ||
                state.supportContact.email.isNotBlank()
            ) {
                SupportContact(
                    name = state.supportContact.name.ifBlank { null },
                    email = state.supportContact.email.ifBlank { null },
                )
            } else {
                null
            }

            // Build the full tools list: user-selected tools + capability tools + MCP server markers
            val allTools = buildToolsList(state)

            // Prune `tool_options` to the keys still present in the agent's
            // current tool selection. Upstream keys this map by tool name
            // (MCP tool names appear without the `_mcp_serverName` suffix —
            // see `client/src/components/SidePanel/Agents/MCPToolItem.tsx`),
            // so we match against the bare names: `selectedMcpTools` for MCP
            // and `selectedTools` for regular tools. Without this prune, a
            // user who deselects an MCP tool whose options were configured
            // via the web client would still ship those tool_options on
            // save, producing zombie config that re-appears the next time
            // the tool is re-added.
            val keepableToolOptionKeys = state.selectedMcpTools.toSet() + state.selectedTools.toSet()
            val prunedToolOptions = state.toolOptions?.let { options ->
                val filtered = options.filterKeys { it in keepableToolOptionKeys }
                if (filtered.isEmpty()) null else JsonObject(filtered)
            }

            // Build model_parameters from advanced settings
            val modelParameters = buildModelParameters(state.advancedSettings)

            // Artifacts: upstream `ArtifactModes` enum serialized as its wire string.
            // null means "off" (omitted from the request body via encodeDefaults=false).
            val artifacts = state.capabilities.artifactsMode?.wire

            // Chain (sequential agents) + handoffs (graph edges). For CREATE,
            // omit when empty (no prior state to clear). For UPDATE, always
            // send the current value — including empty lists — so removing
            // every chain target or every handoff edge actually clears the
            // server-side list. Coercing empty → null on update would let the
            // server's "missing field = no change" rule swallow the deletion.
            val isUpdate = state.isEditMode && state.agentId != null
            val chainAgentIds = if (isUpdate) state.chainAgentIds else state.chainAgentIds.ifEmpty { null }
            // Append any raw edges that failed to deserialize on load (forward-
            // compatibility for new upstream edge fields the mobile model
            // doesn't model yet). Without re-emitting these, a single decoder
            // mismatch would silently clear all server-side edges on save.
            val handoffEdges = if (isUpdate) {
                encodeHandoffEdgesAlways(state.handoffEdges) + state.unparsedHandoffEdges
            } else {
                val encoded = encodeHandoffEdges(state.handoffEdges).orEmpty() + state.unparsedHandoffEdges
                encoded.ifEmpty { null }
            }

            // Skills (v0.8.6). Write shape per the zod agentBaseSchema
            // (skills/skills_enabled both optional) + the server's $set merge:
            // when the toggle is off, send skills_enabled=false and drop the
            // allowlist. When on, send the toggle plus the current allowlist
            // (empty = "full catalog"; the server stores skills_enabled=true
            // and omits the allowlist). On UPDATE always send both fields so
            // turning skills off, or clearing the allowlist, is honored via the
            // $set merge; on CREATE omit when off (nothing to clear). On read
            // the server scrubs the allowlist to ids the caller can access, so
            // [applyAgentData] re-hydrates from the saved agent rather than
            // trusting this list.
            val skillsEnabled: Boolean?
            val skills: List<String>?
            when {
                !state.skillsEnabled -> {
                    skillsEnabled = if (isUpdate) false else null
                    skills = if (isUpdate) emptyList() else null
                }
                else -> {
                    skillsEnabled = true
                    skills = state.selectedSkillIds
                }
            }

            // Subagents config (v0.8.6). Same persist semantics as skills: when
            // off, send an explicit `{ enabled: false, ... }` on UPDATE (not
            // null) so the server's removeNullishValues doesn't strip it and the
            // $set merge actually clears it; omit on CREATE. When on, send
            // enabled + allowSelf + the agent_ids allowlist (self never included).
            val subagents: AgentSubagentsConfig? = when {
                !state.subagentsEnabled ->
                    if (isUpdate) {
                        AgentSubagentsConfig(
                            enabled = false,
                            allowSelf = state.subagentAllowSelf,
                            agentIds = state.selectedSubagentIds,
                        )
                    } else {
                        null
                    }
                else -> AgentSubagentsConfig(
                    enabled = true,
                    allowSelf = state.subagentAllowSelf,
                    agentIds = state.selectedSubagentIds,
                )
            }

            val result = if (state.isEditMode && state.agentId != null) {
                agentRepository.updateAgent(
                    id = state.agentId,
                    request = UpdateAgentRequest(
                        name = state.name,
                        description = state.description.ifBlank { null },
                        instructions = state.instructions.ifBlank { null },
                        model = state.model.ifBlank { null },
                        provider = state.provider.ifBlank { null },
                        modelParameters = modelParameters,
                        artifacts = artifacts,
                        recursionLimit = state.capabilities.recursionLimit,
                        hideSequentialOutputs = state.capabilities.hideSequentialOutputs,
                        endAfterTools = state.capabilities.endAfterTools,
                        category = state.category.ifBlank { null },
                        tools = allTools.ifEmpty { null },
                        conversationStarters = state.conversationStarters.ifEmpty { null },
                        isPublic = isPublic,
                        isCollaborative = isCollaborative,
                        supportContact = supportContact,
                        agentIds = chainAgentIds,
                        edges = handoffEdges,
                        toolOptions = prunedToolOptions,
                        additionalInstructions = state.additionalInstructions,
                        toolKwargs = state.toolKwargs,
                        skills = skills,
                        skillsEnabled = skillsEnabled,
                        subagents = subagents,
                    ),
                )
            } else {
                agentRepository.createAgent(
                    request = CreateAgentRequest(
                        name = state.name,
                        description = state.description.ifBlank { null },
                        instructions = state.instructions.ifBlank { null },
                        model = state.model.ifBlank { null },
                        provider = state.provider.ifBlank { null },
                        modelParameters = modelParameters,
                        artifacts = artifacts,
                        recursionLimit = state.capabilities.recursionLimit,
                        hideSequentialOutputs = state.capabilities.hideSequentialOutputs,
                        endAfterTools = state.capabilities.endAfterTools,
                        category = state.category.ifBlank { null },
                        tools = allTools.ifEmpty { null },
                        conversationStarters = state.conversationStarters.ifEmpty { null },
                        isPublic = isPublic,
                        isCollaborative = isCollaborative,
                        supportContact = supportContact,
                        agentIds = chainAgentIds,
                        edges = handoffEdges,
                        toolOptions = prunedToolOptions,
                        additionalInstructions = state.additionalInstructions,
                        toolKwargs = state.toolKwargs,
                        skills = skills,
                        skillsEnabled = skillsEnabled,
                        subagents = subagents,
                    ),
                )
            }

            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    _events.emit(AgentEditorEvent.SaveSuccess(result.data.id))
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = result.message ?: "Failed to save agent",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    companion object {

        /** Upstream caps chain (sequential multi-agent) at 10 entries. */
        const val CHAIN_MAX = 10

        /** Upstream `MAX_SUBAGENTS` (config.ts) — subagent agent_ids cap. */
        const val MAX_SUBAGENTS = 10

        /** Tool ids used with `GET /agents/tools/:id/auth`. */
        private const val TOOL_EXECUTE_CODE = "execute_code"

        /** Upstream auth-field name for Code Interpreter (hooks/Plugins/useAuthCodeTool.ts). */
        private const val CODE_AUTH_FIELD = "LIBRECHAT_CODE_API_KEY"

        /** Validation limits mirrored from upstream agentSchema. */
        const val NAME_MAX = 256
        const val DESCRIPTION_MAX = 512
        const val SUPPORT_NAME_MIN = 3

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

        // Pragmatic email regex matching upstream client-side validateEmail.
        // Server still runs its own check, so this only catches obvious typos.
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
