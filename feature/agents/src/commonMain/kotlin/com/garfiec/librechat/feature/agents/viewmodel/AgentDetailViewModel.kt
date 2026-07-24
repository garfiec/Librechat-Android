package com.garfiec.librechat.feature.agents.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.feature.agents.AgentDetailDisplayData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class AgentDetailUiState(
    val agent: AgentDetailDisplayData? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val canEdit: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val isDuplicating: Boolean = false,
)

sealed interface AgentDetailEvent {
    data object Deleted : AgentDetailEvent
    data class Duplicated(val agentId: String) : AgentDetailEvent
}

class AgentDetailViewModel(
    private val agentRepository: AgentRepository,
    private val serverDataStore: ServerDataStore,
    initialAgentId: String? = null,
) : ViewModel() {

    private val agentId: String = checkNotNull(initialAgentId) { "agentId must be provided" }

    private val _uiState = MutableStateFlow(AgentDetailUiState())
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AgentDetailEvent>()
    val events: SharedFlow<AgentDetailEvent> = _events.asSharedFlow()

    init {
        loadAgent()
    }

    fun loadAgent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            // Try the expanded endpoint first (returns full agent data including
            // description, category, tools, conversation_starters). It requires
            // EDIT permission server-side, so a 403 there means the user may view
            // but not edit this agent. Any other failure is transient/unknown, so
            // stay optimistic rather than hiding Edit from an owner during a flaky
            // request — the editor re-checks permission on entry. Fall back to the
            // standard view-only endpoint for the displayed data.
            val expanded = agentRepository.getAgentForEditing(agentId)
            val canEdit = expanded !is Result.Error || !expanded.isPermissionDenied()
            val result = when (expanded) {
                is Result.Success -> expanded
                else -> agentRepository.getAgent(agentId)
            }
            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        agent = result.data.toDetailDisplayData(),
                        canEdit = canEdit,
                        isLoading = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        canEdit = false,
                        error = result.message ?: "Failed to load agent",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun showDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = true)
    }

    fun dismissDeleteConfirmation() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = false)
    }

    fun deleteAgent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDeleting = true,
                showDeleteDialog = false,
                error = null,
            )
            when (val result = agentRepository.deleteAgent(agentId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isDeleting = false)
                    _events.emit(AgentDetailEvent.Deleted)
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

    fun duplicateAgent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDuplicating = true, error = null)
            when (val result = agentRepository.duplicateAgent(agentId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(isDuplicating = false)
                    _events.emit(AgentDetailEvent.Duplicated(result.data.id))
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

    private fun Result.Error.isPermissionDenied(): Boolean =
        (exception as? ApiException)?.statusCode == HTTP_FORBIDDEN

    private fun Agent.toDetailDisplayData(): AgentDetailDisplayData {
        val resolvedUrl = avatarUrl?.let { url ->
            if (url.startsWith("http")) {
                url
            } else {
                "${serverDataStore.getBaseUrl()}$url"
            }
        }
        return AgentDetailDisplayData(
            id = id,
            name = name ?: "Unnamed Agent",
            description = description,
            avatarUrl = resolvedUrl,
            author = author,
            authorName = authorName,
            model = model,
            category = category,
            tools = tools,
            conversationStarters = conversationStarters,
        )
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }
}
