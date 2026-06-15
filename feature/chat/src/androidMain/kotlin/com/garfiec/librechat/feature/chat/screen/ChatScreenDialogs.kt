package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.ui.components.ModelParameterSheet
import com.garfiec.librechat.feature.chat.components.ForkOptionsBottomSheet
import com.garfiec.librechat.feature.chat.components.ModelSelectorSheet
import com.garfiec.librechat.feature.chat.components.PresetPicker
import com.garfiec.librechat.feature.chat.components.SavePresetDialog
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Hosts the chat screen's transient dialogs and bottom sheets — preset load/save,
 * fork options, model parameters, rename/delete confirmations, and the primary +
 * secondary model selectors — so [ChatScreen] only has to declare which are open.
 * Local-only visibility (preset picker, save-preset, secondary model sheet) is
 * hoisted to the caller via the boolean flags and their setters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreenDialogs(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    sendBlockMessage: String?,
    showPresetPicker: Boolean,
    showSavePresetDialog: Boolean,
    showSecondaryModelSheet: Boolean,
    onSetShowPresetPicker: (Boolean) -> Unit,
    onSetShowSavePresetDialog: (Boolean) -> Unit,
    onSetShowSecondaryModelSheet: (Boolean) -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    if (showPresetPicker) {
        PresetPicker(
            presets = uiState.presets,
            onPresetSelect = { preset ->
                viewModel.loadPreset(preset)
                onSetShowPresetPicker(false)
            },
            onDismiss = { onSetShowPresetPicker(false) },
            onEditPreset = { preset ->
                viewModel.loadPreset(preset)
                onSetShowPresetPicker(false)
            },
            onDeletePreset = { preset ->
                preset.presetId?.let { viewModel.deletePreset(it) }
            },
        )
    }

    if (showSavePresetDialog) {
        SavePresetDialog(
            currentEndpoint = uiState.selectedEndpoint,
            currentModel = uiState.selectedModel,
            onSave = { name ->
                viewModel.savePreset(name)
                onSetShowSavePresetDialog(false)
            },
            onDismiss = { onSetShowSavePresetDialog(false) },
        )
    }

    if (uiState.showForkOptionsForMessageId != null) {
        ForkOptionsBottomSheet(
            onDismiss = viewModel::dismissForkOptions,
            onFork = { option, splitAtTarget ->
                viewModel.forkFromMessage(
                    messageId = uiState.showForkOptionsForMessageId!!,
                    option = option,
                    splitAtTarget = splitAtTarget,
                )
            },
        )
    }

    if (uiState.showModelParameters) {
        val activeAgent = remember(uiState.agents, uiState.selectedModel, uiState.selectedEndpoint) {
            if (uiState.selectedEndpoint == EndpointConstants.AGENTS) {
                uiState.agents.find { it.id == uiState.selectedModel }
            } else {
                null
            }
        }
        ModelParameterSheet(
            parameters = uiState.modelParameters,
            onParametersChange = viewModel::updateModelParameters,
            onDismiss = viewModel::hideModelParameters,
            selectedEndpoint = uiState.selectedEndpoint,
            extendedEffortSupported = uiState.extendedEffortSupported,
            selectedProvider = activeAgent?.provider,
            selectedModel = activeAgent?.model ?: uiState.selectedModel,
            onSaveAsPreset = {
                viewModel.hideModelParameters()
                onSetShowSavePresetDialog(true)
            },
        )
    }

    if (uiState.showRenameDialog) {
        ChatRenameDialog(
            currentTitle = uiState.conversationTitle ?: "",
            onDismiss = viewModel::dismissRenameDialog,
            onConfirm = viewModel::renameConversation,
        )
    }

    if (uiState.showDeleteConfirmation) {
        ChatDeleteConfirmationDialog(
            conversationTitle = uiState.conversationTitle ?: "this conversation",
            onDismiss = viewModel::dismissDeleteConfirmation,
            onConfirm = viewModel::deleteConversation,
        )
    }

    if (uiState.showModelSheet) {
        ModelSelectorSheet(
            endpointConfigs = uiState.endpointConfigs,
            availableModels = uiState.availableModels,
            agents = uiState.agents,
            selectedEndpoint = uiState.selectedEndpoint,
            selectedModel = uiState.selectedModel,
            onModelSelect = { endpoint, model ->
                viewModel.onModelSelected(endpoint, model)
                // Clear any pending scaffold-level snackbar for the same error so it
                // doesn't flash behind the sheet's close animation. Harmless no-op when
                // error is already null.
                viewModel.dismissError()
                viewModel.dismissSendBlockReason()
                viewModel.dismissModelSheet()
            },
            onDismiss = {
                viewModel.dismissError()
                viewModel.dismissSendBlockReason()
                viewModel.dismissModelSheet()
            },
            serverUrl = uiState.serverUrl,
            // Send-block reasons take precedence: when set, the sheet was auto-opened
            // to help the user resolve the block, so surface that context inline.
            errorMessage = sendBlockMessage ?: uiState.error,
            onErrorDismiss = {
                viewModel.dismissSendBlockReason()
                viewModel.dismissError()
            },
            favoriteAgentIds = uiState.favoriteAgentIds,
            favoriteModelKeys = uiState.favoriteModelKeys,
            onToggleAgentFavorite = viewModel::toggleAgentFavorite,
            onToggleModelFavorite = viewModel::toggleModelFavorite,
            starredDisplay = uiState.starredModelsDisplay,
            endpointKeyStates = uiState.endpointKeyStates,
            onSetApiKey = { name -> onNavigateToProviderKeys(name) },
        )
    }

    // Secondary model selector sheet for comparison mode
    if (showSecondaryModelSheet) {
        ModelSelectorSheet(
            endpointConfigs = uiState.endpointConfigs,
            availableModels = uiState.availableModels,
            agents = uiState.agents,
            selectedEndpoint = uiState.comparisonState.secondaryEndpoint,
            selectedModel = uiState.comparisonState.secondaryModel,
            onModelSelect = { endpoint, model ->
                viewModel.setSecondaryModel(endpoint, model)
                onSetShowSecondaryModelSheet(false)
            },
            onDismiss = { onSetShowSecondaryModelSheet(false) },
            serverUrl = uiState.serverUrl,
            favoriteAgentIds = uiState.favoriteAgentIds,
            favoriteModelKeys = uiState.favoriteModelKeys,
            onToggleAgentFavorite = viewModel::toggleAgentFavorite,
            onToggleModelFavorite = viewModel::toggleModelFavorite,
            starredDisplay = uiState.starredModelsDisplay,
            endpointKeyStates = uiState.endpointKeyStates,
            onSetApiKey = { name -> onNavigateToProviderKeys(name) },
        )
    }
}

@Composable
private fun ChatRenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_title_rename)) },
        text = {
            Column {
                Text(
                    text = "Enter a new title for this conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.hint_title)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(Res.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun ChatDeleteConfirmationDialog(
    conversationTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_title_delete_conversation)) },
        text = {
            Text(
                text = "Are you sure you want to delete \"$conversationTitle\"? This action cannot be undone.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
