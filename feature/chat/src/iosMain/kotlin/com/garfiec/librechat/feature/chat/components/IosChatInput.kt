package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_attach_file
import com.garfiec.librechat.feature.chat.resources.cd_paste_image
import com.garfiec.librechat.feature.chat.viewmodel.ChatInputGates
import org.jetbrains.compose.resources.stringResource

@Composable
fun IosChatInput(
    inputText: String,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabledTools: Set<String> = emptySet(),
    onToggleTool: (String) -> Unit = {},
    mcpServers: List<McpServerDisplayData> = emptyList(),
    selectedMcpServerNames: Set<String> = emptySet(),
    onToggleMcpServer: (String) -> Unit = {},
    onOpenModelParameters: () -> Unit = {},
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onOpenModelSelector: () -> Unit = {},
    selectedModelDisplay: String? = null,
    isCodeInterpreterAvailable: Boolean = true,
    attachedFiles: List<AttachedFile> = emptyList(),
    onRemoveFile: (AttachedFile) -> Unit = {},
    hasClipboardImage: Boolean = false,
    onPasteImage: (() -> Unit)? = null,
    onAttachFiles: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickPhotos: () -> Unit = {},
    webSearchEnabled: Boolean = true,
    runCodeEnabled: Boolean = true,
    fileSearchEnabled: Boolean = true,
    mcpServersEnabled: Boolean = true,
    gates: ChatInputGates = ChatInputGates(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showToolsSheet by remember { mutableStateOf(false) }

    val state = ChatInputState(
        inputText = inputText,
        isStreaming = isStreaming,
        isRecording = isRecording,
        isTranscribing = isTranscribing,
        enabledTools = enabledTools,
        mcpServers = mcpServers,
        selectedMcpServerNames = selectedMcpServerNames,
        selectedModelDisplay = selectedModelDisplay,
        isCodeInterpreterAvailable = isCodeInterpreterAvailable,
        attachedFiles = attachedFiles,
        gates = gates,
    )

    CommonChatInputCore(
        state = state,
        onSend = onSend,
        onStop = onStop,
        onRemoveFile = onRemoveFile,
        modifier = modifier,
        leadingButtons = {
            // "+" button to open tools bottom sheet (matches Android behavior)
            FilledTonalIconButton(
                onClick = { showToolsSheet = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(Res.string.cd_attach_file),
                )
            }

            // Paste image button (shown when clipboard has image content)
            if (hasClipboardImage && onPasteImage != null) {
                IconButton(
                    onClick = onPasteImage,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = stringResource(Res.string.cd_paste_image),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
        },
        textFieldContent = {
            // Text field with mic button overlaid at trailing edge.
            // The mic is a separate composable (not trailingIcon) to
            // avoid iOS text-magnifier gesture conflicts, but visually
            // it sits inside the field's rounded border.
            Box(
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 160.dp),
                    placeholder = {
                        ChatInputPlaceholder(
                            isRecording = isRecording,
                            selectedModelDisplay = selectedModelDisplay,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = ChatInputDefaults.shape,
                    colors = ChatInputDefaults.textFieldColors(),
                    keyboardOptions = ChatInputDefaults.keyboardOptions,
                    keyboardActions = KeyboardActions.Default,
                    maxLines = 6,
                    // Invisible spacer reserves room for the overlaid mic button
                    trailingIcon = {
                        Spacer(modifier = Modifier.width(36.dp))
                    },
                )

                // Mic button overlaid at trailing edge of the text field
                IconButton(
                    onClick = {
                        if (isRecording) {
                            onStopRecording()
                        } else {
                            onStartRecording()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    enabled = !isTranscribing,
                ) {
                    VoiceMicIndicator(
                        isRecording = isRecording,
                        isTranscribing = isTranscribing,
                    )
                }
            }
        },
        trailingSpacer = {
            Spacer(modifier = Modifier.width(4.dp))
        },
        bottomContent = {
            // Snackbar for file attachment toast
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
    )

    // Tools bottom sheet with native iOS file picker actions
    if (showToolsSheet) {
        ChatToolsBottomSheet(
            enabledTools = enabledTools,
            onToggleTool = onToggleTool,
            mcpServers = mcpServers,
            selectedMcpServerNames = selectedMcpServerNames,
            onToggleMcpServer = onToggleMcpServer,
            onAttachFiles = {
                showToolsSheet = false
                onAttachFiles()
            },
            onTakePhoto = {
                showToolsSheet = false
                onTakePhoto()
            },
            onPickPhotos = {
                showToolsSheet = false
                onPickPhotos()
            },
            onOpenModelParameters = {
                showToolsSheet = false
                onOpenModelParameters()
            },
            onOpenModelSelector = {
                showToolsSheet = false
                onOpenModelSelector()
            },
            selectedModelDisplay = selectedModelDisplay,
            onDismiss = { showToolsSheet = false },
            isCodeInterpreterAvailable = isCodeInterpreterAvailable,
            webSearchEnabled = webSearchEnabled,
            runCodeEnabled = runCodeEnabled,
            fileSearchEnabled = fileSearchEnabled,
            mcpServersEnabled = mcpServersEnabled,
            gates = gates,
        )
    }
}
