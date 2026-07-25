package com.garfiec.librechat.feature.settings.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.MemoryRepository
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.core.model.request.CreateMemoryRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryPreferencesRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryRequest
import com.garfiec.librechat.feature.settings.viewmodel.SettingsStateHandle
import kotlinx.coroutines.launch

/**
 * Handles memory CRUD operations and memory preferences.
 */
class MemoryManagementDelegate(
    private val stateHandle: SettingsStateHandle,
    private val memoryRepository: MemoryRepository,
) {

    fun loadMemories() {
        stateHandle.scope.launch {
            when (val result = memoryRepository.getMemories()) {
                is Result.Success -> {
                    stateHandle.update { copy(memories = result.data) }
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load memories: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun showAddMemoryDialog() {
        stateHandle.update { copy(showMemoryDialog = true, editingMemory = null) }
    }

    fun showEditMemoryDialog(memory: Memory) {
        stateHandle.update { copy(showMemoryDialog = true, editingMemory = memory) }
    }

    fun dismissMemoryDialog() {
        stateHandle.update { copy(showMemoryDialog = false, editingMemory = null) }
    }

    fun saveMemory(key: String, value: String) {
        stateHandle.scope.launch {
            val editing = stateHandle.state.editingMemory
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
                    dismissMemoryDialog()
                    loadMemories()
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to save memory") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun deleteMemory(memory: Memory) {
        stateHandle.scope.launch {
            when (val result = memoryRepository.deleteMemory(memory.key, memory.agentId)) {
                is Result.Success -> loadMemories()
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to delete memory") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun toggleMemoriesEnabled(enabled: Boolean) {
        stateHandle.scope.launch {
            when (val result = memoryRepository.updatePreferences(
                UpdateMemoryPreferencesRequest(enabled = enabled),
            )) {
                is Result.Success -> {
                    stateHandle.update { copy(memoriesEnabled = result.data.enabled) }
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to update memory preferences") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
