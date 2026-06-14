package com.garfiec.librechat.feature.agents.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.model.AgentSubagentsConfig
import com.garfiec.librechat.core.model.SupportContact
import com.garfiec.librechat.core.model.request.CreateAgentRequest
import com.garfiec.librechat.core.model.request.RevertAgentRequest
import com.garfiec.librechat.core.model.request.UpdateAgentRequest
import com.garfiec.librechat.feature.agents.components.model.AgentVisibility
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorEvent
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorUiState
import com.garfiec.librechat.feature.agents.viewmodel.applyAgentData
import com.garfiec.librechat.feature.agents.viewmodel.buildModelParameters
import com.garfiec.librechat.feature.agents.viewmodel.buildToolsList
import com.garfiec.librechat.feature.agents.viewmodel.encodeHandoffEdges
import com.garfiec.librechat.feature.agents.viewmodel.encodeHandoffEdgesAlways
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Owns the editor's persistence mutations: form validation + create/update
 * ([save]), plus [duplicate], [delete], and [revertToVersion]. Assembles the
 * create/update request body from [AgentEditorUiState] (delegating the pure
 * transforms to AgentEditorMappers) and emits the corresponding
 * [AgentEditorEvent] on success so the screen can navigate.
 */
class AgentSaveDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val agentRepository: AgentRepository,
    private val filesDelegate: AgentFilesDelegate,
    private val events: MutableSharedFlow<AgentEditorEvent>,
) {

    fun save() {
        val state = stateHandle.state

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
            stateHandle.update {
                copy(
                    nameError = nameError,
                    descriptionError = descriptionError,
                    supportContactNameError = supportContactNameError,
                    supportContactEmailError = supportContactEmailError,
                )
            }
            return
        }

        stateHandle.scope.launch {
            stateHandle.update { copy(isSaving = true, error = null) }

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
            // applyAgentData re-hydrates from the saved agent rather than
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
                    stateHandle.update { copy(isSaving = false) }
                    events.emit(AgentEditorEvent.SaveSuccess(result.data.id))
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(isSaving = false, error = result.message ?: "Failed to save agent")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun duplicate() {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            stateHandle.update { copy(isDuplicating = true, showDuplicateConfirm = false) }
            when (val result = agentRepository.duplicateAgent(agentId)) {
                is Result.Success -> {
                    stateHandle.update { copy(isDuplicating = false) }
                    events.emit(AgentEditorEvent.DuplicateSuccess(result.data.id))
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(isDuplicating = false, error = result.message ?: "Failed to duplicate agent")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun delete() {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            stateHandle.update { copy(isDeleting = true, showDeleteConfirm = false) }
            when (val result = agentRepository.deleteAgent(agentId)) {
                is Result.Success -> {
                    stateHandle.update { copy(isDeleting = false) }
                    events.emit(AgentEditorEvent.DeleteSuccess)
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(isDeleting = false, error = result.message ?: "Failed to delete agent")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun revertToVersion(version: Int) {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            stateHandle.update { copy(showVersionHistory = false, isLoading = true) }
            when (val result = agentRepository.revertAgent(agentId, RevertAgentRequest(version))) {
                is Result.Success -> {
                    stateHandle.update { applyAgentData(result.data).copy(isLoading = false) }
                    // A reverted version often has a different file set (different
                    // execute_code / file_search / context attachments). Clear the
                    // stale enrichment cache and re-fetch /api/files/agent/:id so
                    // the new file_ids resolve to filename/bytes/type instead of
                    // showing bare IDs in the chips.
                    filesDelegate.resetFileCache()
                    filesDelegate.loadAgentFiles(agentId)
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(isLoading = false, error = result.message ?: "Failed to revert agent")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private companion object {
        /** Validation limits mirrored from upstream agentSchema. */
        const val NAME_MAX = 256
        const val DESCRIPTION_MAX = 512
        const val SUPPORT_NAME_MIN = 3

        // Pragmatic email regex matching upstream client-side validateEmail.
        // Server still runs its own check, so this only catches obvious typos.
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
