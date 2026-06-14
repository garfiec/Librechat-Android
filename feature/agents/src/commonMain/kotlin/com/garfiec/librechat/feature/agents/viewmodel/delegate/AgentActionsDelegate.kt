package com.garfiec.librechat.feature.agents.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.model.ActionMetadata
import com.garfiec.librechat.core.model.request.CreateActionRequest
import com.garfiec.librechat.core.model.request.FunctionTool
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.toDisplayData
import kotlinx.coroutines.launch

/**
 * Owns the agent's OpenAPI actions: loading the agent's existing actions and
 * the add/update/delete CRUD against `POST/DELETE /api/agents/:id/actions`.
 * Actions require a saved agent, so every mutation reads the current agent id
 * from state and no-ops when absent.
 */
class AgentActionsDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val agentRepository: AgentRepository,
    private val editAgentId: String?,
) {

    fun loadActions() {
        stateHandle.scope.launch {
            when (val result = agentRepository.getAgentActions()) {
                is Result.Success -> {
                    val agentId = editAgentId ?: return@launch
                    val agentActions = result.data
                        .filter { it.agentId == agentId }
                        .map { it.toDisplayData() }
                    stateHandle.update { copy(actions = agentActions) }
                }
                is Result.Error -> { /* Actions are optional */ }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun saveAction(
        actionId: String?,
        metadata: ActionMetadata,
        functions: List<FunctionTool>,
    ) {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            stateHandle.update { copy(isSaving = true, error = null) }
            val request = CreateActionRequest(
                actionId = actionId,
                metadata = metadata,
                functions = functions,
            )
            when (val result = agentRepository.addOrUpdateAction(agentId, request)) {
                is Result.Success -> {
                    val (_, action) = result.data
                    val existing = stateHandle.state.actions.toMutableList()
                    val idx = existing.indexOfFirst { it.actionId == action.actionId }
                    if (idx >= 0) {
                        existing[idx] = action.toDisplayData()
                    } else {
                        existing.add(action.toDisplayData())
                    }
                    stateHandle.update { copy(actions = existing, isSaving = false) }
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(error = result.message ?: "Failed to save action", isSaving = false)
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun deleteAction(actionId: String) {
        val agentId = stateHandle.state.agentId ?: return
        stateHandle.scope.launch {
            when (val result = agentRepository.deleteAction(agentId, actionId)) {
                is Result.Success -> {
                    stateHandle.update { copy(actions = actions.filter { it.actionId != actionId }) }
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to delete action") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
