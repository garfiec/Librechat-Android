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
import com.garfiec.librechat.core.model.AgentAction
import com.garfiec.librechat.core.model.AgentCategory
import com.garfiec.librechat.core.model.AgentFile
import com.garfiec.librechat.core.model.AgentSubagentsConfig
import com.garfiec.librechat.core.model.AgentTool
import com.garfiec.librechat.core.model.ArtifactsMode
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.model.HandoffEdge
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.SupportContact
import com.garfiec.librechat.core.model.mcp.McpTool
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.model.request.CreateActionRequest
import com.garfiec.librechat.core.model.request.CreateAgentRequest
import com.garfiec.librechat.core.model.request.DeleteFileEntry
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
import com.garfiec.librechat.feature.agents.components.model.buildAgentVersionList
import com.garfiec.librechat.feature.agents.util.ContentReader
import com.garfiec.librechat.feature.agents.util.OpenApiSpecParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /** Cached file-detail payload from `GET /api/files/agent/:id`. Held here so
     *  that whichever of [loadAgent] / [loadAgentFiles] finishes second can
     *  trigger the merge against the by-then-populated tool_resources slots.
     *  Without this cache, [loadAgentFiles] finishing first would read empty
     *  slot lists and produce a no-op enrichment. */
    private var loadedAgentFileObjects: List<FileObject>? = null

    init {
        loadAvailableTools()
        loadCategories()
        loadModels()
        loadMcpTools()
        loadAllAgents()
        loadCodeInterpreterAvailability()
        observeWebSearchAvailability()
        observeSkillsAvailability()
        observeSubagentsAvailability()
        observeServerVersion()
        verifyCodeToolAuth()
        if (editAgentId != null) {
            loadAgent(editAgentId)
            loadActions()
            loadAgentFiles(editAgentId)
        }
    }

    /**
     * Fetches `GET /api/files/agent/:id` and merges filename/bytes/type into
     * the per-capability slots populated from `tool_resources.<X>.file_ids`.
     * The agent payload only carries file_ids — without this call, chips
     * would render with bare IDs.
     */
    private fun loadAgentFiles(agentId: String) {
        viewModelScope.launch {
            when (val result = fileRepository.getAgentFiles(agentId)) {
                is Result.Success -> {
                    loadedAgentFileObjects = result.data
                    mergeAgentFileMetadata(result.data)
                }
                is Result.Error -> { /* Best-effort enrichment; chips fall back to fileId */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun mergeAgentFileMetadata(files: List<FileObject>) {
        val byId = files.associateBy { it.fileId }
        fun enrich(list: List<AgentFile>): List<AgentFile> = list.map { agentFile ->
            byId[agentFile.fileId]?.let { obj ->
                agentFile.copy(
                    filename = agentFile.filename ?: obj.filename,
                    bytes = agentFile.bytes ?: obj.bytes,
                    type = agentFile.type ?: obj.type,
                )
            } ?: agentFile
        }
        _uiState.value = _uiState.value.copy(
            codeFiles = enrich(_uiState.value.codeFiles),
            knowledgeFiles = enrich(_uiState.value.knowledgeFiles),
            contextFiles = enrich(_uiState.value.contextFiles),
        )
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

    private fun loadAgent(agentId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
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
                    val newState = _uiState.value
                        .applyAgentData(agent)
                        .copy(isLoading = false)
                    _uiState.value = newState
                    // If loadAgentFiles already returned, re-merge now that
                    // the per-capability slot lists are populated. Without
                    // this, an earlier-finishing files request would have
                    // merged against empty lists and produced no enrichment.
                    loadedAgentFileObjects?.let { mergeAgentFileMetadata(it) }
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
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Failed to load agent",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadAvailableTools() {
        viewModelScope.launch {
            when (val result = agentRepository.getAvailableTools()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        availableTools = result.data.map { it.toDisplayData() },
                    )
                }
                is Result.Error -> { /* Tools are optional, ignore errors */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            when (val result = agentRepository.getAgentCategories()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        categories = result.data,
                    )
                }
                is Result.Error -> { /* Categories are optional */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadModels() {
        viewModelScope.launch {
            when (val result = configRepository.fetchModels()) {
                is Result.Success -> {
                    val modelOptions = result.data.flatMap { (endpoint, models) ->
                        models.map { modelName ->
                            ModelOption(
                                id = modelName,
                                name = modelName,
                                endpoint = endpoint,
                            )
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        availableModels = modelOptions,
                    )
                }
                is Result.Error -> { /* Models loading failed, user can retry */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadMcpTools() {
        viewModelScope.launch {
            when (val result = mcpRepository.getTools()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(mcpTools = result.data)
                }
                is Result.Error -> { /* MCP tools are optional */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
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

    private fun loadAllAgents() {
        viewModelScope.launch {
            when (val result = agentRepository.getAgents()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        allAgents = result.data
                            .filter { it.id != editAgentId }
                            .map { it.toHandoffDisplayData() },
                    )
                }
                is Result.Error -> { /* Agents list is optional for handoff */ }
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

    /**
     * Picks a slot's current list. Used by upload/remove so the caller doesn't
     * have to switch on the enum.
     */
    private fun filesFor(slot: AgentFileSlot): List<AgentFile> = when (slot) {
        AgentFileSlot.CODE -> _uiState.value.codeFiles
        AgentFileSlot.KNOWLEDGE -> _uiState.value.knowledgeFiles
        AgentFileSlot.CONTEXT -> _uiState.value.contextFiles
    }

    private fun setFilesFor(slot: AgentFileSlot, files: List<AgentFile>) {
        _uiState.value = when (slot) {
            AgentFileSlot.CODE -> _uiState.value.copy(codeFiles = files)
            AgentFileSlot.KNOWLEDGE -> _uiState.value.copy(knowledgeFiles = files)
            AgentFileSlot.CONTEXT -> _uiState.value.copy(contextFiles = files)
        }
    }

    /**
     * Upload a file for the given capability slot. The backend attaches the
     * file to `tool_resources.<wire>.file_ids` on the agent when both
     * `agent_id` + `tool_resource` are supplied. New (unsaved) agents can't
     * accept files yet — the user is told to save first via a snackbar.
     */
    fun uploadAgentFile(fileRef: Any, slot: AgentFileSlot) {
        val agentId = _uiState.value.agentId
        if (agentId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                error = AGENT_FILES_SAVE_FIRST_MARKER,
            )
            return
        }
        viewModelScope.launch {
            try {
                val bytes = contentReader.readBytes(fileRef) ?: run {
                    _uiState.value = _uiState.value.copy(error = "Could not read file")
                    return@launch
                }
                if (bytes.size > AGENT_FILE_SIZE_LIMIT_BYTES) {
                    val limitMb = (AGENT_FILE_SIZE_LIMIT_BYTES / (1024 * 1024)).toInt()
                    _uiState.value = _uiState.value.copy(
                        error = "$AGENT_FILES_TOO_LARGE_MARKER$limitMb",
                    )
                    return@launch
                }
                val filename = contentReader.getFileName(fileRef) ?: "upload"
                val mimeType = contentReader.getMimeType(fileRef) ?: "application/octet-stream"

                _uiState.value = _uiState.value.copy(
                    uploadingSlots = _uiState.value.uploadingSlots + slot,
                )
                val result = fileRepository.uploadFile(
                    bytes = bytes,
                    filename = filename,
                    type = mimeType,
                    endpoint = "agents",
                    agentId = agentId,
                    toolResource = slot.wire,
                )
                _uiState.value = _uiState.value.copy(
                    uploadingSlots = _uiState.value.uploadingSlots - slot,
                )
                when (result) {
                    is Result.Success -> {
                        val obj = result.data
                        val agentFile = AgentFile(
                            fileId = obj.fileId,
                            filename = obj.filename,
                            bytes = obj.bytes,
                            type = obj.type,
                            originResource = slot.wire,
                        )
                        // Re-pick latest because the snackbar might have cleared
                        // state between the upload start and completion.
                        setFilesFor(slot, filesFor(slot) + agentFile)
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            error = result.message ?: AGENT_FILE_UPLOAD_FAILED_MARKER,
                        )
                    }
                    is Result.Loading -> { /* no-op */ }
                }
            } catch (e: Exception) {
                Logger.e(e) { "uploadAgentFile: unexpected error" }
                _uiState.value = _uiState.value.copy(
                    uploadingSlots = _uiState.value.uploadingSlots - slot,
                    error = AGENT_FILE_UPLOAD_FAILED_MARKER,
                )
            }
        }
    }

    fun removeAgentFile(fileId: String, slot: AgentFileSlot) {
        val agentId = _uiState.value.agentId
        // Local-only state when there's no agentId yet (we never let this happen
        // through uploadAgentFile, but stay defensive).
        if (agentId.isNullOrBlank()) {
            setFilesFor(slot, filesFor(slot).filterNot { it.fileId == fileId })
            return
        }
        // Optimistically remove; rollback on error.
        val before = filesFor(slot)
        setFilesFor(slot, before.filterNot { it.fileId == fileId })
        viewModelScope.launch {
            val target = before.firstOrNull { it.fileId == fileId } ?: return@launch
            // The Context slot UI shows files from both `context` and `ocr`
            // tool_resources merged. Route the deletion to the slot the file
            // was actually loaded from so the backend can find and remove
            // the file_id; falling back to slot.wire for files uploaded in
            // this session (which the picker writes under slot.wire).
            val toolResource = target.originResource ?: slot.wire
            val result = fileRepository.deleteFiles(
                files = listOf(DeleteFileEntry(fileId = target.fileId, filepath = "")),
                agentId = agentId,
                toolResource = toolResource,
            )
            if (result is Result.Error) {
                // Rollback
                setFilesFor(slot, before)
                _uiState.value = _uiState.value.copy(
                    error = result.message ?: AGENT_FILE_REMOVE_FAILED_MARKER,
                )
            }
        }
    }

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

    fun uploadAvatar(uri: Any) {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            try {
                // Reading bytes off the URI is blocking I/O — keep it off the Main
                // dispatcher (viewModelScope = Main.immediate) to avoid an ANR on
                // large images. Mirrors the FileAttachmentDelegate fix.
                val bytes = withContext(ioDispatcher) { contentReader.readBytes(uri) } ?: return@launch
                if (bytes.size > AVATAR_SIZE_LIMIT_BYTES) {
                    val limitMb = AVATAR_SIZE_LIMIT_BYTES / (1024 * 1024)
                    _uiState.value = _uiState.value.copy(
                        error = "Avatar must be ${limitMb}MB or smaller",
                    )
                    return@launch
                }
                val mimeType = contentReader.getMimeType(uri) ?: "image/png"

                when (val result = agentRepository.uploadAgentAvatar(agentId, bytes, mimeType)) {
                    is Result.Success -> {
                        _uiState.value = _uiState.value.copy(
                            avatarUrl = result.data.avatarUrl,
                        )
                    }
                    is Result.Error -> {
                        _uiState.value = _uiState.value.copy(
                            error = result.message ?: "Failed to upload avatar",
                        )
                    }
                    is Result.Loading -> { /* no-op */ }
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation must propagate (SKIE/iOS requirement).
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to read image: ${e.message}",
                )
            }
        }
    }

    fun resetAvatar() {
        val agentId = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = agentRepository.resetAgentAvatar(agentId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(avatarUrl = null)
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to reset avatar",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

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
                    loadedAgentFileObjects = null
                    loadAgentFiles(agentId)
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

        // Tool identifiers that represent capabilities (not user-selectable tools).
        // These are stored in the agent's tools list but displayed as capability toggles in the UI.
        private val CAPABILITY_TOOLS = setOf(
            "execute_code",
            "file_search",
            "web_search",
            "context",
            "end_after_tools",
            "hide_sequential_outputs",
            "programmatic_tools",
            "deferred_tools",
        )

        // MCP server marker prefix: tools starting with "sys__server__sys" or containing "_mcp_"
        // are MCP-related entries in the tools list.
        private const val MCP_SERVER_MARKER = "sys__server__sys"
        private const val MCP_TOOL_SEPARATOR = "_mcp_"

        /**
         * Partitions the agent's raw tools list into:
         * - regular tools (for the tool selector UI)
         * - capability booleans (code interpreter, file search, etc.)
         * - selected MCP tool names (tools containing "_mcp_")
         *
         * This mirrors how the web frontend splits the tools array when loading
         * an agent for editing (see AgentSelect.tsx resetAgentForm).
         */
        private fun partitionTools(
            rawTools: List<String>?,
        ): Triple<List<String>, Set<String>, Set<String>> {
            if (rawTools == null) return Triple(emptyList(), emptySet(), emptySet())

            val regularTools = mutableListOf<String>()
            val capabilityTools = mutableSetOf<String>()
            val mcpToolNames = mutableSetOf<String>()

            for (tool in rawTools) {
                when {
                    tool in CAPABILITY_TOOLS -> capabilityTools.add(tool)
                    tool.contains(MCP_TOOL_SEPARATOR) -> {
                        // MCP tools are stored as "toolName_mcp_serverName" or
                        // "sys__server__sys_mcp_serverName" in the agent's tools list.
                        // Extract the tool name part (before _mcp_) for matching against
                        // the McpTool.name from the MCP tools API.
                        val toolName = tool.substringBefore(MCP_TOOL_SEPARATOR)
                        // The sys__server__sys marker means the entire MCP server was
                        // toggled on -- we still track the server name for display.
                        if (toolName == MCP_SERVER_MARKER) {
                            // Server-level toggle: store the server name
                            val serverName = tool.substringAfter(MCP_TOOL_SEPARATOR)
                            mcpToolNames.add(serverName)
                        } else {
                            mcpToolNames.add(toolName)
                        }
                    }
                    else -> regularTools.add(tool)
                }
            }

            return Triple(regularTools, capabilityTools, mcpToolNames)
        }

        /**
         * Applies agent data to the UI state using the copy() function.
         * Returns a new AgentEditorUiState with all agent fields populated.
         * This is the single source of truth for mapping agent API response data
         * to the editor UI, used by both loadAgent() and revertToVersion().
         */
        private fun AgentEditorUiState.applyAgentData(agent: Agent): AgentEditorUiState {
            val (regularTools, capabilityTools, mcpToolNames) = partitionTools(agent.tools)
            val parsedEdges = parseHandoffEdges(agent.edges)

            return copy(
                name = agent.name ?: "",
                description = agent.description ?: "",
                instructions = agent.instructions ?: "",
                model = agent.model ?: "",
                provider = agent.provider ?: "",
                category = agent.category ?: "general",
                selectedTools = regularTools,
                conversationStarters = agent.conversationStarters,
                avatarUrl = agent.avatarUrl,
                codeInterpreterEnabled = "execute_code" in capabilityTools,
                fileSearchEnabled = "file_search" in capabilityTools,
                webSearchEnabled = "web_search" in capabilityTools,
                fileContextEnabled = "context" in capabilityTools,
                selectedMcpTools = mcpToolNames,
                capabilities = AgentCapabilities(
                    artifactsMode = ArtifactsMode.fromWire(agent.artifacts),
                    endAfterTools = agent.endAfterTools ?: false,
                    hideSequentialOutputs = agent.hideSequentialOutputs ?: false,
                    recursionLimit = agent.recursionLimit ?: 25,
                ),
                advancedSettings = parseModelParameters(agent.modelParameters),
                sharingState = AgentSharingState(
                    visibility = when {
                        agent.isPublic == true -> AgentVisibility.PUBLIC
                        agent.isCollaborative == true -> AgentVisibility.TEAM
                        else -> AgentVisibility.PRIVATE
                    },
                    isCollaborative = agent.isCollaborative ?: false,
                ),
                supportContact = agent.parseSupportContact(),
                chainAgentIds = agent.agentIds ?: emptyList(),
                // Re-hydrate from the saved agent (server silently drops
                // inaccessible skill ids in sanitizeViewerSkillScope), so the
                // chips reflect what was actually persisted, not stale local
                // state. Empty + enabled stays "full catalog".
                skillsEnabled = agent.skillsEnabled ?: false,
                selectedSkillIds = agent.skills ?: emptyList(),
                // Subagents config (v0.8.6). allowSelf defaults true (upstream
                // `allowSelf !== false`); self never appears in agent_ids.
                subagentsEnabled = agent.subagents?.enabled ?: false,
                subagentAllowSelf = agent.subagents?.allowSelf != false,
                selectedSubagentIds = agent.subagents?.agentIds
                    ?.filter { it != agent.id }
                    ?: emptyList(),
                handoffEdges = parsedEdges.typed,
                unparsedHandoffEdges = parsedEdges.unparsed,
                toolOptions = agent.toolOptions,
                additionalInstructions = agent.additionalInstructions,
                toolKwargs = agent.toolKwargs,
                codeFiles = parseToolResourceFiles(agent.toolResources, "execute_code"),
                knowledgeFiles = parseToolResourceFiles(agent.toolResources, "file_search"),
                contextFiles = parseToolResourceFiles(agent.toolResources, "context") +
                    // The OCR resource is merged into Context in the editor UI on web
                    // (see upstream client/src/utils/forms.tsx). Mirror that.
                    parseToolResourceFiles(agent.toolResources, "ocr"),
                versions = buildAgentVersionList(
                    rawVersions = agent.versions
                        ?.filterIsInstance<JsonObject>()
                        ?: emptyList(),
                    currentName = agent.name,
                    currentDescription = agent.description,
                    currentInstructions = agent.instructions,
                    currentArtifacts = agent.artifacts,
                    // Match upstream's isActiveVersion exactly: capabilities is
                    // not a separate field on the agent record — the snapshot's
                    // `tools` array carries capability markers (execute_code,
                    // file_search, web_search, context) mixed in with regular
                    // tool names. Passing the union here keeps the active-
                    // version marker working; previously we filtered capability
                    // markers out of currentTools and compared against an empty
                    // capabilities set, which never matched.
                    currentCapabilities = emptySet(),
                    currentTools = (regularTools + mcpToolNames + capabilityTools).toSet(),
                ),
            )
        }

        private val EDGE_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        /**
         * Pulls `tool_resources.<resource>.file_ids` out of the agent payload and
         * lifts each id into an [AgentFile] stub. Filename / bytes / type are
         * filled in later by [loadAgentFiles] hitting `GET /api/files/agent/:id`.
         */
        private fun parseToolResourceFiles(
            toolResources: JsonObject?,
            resource: String,
        ): List<AgentFile> {
            val obj = toolResources ?: return emptyList()
            return try {
                val resourceObj = obj[resource] as? JsonObject ?: return emptyList()
                val ids = resourceObj["file_ids"] as? JsonElement ?: return emptyList()
                ids.jsonArray.mapNotNull { element ->
                    val id = (element as? JsonPrimitive)?.content ?: return@mapNotNull null
                    AgentFile(fileId = id, originResource = resource)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private data class ParsedEdges(
            val typed: List<HandoffEdge>,
            val unparsed: List<JsonElement>,
        )

        private fun parseHandoffEdges(edges: List<JsonElement>?): ParsedEdges {
            if (edges.isNullOrEmpty()) return ParsedEdges(emptyList(), emptyList())
            val typed = mutableListOf<HandoffEdge>()
            val unparsed = mutableListOf<JsonElement>()
            edges.forEach { element ->
                try {
                    typed += EDGE_JSON.decodeFromJsonElement(HandoffEdge.serializer(), element)
                } catch (_: Exception) {
                    // Keep the raw element so save() can re-emit it. Without
                    // this preservation, a single decoder mismatch (e.g.
                    // upstream adds a new required field) silently drops
                    // server-side edges on every subsequent save.
                    unparsed += element
                }
            }
            return ParsedEdges(typed, unparsed)
        }

        internal fun encodeHandoffEdges(edges: List<HandoffEdge>): List<JsonElement>? {
            if (edges.isEmpty()) return null
            return edges.map { EDGE_JSON.encodeToJsonElement(HandoffEdge.serializer(), it) }
        }

        /** Update-path encoder: always returns a list (possibly empty) so the
         *  server overwrites its `edges` field. Use [encodeHandoffEdges] on
         *  create where an empty list adds noise without semantic meaning. */
        internal fun encodeHandoffEdgesAlways(edges: List<HandoffEdge>): List<JsonElement> {
            return edges.map { EDGE_JSON.encodeToJsonElement(HandoffEdge.serializer(), it) }
        }

        /**
         * Parse model_parameters JsonElement into AgentAdvancedSettings.
         *
         * Three fields are typed because the agent-editor UI exposes them
         * (temperature, top_p, max_tokens). Every other key is preserved
         * verbatim in [AgentAdvancedSettings.extras] so values like
         * `frequency_penalty`, `presence_penalty`, `reasoning_effort`,
         * `verbosity`, `thinking`, `thinkingBudget`, `web_search`, `region`,
         * etc. round-trip through load → save without being dropped — even
         * though mobile doesn't yet surface them in the agent editor.
         */
        private fun parseModelParameters(params: JsonElement?): AgentAdvancedSettings {
            if (params == null) return AgentAdvancedSettings()
            return try {
                val obj = params.jsonObject
                val typedKeys = setOf(
                    "temperature",
                    "top_p", "topP",
                    "max_tokens", "maxTokens", "maxOutputTokens",
                )
                // Remember which alias each typed slot was loaded under so the
                // save path can emit using the same key. Without this, a Google
                // agent's `maxOutputTokens` would silently become `max_tokens`
                // on every save, and a Bedrock-Anthropic `topP` would become
                // `top_p`. Servers that index on the exact key (or compute a
                // version diff) treat these as different fields.
                val topPKey = when {
                    "top_p" in obj -> "top_p"
                    "topP" in obj -> "topP"
                    else -> null
                }
                val maxTokensKey = when {
                    "max_tokens" in obj -> "max_tokens"
                    "maxTokens" in obj -> "maxTokens"
                    "maxOutputTokens" in obj -> "maxOutputTokens"
                    else -> null
                }
                AgentAdvancedSettings(
                    temperature = obj["temperature"]?.jsonPrimitive?.floatOrNull,
                    topP = topPKey?.let { obj[it]?.jsonPrimitive?.floatOrNull },
                    maxTokens = maxTokensKey?.let { obj[it]?.jsonPrimitive?.intOrNull },
                    topPKey = topPKey,
                    maxTokensKey = maxTokensKey,
                    extras = obj.filterKeys { it !in typedKeys },
                )
            } catch (_: Exception) {
                AgentAdvancedSettings()
            }
        }

        /**
         * Build model_parameters JsonObject from the advanced settings.
         * Returns null when no parameters are set (avoids sending empty
         * objects). Re-emits everything in [AgentAdvancedSettings.extras]
         * untouched so server-set keys mobile doesn't yet edit survive a
         * save. Typed slots are emitted under the same key the server
         * originally sent — see [AgentAdvancedSettings.topPKey] / [maxTokensKey].
         */
        private fun buildModelParameters(settings: AgentAdvancedSettings): JsonObject? {
            val map = mutableMapOf<String, JsonElement>()
            map.putAll(settings.extras)
            settings.temperature?.let { map["temperature"] = JsonPrimitive(it) }
            settings.topP?.let { map[settings.topPKey ?: "top_p"] = JsonPrimitive(it) }
            settings.maxTokens?.let { map[settings.maxTokensKey ?: "max_tokens"] = JsonPrimitive(it) }
            return if (map.isEmpty()) null else JsonObject(map)
        }

        /**
         * Build the full tools list for saving, combining:
         * - User-selected tools (from tool selector)
         * - Capability tools (execute_code, file_search) based on toggle state
         * - MCP server markers for selected MCP tools
         */
        private fun buildToolsList(state: AgentEditorUiState): List<String> {
            val tools = state.selectedTools.toMutableList()

            // Add capability tools based on toggle state, but gated on
            // server availability — drops the entry if the server's
            // agents.capabilities list excludes the capability (even when
            // the in-memory toggle is on from a previously-loaded agent).
            // The observers intentionally don't reset the toggle on
            // availability transitions; the filter lives here instead.
            if (state.codeInterpreterEnabled && state.isCodeInterpreterAvailable) tools.add("execute_code")
            if (state.fileSearchEnabled) tools.add("file_search")
            if (state.webSearchEnabled && state.isWebSearchAvailable) tools.add("web_search")
            if (state.fileContextEnabled) tools.add("context")

            // Add MCP server markers for each selected MCP tool
            for (mcpToolName in state.selectedMcpTools) {
                // Check if this is a server name or a tool name by looking at available MCP tools
                val matchingTool = state.mcpTools.find { it.name == mcpToolName }
                if (matchingTool != null) {
                    val serverName = matchingTool.serverName
                    if (serverName != null) {
                        // Store as "toolName_mcp_serverName" format
                        tools.add("${mcpToolName}${MCP_TOOL_SEPARATOR}$serverName")
                    } else {
                        tools.add(mcpToolName)
                    }
                } else {
                    // May be a server name marker
                    tools.add("${MCP_SERVER_MARKER}${MCP_TOOL_SEPARATOR}$mcpToolName")
                }
            }

            return tools
        }

        private fun Agent.toHandoffDisplayData() = AgentHandoffDisplayData(
            id = id,
            name = name ?: id,
        )

        private fun AgentAction.toDisplayData(): AgentActionDisplayData {
            val rawSpec = metadata?.rawSpec
            val functionCount = if (!rawSpec.isNullOrBlank()) {
                try {
                    OpenApiSpecParser.extractFunctionInfo(rawSpec).size
                } catch (_: Exception) {
                    0
                }
            } else {
                0
            }
            return AgentActionDisplayData(
                actionId = actionId,
                domain = metadata?.domain,
                type = type,
                authType = metadata?.auth?.type,
                rawSpec = metadata?.rawSpec,
                functionCount = functionCount,
            )
        }

        private fun AgentTool.toDisplayData() = AgentToolDisplayData(
            toolId = pluginKey ?: toolId,
            name = name,
            description = description,
            icon = icon,
            isAvailable = isAvailable,
        )

        /**
         * Parse the support_contact JsonElement from the Agent model into
         * a SupportContactState for the UI.
         */
        private fun Agent.parseSupportContact(): SupportContactState {
            val json = supportContact ?: return SupportContactState()
            return try {
                val obj = json as? JsonObject
                    ?: return SupportContactState()
                val name = obj["name"]
                    ?.let { (it as? JsonPrimitive)?.content }
                    ?: ""
                val email = obj["email"]
                    ?.let { (it as? JsonPrimitive)?.content }
                    ?: ""
                SupportContactState(name = name, email = email)
            } catch (_: Exception) {
                SupportContactState()
            }
        }
    }
}
