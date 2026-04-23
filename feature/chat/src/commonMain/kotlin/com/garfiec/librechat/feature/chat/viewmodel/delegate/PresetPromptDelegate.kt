package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.model.EModelEndpoint
import com.garfiec.librechat.core.model.Preset
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import kotlinx.coroutines.launch

class PresetPromptDelegate(
    private val stateHandle: ChatStateHandle,
    private val presetRepository: PresetRepository,
    private val promptRepository: PromptRepository,
) {

    // Keep domain objects for internal operations (loadPreset, handlePromptMention, etc.)
    private var cachedPresets: List<Preset> = emptyList()
    private var cachedPromptGroups: List<PromptGroup> = emptyList()

    fun loadPresets() {
        stateHandle.scope.launch {
            when (val result = presetRepository.getAll()) {
                is Result.Success -> {
                    cachedPresets = result.data
                    stateHandle.update {
                        copy(presets = result.data.map { it.toDisplayData() })
                    }
                }
                is Result.Error -> {
                    // Presets are non-critical; don't block the user
                    Logger.d(result.exception) { "Failed to load presets: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun loadAvailablePrompts() {
        stateHandle.scope.launch {
            when (val result = promptRepository.getGroups(pageSize = 100)) {
                is Result.Success -> {
                    cachedPromptGroups = result.data.promptGroups
                    stateHandle.update {
                        copy(availablePrompts = result.data.promptGroups.map { it.toDisplayData() })
                    }
                }
                is Result.Error -> {
                    // Prompts are non-critical; don't block the user
                    Logger.d(result.exception) { "Failed to load prompts: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun savePreset(name: String) {
        val state = stateHandle.state
        val preset = Preset(
            title = name,
            endpoint = try {
                EModelEndpoint.valueOf(state.selectedEndpoint.uppercase())
            } catch (_: IllegalArgumentException) {
                null
            },
            model = state.selectedModel,
        )
        stateHandle.scope.launch {
            try {
                presetRepository.create(preset)
                loadPresets()
            } catch (e: Exception) {
                Logger.e(e) { "Could not save preset" }
                stateHandle.update { copy(error = "Could not save preset") }
            }
        }
    }

    fun loadPreset(displayData: PresetDisplayData) {
        val preset = cachedPresets.find { it.presetId == displayData.presetId }
        stateHandle.update {
            copy(
                selectedEndpoint = preset?.endpoint?.name?.lowercase() ?: selectedEndpoint,
                selectedModel = preset?.model ?: selectedModel,
            )
        }
    }

    fun deletePreset(presetId: String) {
        stateHandle.scope.launch {
            when (presetRepository.delete(presetId)) {
                is Result.Success -> loadPresets()
                is Result.Error -> {
                    stateHandle.update { copy(error = "Could not delete preset") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun editPreset(preset: Preset) {
        stateHandle.scope.launch {
            when (presetRepository.update(preset)) {
                is Result.Success -> loadPresets()
                is Result.Error -> {
                    stateHandle.update { copy(error = "Could not update preset") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun handlePromptMention(displayData: PromptMentionDisplayData) {
        val currentInput = stateHandle.state.inputText
        val atIndex = currentInput.lastIndexOf('@')
        val newText = if (atIndex >= 0) {
            currentInput.substring(0, atIndex) + (displayData.command ?: displayData.name) + " "
        } else {
            currentInput + (displayData.command ?: displayData.name) + " "
        }
        stateHandle.update { copy(inputText = newText) }
        recordUseFor(displayData)
    }

    fun handleSlashCommand(displayData: PromptMentionDisplayData) {
        // Look up the full domain object to access the prompts list
        val group = cachedPromptGroups.find {
            it.name == displayData.name && it.command == displayData.command
        }
        val promptText = if (group != null) {
            val productionId = group.productionId
            if (productionId != null) {
                group.prompts.find { it.id == productionId }?.prompt
            } else {
                group.prompts.firstOrNull()?.prompt
            }
        } else {
            null
        }
        stateHandle.update {
            copy(inputText = promptText ?: (displayData.command ?: displayData.name))
        }
        recordUseFor(displayData)
    }

    /**
     * Fire-and-forget telemetry ping for `POST /api/prompts/groups/:id/use` (v0.8.5+).
     * Errors are swallowed — analytics is never a user-facing failure.
     */
    private fun recordUseFor(displayData: PromptMentionDisplayData) {
        val group = cachedPromptGroups.find {
            it.name == displayData.name && it.command == displayData.command
        } ?: return
        val groupId = group.id ?: return
        stateHandle.scope.launch {
            when (val result = promptRepository.recordPromptGroupUse(groupId)) {
                is Result.Error -> Logger.d(result.exception) { "recordPromptGroupUse failed (non-fatal): ${result.message}" }
                else -> Unit
            }
        }
    }
}

// --- Display data mapping extensions ---

internal fun Preset.toDisplayData() = PresetDisplayData(
    presetId = presetId,
    title = title ?: "Untitled Preset",
    endpointLabel = endpoint?.name?.lowercase(),
    model = model,
)

internal fun PromptGroup.toDisplayData() = PromptMentionDisplayData(
    name = name,
    command = command,
    oneliner = oneliner,
)
