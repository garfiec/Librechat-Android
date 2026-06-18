package com.garfiec.librechat.feature.chat.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.viewmodel.ChatInputGates
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
fun ChatInput(
    inputText: String,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onQueue: () -> Unit = {},
    canQueue: Boolean = false,
    queuedPausedCount: Int = 0,
    onSendQueuedMessages: () -> Unit = {},
    isEditingQueued: Boolean = false,
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    queuedMessages: List<QueuedMessage> = emptyList(),
    onEditQueuedMessage: (localId: String) -> Unit = {},
    onCancelQueuedMessage: (localId: String) -> Unit = {},
    onReorderQueuedMessages: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    fontSizeMultiplier: Float = 1f,
    attachedFiles: List<AttachedFile> = emptyList(),
    onFilesSelected: (List<Uri>) -> Unit = {},
    onRemoveFile: (AttachedFile) -> Unit = {},
    promptSuggestions: List<PromptMentionDisplayData> = emptyList(),
    onPromptSelected: (PromptMentionDisplayData) -> Unit = {},
    onSlashCommandSelected: (PromptMentionDisplayData) -> Unit = {},
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onImagePasted: ((Uri) -> Unit)? = null,
    enabledTools: Set<String> = emptySet(),
    onToggleTool: (String) -> Unit = {},
    mcpServers: List<McpServerDisplayData> = emptyList(),
    selectedMcpServerNames: Set<String> = emptySet(),
    onToggleMcpServer: (String) -> Unit = {},
    onOpenModelParameters: () -> Unit = {},
    onOpenModelSelector: () -> Unit = {},
    selectedModelDisplay: String? = null,
    isCodeInterpreterAvailable: Boolean = true,
    webSearchEnabled: Boolean = true,
    runCodeEnabled: Boolean = true,
    fileSearchEnabled: Boolean = true,
    mcpServersEnabled: Boolean = true,
    gates: ChatInputGates = ChatInputGates(),
) {
    val cdOpenToolsMenu = stringResource(Res.string.cd_open_tools_menu)
    val cdPasteImage = stringResource(Res.string.cd_paste_image)
    val cdMessageInput = stringResource(Res.string.cd_message_input)
    val cdStopVoiceRec = stringResource(Res.string.cd_stop_voice_recording)
    val cdStartVoiceRec = stringResource(Res.string.cd_start_voice_recording)
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onFilesSelected(uris)
        }
    }

    // Photo picker (gallery) launcher using modern PickMultipleVisualMedia
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onFilesSelected(uris)
        }
    }

    // Camera launcher: stores the photo in a temp file via FileProvider
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraPhotoUri
        if (success && uri != null) {
            onFilesSelected(listOf(uri))
        }
        cameraPhotoUri = null
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            val photoFile = createCameraPhotoFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile,
            )
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val onTakePhoto: () -> Unit = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            val photoFile = createCameraPhotoFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile,
            )
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val onPickPhotos: () -> Unit = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    val focusRequester = remember { FocusRequester() }
    var showToolsSheet by remember { mutableStateOf(false) }

    // Use TextFieldValue internally so we can control cursor position.
    // When inputText changes externally (e.g. STT result, prompt insertion),
    // place the cursor at the end of the new text.
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(inputText, selection = TextRange(inputText.length)))
    }
    if (textFieldValue.text != inputText) {
        textFieldValue = TextFieldValue(inputText, selection = TextRange(inputText.length))
    }

    // Badge suppressed on agents endpoint (retained state restores on concrete models).
    val hasActiveTools by remember(enabledTools, selectedMcpServerNames, gates.showEphemeralTools) {
        derivedStateOf {
            gates.showEphemeralTools && (enabledTools.isNotEmpty() || selectedMcpServerNames.isNotEmpty())
        }
    }

    // Detect @mention query from input text
    val mentionQuery by remember(inputText) {
        derivedStateOf { parseMentionQuery(inputText) }
    }

    val filteredPrompts by remember(mentionQuery, promptSuggestions) {
        derivedStateOf {
            val query = mentionQuery
            if (query != null) filterMatchingPrompts(query, promptSuggestions) else emptyList()
        }
    }

    // Detect slash command query: "/" at position 0
    val slashQuery by remember(inputText) {
        derivedStateOf { parseSlashQuery(inputText) }
    }

    val filteredSlashCommands by remember(slashQuery, promptSuggestions) {
        derivedStateOf {
            val query = slashQuery
            if (query != null) filterMatchingSlashCommands(query, promptSuggestions) else emptyList()
        }
    }

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
        canQueue = canQueue,
        isEditingQueued = isEditingQueued,
    )

    CommonChatInputCore(
        state = state,
        onSend = onSend,
        onStop = onStop,
        onQueue = onQueue,
        queuedPausedCount = queuedPausedCount,
        onSendQueuedMessages = onSendQueuedMessages,
        onCommitEdit = onCommitEdit,
        onCancelEdit = onCancelEdit,
        queuedMessages = queuedMessages,
        onEditQueuedMessage = onEditQueuedMessage,
        onCancelQueuedMessage = onCancelQueuedMessage,
        onReorderQueuedMessages = onReorderQueuedMessages,
        fontSizeMultiplier = fontSizeMultiplier,
        onRemoveFile = onRemoveFile,
        modifier = modifier,
        leadingButtons = {
            // "+" button to open tools bottom sheet
            Box {
                FilledTonalIconButton(
                    onClick = { showToolsSheet = true },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = cdOpenToolsMenu
                            role = Role.Button
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                }
                // Active tools badge
                if (hasActiveTools) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
            }

            // Paste image button (shown only when clipboard has image content)
            if (onImagePasted != null) {
                IconButton(
                    onClick = {
                        // Clipboard image pasting is handled by the screen
                        onImagePasted(Uri.EMPTY)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = cdPasteImage
                            role = Role.Button
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
        },
        textFieldContent = {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onInputChanged(newValue.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 160.dp)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = cdMessageInput
                        },
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
                    trailingIcon = {
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
                                .semantics {
                                    contentDescription = if (isRecording) {
                                        cdStopVoiceRec
                                    } else {
                                        cdStartVoiceRec
                                    }
                                    role = Role.Button
                                },
                            enabled = !isTranscribing,
                        ) {
                            VoiceMicIndicator(
                                isRecording = isRecording,
                                isTranscribing = isTranscribing,
                            )
                        }
                    },
                )

                // @mention dropdown
                DropdownMenu(
                    expanded = filteredPrompts.isNotEmpty() && filteredSlashCommands.isEmpty(),
                    onDismissRequest = { /* Dismissed by typing or selecting */ },
                ) {
                    filteredPrompts.forEach { group ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val oneliner = group.oneliner
                                    if (!oneliner.isNullOrBlank()) {
                                        Text(
                                            text = oneliner,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            onClick = { onPromptSelected(group) },
                        )
                    }
                }

                // Slash command dropdown
                SlashCommandMenu(
                    filteredCommands = filteredSlashCommands,
                    onCommandSelect = onSlashCommandSelected,
                )
            }
        },
        trailingSpacer = {
            Spacer(modifier = Modifier.width(8.dp))
        },
    )

    // Tools bottom sheet
    if (showToolsSheet) {
        ChatToolsBottomSheet(
            enabledTools = enabledTools,
            onToggleTool = onToggleTool,
            mcpServers = mcpServers,
            selectedMcpServerNames = selectedMcpServerNames,
            onToggleMcpServer = onToggleMcpServer,
            onAttachFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
            onTakePhoto = onTakePhoto,
            onPickPhotos = onPickPhotos,
            onOpenModelParameters = onOpenModelParameters,
            onOpenModelSelector = onOpenModelSelector,
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

/**
 * Creates a temporary file for the camera to write a photo into.
 * Stored in the app's cache directory under `camera_photos/` which is
 * registered in the FileProvider paths XML.
 */
private fun createCameraPhotoFile(context: Context): File {
    val cameraDir = File(context.cacheDir, "camera_photos")
    if (!cameraDir.exists()) {
        cameraDir.mkdirs()
    }
    return File.createTempFile("photo_", ".jpg", cameraDir)
}
