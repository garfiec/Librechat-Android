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
import com.garfiec.librechat.feature.chat.components.ForkOptionsBottomSheet
import com.garfiec.librechat.feature.chat.components.PresetPicker
import com.garfiec.librechat.feature.chat.components.SavePresetDialog
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Hosts the chat screen's transient dialogs and bottom sheets — preset load/save,
 * fork options, rename/delete confirmations, and the primary + secondary *standalone*
 * model selectors — so [ChatScreen] only has to declare which are open.
 * Local-only visibility (preset picker, save-preset, secondary model sheet) is
 * hoisted to the caller via the boolean flags and their setters.
 *
 * The chat options sheet — the "+" menu, with the model selector and model parameters as
 * swappable pages — is hosted separately in [ChatScreen], since two entry points open it.
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
        PrimaryModelSelectorSheet(
            uiState = uiState,
            viewModel = viewModel,
            sendBlockMessage = sendBlockMessage,
            onNavigateToProviderKeys = onNavigateToProviderKeys,
        )
    }

    // Secondary model selector sheet for comparison mode
    if (showSecondaryModelSheet) {
        SecondaryModelSelectorSheet(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { onSetShowSecondaryModelSheet(false) },
            onNavigateToProviderKeys = onNavigateToProviderKeys,
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
