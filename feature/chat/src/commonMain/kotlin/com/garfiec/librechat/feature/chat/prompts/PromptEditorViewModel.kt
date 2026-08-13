package com.garfiec.librechat.feature.chat.prompts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptData
import com.garfiec.librechat.core.model.request.CreatePromptGroupData
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptGroupRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class PromptEditorUiState(
    val groupId: String? = null,
    val name: String = "",
    val oneliner: String = "",
    val command: String = "",
    val promptText: String = "",
    val variableValues: Map<String, String> = emptyMap(),
    val prompts: List<Prompt> = emptyList(),
    val productionId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val showVersionsSheet: Boolean = false,
) {
    val isNewPrompt: Boolean get() = groupId == null
}

class PromptEditorViewModel(
    private val promptRepository: PromptRepository,
    initialGroupId: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PromptEditorUiState())
    val uiState: StateFlow<PromptEditorUiState> = _uiState.asStateFlow()

    init {
        val groupId = initialGroupId
        if (groupId != null) {
            loadGroup(groupId)
        }
    }

    private fun loadGroup(groupId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val groupResult = promptRepository.getGroup(groupId)
            val promptsResult = promptRepository.getPromptsByGroupId(groupId)

            when (groupResult) {
                is Result.Success -> {
                    val group = groupResult.data
                    val prompts = (promptsResult as? Result.Success)?.data ?: emptyList()
                    val mergedGroup = group.copy(prompts = prompts)
                    val productionPrompt = mergedGroup.prompts.find { it.id == mergedGroup.productionId }
                    _uiState.value = _uiState.value.copy(
                        groupId = mergedGroup.id,
                        name = mergedGroup.name,
                        oneliner = mergedGroup.oneliner ?: "",
                        command = mergedGroup.command ?: "",
                        promptText = productionPrompt?.prompt ?: mergedGroup.prompts.firstOrNull()?.prompt ?: "",
                        prompts = mergedGroup.prompts,
                        productionId = mergedGroup.productionId,
                        isLoading = false,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = groupResult.message ?: "Failed to load prompt",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateOneliner(oneliner: String) {
        _uiState.value = _uiState.value.copy(oneliner = oneliner)
    }

    fun updateCommand(command: String) {
        _uiState.value = _uiState.value.copy(command = command.removePrefix("/"))
    }

    fun updatePromptText(text: String) {
        _uiState.value = _uiState.value.copy(promptText = text)
    }

    fun updateVariable(name: String, value: String) {
        val current = _uiState.value.variableValues.toMutableMap()
        current[name] = value
        _uiState.value = _uiState.value.copy(variableValues = current)
    }

    fun showVersionsSheet() {
        _uiState.value = _uiState.value.copy(showVersionsSheet = true)
    }

    fun hideVersionsSheet() {
        _uiState.value = _uiState.value.copy(showVersionsSheet = false)
    }

    fun setProductionTag(promptId: String) {
        val state = _uiState.value
        val groupId = state.groupId ?: return
        viewModelScope.launch {
            when (val result = promptRepository.updatePromptProductionTag(promptId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        productionId = promptId,
                        showVersionsSheet = false,
                    )
                    // Reload group to get updated state
                    loadGroup(groupId)
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message ?: "Failed to update production tag",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank() || state.promptText.isBlank()) {
            _uiState.value = state.copy(error = "Name and prompt text are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)

            if (state.isNewPrompt) {
                createNewPrompt(state)
            } else {
                updateExistingPrompt(state)
            }
        }
    }

    private suspend fun createNewPrompt(state: PromptEditorUiState) {
        val request = CreatePromptRequest(
            prompt = CreatePromptData(
                prompt = state.promptText,
                type = "text",
            ),
            group = CreatePromptGroupData(name = state.name),
        )
        when (val result = promptRepository.create(request)) {
            is Result.Success -> {
                val createdGroup = result.data
                // The oneliner and the `/` command ride on a follow-up update — the create route
                // takes neither — so its outcome decides this save's: a prompt saved without the
                // command the user typed is not findable by the command they will type. `groupId`
                // is adopted either way, so a retry updates the group just created rather than
                // minting a duplicate.
                val metadataSaved = if (state.oneliner.isNotBlank() || state.command.isNotBlank()) {
                    val updateRequest = UpdatePromptGroupRequest(
                        name = state.name,
                        oneliner = state.oneliner.ifBlank { null },
                        command = state.command.ifBlank { null },
                    )
                    val groupId = createdGroup.id
                    groupId != null && promptRepository.update(groupId, updateRequest) is Result.Success
                } else {
                    true
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saved = metadataSaved,
                    groupId = createdGroup.id,
                    error = if (metadataSaved) null else "Prompt created, but its details could not be saved",
                )
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = result.message ?: "Failed to create prompt",
                )
            }
            is Result.Loading -> { /* no-op */ }
        }
    }

    private suspend fun updateExistingPrompt(state: PromptEditorUiState) {
        val groupId = state.groupId ?: return

        // Update group metadata
        val updateRequest = UpdatePromptGroupRequest(
            name = state.name,
            oneliner = state.oneliner.ifBlank { null },
            command = state.command.ifBlank { null },
        )
        when (val result = promptRepository.update(groupId, updateRequest)) {
            is Result.Success -> {
                // A changed body is added as a new version. Those calls carry the body — the
                // metadata update above does not — and the screen pops on `saved`, so flipping it
                // without reading their results loses the user's edit behind a success animation.
                val currentProduction = state.prompts.find { it.id == state.productionId }
                val textSaved = if (currentProduction == null || currentProduction.prompt != state.promptText) {
                    publishNewVersion(groupId, state.promptText)
                } else {
                    true
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saved = textSaved,
                    error = if (textSaved) null else "Could not save the prompt text",
                )
            }
            is Result.Error -> {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = result.message ?: "Failed to update prompt",
                )
            }
            is Result.Loading -> { /* no-op */ }
        }
    }

    /**
     * Writes the edited body as a new version and makes it the live one, reporting whether both
     * halves landed.
     *
     * Adding a version does not move the group's `productionId`, so the promotion is not a
     * refinement — without it the edit is stored where nothing reads it. Every surface shows the
     * *production* body: the library row, the composer's `/` picker, and this editor when it
     * reopens.
     */
    private suspend fun publishNewVersion(groupId: String, text: String): Boolean {
        val addRequest = AddPromptToGroupRequest(
            prompt = CreatePromptData(prompt = text, type = "text"),
        )
        val added = promptRepository.addPromptToGroup(groupId, addRequest)
        // The response is the only place the new version's id appears, so no id means no promotion
        // — report the save as incomplete rather than popping the editor over an unread edit.
        val newPromptId = (added as? Result.Success)?.data?.id ?: return false
        return promptRepository.updatePromptProductionTag(newPromptId) is Result.Success
    }

    fun consumeSaved() {
        _uiState.value = _uiState.value.copy(saved = false)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
