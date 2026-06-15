package com.garfiec.librechat.feature.agents.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.HandoffEdge
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the editor's advanced-capability sections: the server-driven gating that
 * decides which sections are shown (code interpreter / web search / skills /
 * subagents availability, plus version-gated collaborative + handoffs/ACL), and
 * the in-memory section state the user edits (skill allowlist, subagent config,
 * sequential chain, handoff graph edges).
 *
 * The availability observers are deliberately FAIL-OPEN and never auto-disable a
 * loaded agent's toggles — a late `endpointConfigs` re-emission could otherwise
 * race past applyAgentData and strip a freshly-loaded capability. Save-time
 * filtering in buildToolsList is the real gate; see the per-observer notes.
 */
class AgentCapabilitiesDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val configRepository: ConfigRepository,
    private val roleRepository: RoleRepository,
    private val skillsRepository: SkillsRepository,
) {

    /** Starts every availability observer. Called once from the VM init. */
    fun observeAvailability() {
        loadCodeInterpreterAvailability()
        observeWebSearchAvailability()
        observeSkillsAvailability()
        observeSubagentsAvailability()
        observeServerVersion()
    }

    /**
     * Observes the detected backend version and hides the Collaborative toggle
     * on v0.8.5+ where the server no longer honors `isCollaborative`/`projectIds`.
     * See VERSION_GATES.md at the repo root.
     */
    private fun observeServerVersion() {
        stateHandle.scope.launch {
            configRepository.detectedBackendVersion.collect { version ->
                val show = version == null ||
                    !BackendVersion.isCompatibleOrNewer(version, "0.8.5")
                // Handoffs (graph edges) require v0.8.5+; on older servers the field is ignored.
                val handoffsAvailable = version != null &&
                    BackendVersion.isCompatibleOrNewer(version, "0.8.5")
                stateHandle.update {
                    copy(
                        showCollaborativeToggle = show,
                        isHandoffsAvailable = handoffsAvailable,
                        isAclAvailable = handoffsAvailable,
                    )
                }
            }
        }
    }

    /**
     * Observes the agents endpoint config capabilities to determine
     * whether code interpreter (execute_code) is available on this server.
     */
    private fun loadCodeInterpreterAvailability() {
        stateHandle.scope.launch {
            configRepository.endpointConfigs.collect { configs ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                // If capabilities list is non-empty, check for the capability.
                // If empty (no config loaded yet), default to available for known-default
                // capabilities (execute_code) and unavailable for opt-in ones (chain).
                val codeAvailable = agentsCapabilities.isEmpty() || ToolConstants.EXECUTE_CODE in agentsCapabilities
                val chainAvailable = "chain" in agentsCapabilities
                stateHandle.update {
                    copy(
                        isCodeInterpreterAvailable = codeAvailable,
                        isChainAvailable = chainAvailable,
                    )
                }
                // NOTE: do NOT auto-disable [codeInterpreterEnabled] here.
                // endpointConfigs is a StateFlow that re-emits whenever any
                // config changes (e.g., a sibling VM calls fetchEndpoints
                // after a provider-key edit). If applyAgentData ran before
                // the second emission and set codeInterpreterEnabled=true,
                // an unrelated config refresh would silently stomp the
                // user's just-loaded capability. Availability gating is
                // applied at save time in buildToolsList instead, so
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
        stateHandle.scope.launch {
            configRepository.endpointConfigs.collect { configs ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                val available = agentsCapabilities.isEmpty() ||
                    ToolConstants.WEB_SEARCH in agentsCapabilities
                stateHandle.update { copy(isWebSearchAvailable = available) }
                // NOTE: do NOT auto-disable [webSearchEnabled] here. See the
                // matching note in [loadCodeInterpreterAvailability] — a
                // late-arriving endpointConfigs emission can race past
                // applyAgentData and silently strip the capability from a
                // freshly-loaded agent. Availability gating is applied at
                // save time in buildToolsList.
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
        stateHandle.scope.launch {
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
                val wasAvailable = stateHandle.state.isSkillsAvailable
                stateHandle.update { copy(isSkillsAvailable = available) }
                // Lazily load the catalog the first time the section is shown.
                if (available && !wasAvailable && stateHandle.state.availableSkills.isEmpty()) {
                    loadSkills()
                }
            }
        }
    }

    /** Fetches the skill catalog for the picker + chip-name resolution. Best
     *  effort — a denied/empty list leaves saved ids rendering as raw chips. */
    private fun loadSkills() {
        stateHandle.scope.launch {
            when (val result = skillsRepository.listSkills()) {
                is Result.Success -> {
                    stateHandle.update { copy(availableSkills = result.data.skills) }
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
        stateHandle.update { copy(skillsEnabled = enabled) }
    }

    fun onSkillSelectionToggled(skillId: String) {
        val current = stateHandle.state.selectedSkillIds
        val next = if (skillId in current) current - skillId else current + skillId
        stateHandle.update { copy(selectedSkillIds = next) }
    }

    fun onSkillRemoved(skillId: String) {
        stateHandle.update { copy(selectedSkillIds = selectedSkillIds - skillId) }
    }

    /**
     * Gates the Subagents section on the agents endpoint `capabilities`
     * containing "subagents" (upstream `AgentCapabilities.subagents`, same
     * source the skills/web-search gates read). Capability-only — subagents has
     * no PermissionType. Fail-open like the sibling gates (empty caps ⇒ shown).
     */
    private fun observeSubagentsAvailability() {
        stateHandle.scope.launch {
            configRepository.endpointConfigs.collect { configs ->
                val agentsCapabilities = configs["agents"]?.capabilities ?: emptyList()
                val available = agentsCapabilities.isEmpty() || "subagents" in agentsCapabilities
                stateHandle.update { copy(isSubagentsAvailable = available) }
            }
        }
    }

    /** Master `subagents.enabled` toggle. Keeps [selectedSubagentIds] /
     *  [subagentAllowSelf] so re-enabling restores them; the save path sends an
     *  explicit `enabled:false` config when off. */
    fun onSubagentsToggled(enabled: Boolean) {
        stateHandle.update { copy(subagentsEnabled = enabled) }
    }

    fun onSubagentAllowSelfToggled(allow: Boolean) {
        stateHandle.update { copy(subagentAllowSelf = allow) }
    }

    fun addSubagent(agentId: String) {
        val current = stateHandle.state.selectedSubagentIds
        // Upstream caps subagents at MAX_SUBAGENTS; never list the agent itself.
        if (agentId != stateHandle.state.agentId &&
            agentId !in current &&
            current.size < AgentEditorViewModel.MAX_SUBAGENTS
        ) {
            stateHandle.update { copy(selectedSubagentIds = current + agentId) }
        }
    }

    fun removeSubagent(agentId: String) {
        stateHandle.update { copy(selectedSubagentIds = selectedSubagentIds - agentId) }
    }

    // --- Chain (sequential multi-agent) ---

    fun addChainAgent(agentId: String) {
        val current = stateHandle.state.chainAgentIds
        // Upstream caps the chain at 10 agents.
        if (agentId !in current && current.size < AgentEditorViewModel.CHAIN_MAX) {
            stateHandle.update { copy(chainAgentIds = current + agentId) }
        }
    }

    fun removeChainAgent(agentId: String) {
        stateHandle.update { copy(chainAgentIds = chainAgentIds - agentId) }
    }

    // --- Handoffs (graph edges) ---

    fun addHandoffEdge(edge: HandoffEdge) {
        stateHandle.update { copy(handoffEdges = handoffEdges + edge) }
    }

    fun updateHandoffEdge(index: Int, edge: HandoffEdge) {
        val list = stateHandle.state.handoffEdges.toMutableList()
        if (index in list.indices) {
            list[index] = edge
            stateHandle.update { copy(handoffEdges = list) }
        }
    }

    fun removeHandoffEdge(index: Int) {
        val list = stateHandle.state.handoffEdges.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            stateHandle.update { copy(handoffEdges = list) }
        }
    }
}
