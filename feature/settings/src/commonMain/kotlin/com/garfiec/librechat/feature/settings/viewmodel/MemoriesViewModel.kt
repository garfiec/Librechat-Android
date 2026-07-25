package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.MemoryRepository
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.core.model.request.CreateMemoryRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryPreferencesRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class MemoriesUiState(
    val memories: List<Memory> = emptyList(),
    val memoriesEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val showDialog: Boolean = false,
    val editingMemory: Memory? = null,
)

class MemoriesViewModel(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoriesUiState())
    val uiState: StateFlow<MemoriesUiState> = _uiState.asStateFlow()

    init {
        loadMemories()
    }

    fun loadMemories() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.memories.isEmpty())
            when (val result = memoryRepository.getMemories()) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        memories = result.data,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = result.message ?: "Failed to load memories",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadMemories()
    }

    fun toggleMemoriesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            when (val result = memoryRepository.updatePreferences(
                UpdateMemoryPreferencesRequest(enabled = enabled),
            )) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(memoriesEnabled = result.data.enabled)
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to update preferences",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showDialog = true, editingMemory = null)
    }

    fun showEditDialog(memory: Memory) {
        _uiState.value = _uiState.value.copy(showDialog = true, editingMemory = memory)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(showDialog = false, editingMemory = null)
    }

    fun saveMemory(key: String, value: String) {
        viewModelScope.launch {
            val editing = _uiState.value.editingMemory
            val result = if (editing != null) {
                memoryRepository.updateMemory(
                    key = editing.key,
                    request = UpdateMemoryRequest(value = value),
                    agentId = editing.agentId,
                )
            } else {
                memoryRepository.createMemory(
                    request = CreateMemoryRequest(key = key, value = value),
                )
            }
            when (result) {
                is Result.Success -> {
                    dismissDialog()
                    loadMemories()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to save memory",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun deleteMemory(memory: Memory) {
        viewModelScope.launch {
            when (val result = memoryRepository.deleteMemory(memory.key, memory.agentId)) {
                is Result.Success -> loadMemories()
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to delete memory",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
