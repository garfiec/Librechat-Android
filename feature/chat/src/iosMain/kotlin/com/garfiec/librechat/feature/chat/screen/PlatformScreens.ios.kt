package com.garfiec.librechat.feature.chat.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.feature.chat.components.InConvoSearchBar
import com.garfiec.librechat.feature.chat.components.IosChatInput
import com.garfiec.librechat.feature.chat.components.LandingContent
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.MessageList
import com.garfiec.librechat.feature.chat.components.ModelSelectorButton
import com.garfiec.librechat.feature.chat.components.ModelSelectorSheet
import com.garfiec.librechat.feature.chat.components.PresetPicker
import com.garfiec.librechat.feature.chat.components.SavePresetDialog
import com.garfiec.librechat.feature.chat.components.TempChatToggle
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.clipboardHasImage
import com.garfiec.librechat.feature.chat.util.openCamera
import com.garfiec.librechat.feature.chat.util.openDocumentPicker
import com.garfiec.librechat.feature.chat.util.openPhotoPicker
import com.garfiec.librechat.feature.chat.util.readClipboardImage
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import com.garfiec.librechat.feature.chat.viewmodel.asString
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ChatScreen(
    modifier: Modifier,
    conversationId: String?,
    initialAgentId: String?,
    onConversationStart: ((String) -> Unit)?,
    onNavigateToConversation: ((String) -> Unit)?,
    onOpenDrawer: (() -> Unit)?,
    onNavigateToPromptsLibrary: (() -> Unit)?,
    onNavigateBack: (() -> Unit)?,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    val viewModel: ChatViewModel = koinViewModel { parametersOf(conversationId, initialAgentId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val attachedFiles by viewModel.attachedFiles.collectAsStateWithLifecycle()
    val prefs by viewModel.chatPreferences.collectAsStateWithLifecycle()

    val isLandingPage = uiState.screenState == ChatScreenState.LANDING
    val isLoading = uiState.screenState == ChatScreenState.LOADING
    val hasMessages = uiState.displayMessages.isNotEmpty() || uiState.isStreaming

    val useKatex = prefs.latexRenderer == LatexRenderer.KATEX
    val fontSizeMultiplier = when (uiState.chatFontSize) {
        ChatFontSize.SMALL -> 0.85f
        ChatFontSize.MEDIUM -> 1.0f
        ChatFontSize.LARGE -> 1.2f
    }

    // Header/composer model labels (agent name vs model name, with the "never a raw
    // model string under agents" rule). Shared with Android via rememberChatModelLabel.
    val (agentName, displayModel) = rememberChatModelLabel(
        selectedEndpoint = uiState.selectedEndpoint,
        selectedModel = uiState.selectedModel,
        agents = uiState.agents,
    )

    // Navigate when a new conversation is created from landing page.
    // Navigate first, then reset — matches Android order so the new
    // ViewModel can resume the active stream before the old one cancels it.
    if (onConversationStart != null) {
        LaunchedEffect(uiState.pendingNavigationConversationId) {
            val navId = uiState.pendingNavigationConversationId
            if (navId != null) {
                onConversationStart(navId)
                viewModel.onPendingNavigationHandled()
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Check clipboard for image content
    var hasClipboardImage by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasClipboardImage = clipboardHasImage()
    }

    // Show errors in snackbar (matches Android behavior)
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null) {
            snackbarHostState.showSnackbar(
                message = error,
                actionLabel = "Dismiss",
            )
            viewModel.dismissError()
        }
    }

    UserKeyErrorSnackbarEffect(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
    )

    val sendBlockMessage = uiState.sendBlockReason?.asString()

    ChatRoot(
        inlineArtifactPrefs = prefs.inlineArtifactPrefs,
        mermaidRenderCache = viewModel.mermaidRenderCache,
        parsedMarkdownCache = viewModel.parsedMarkdownCache,
        subagentProgress = uiState.subagentProgress,
    ) {
    Scaffold(
        modifier = modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val title = uiState.conversationTitle
                        if (!isLandingPage && title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            // Model selector in the header (incl. the landing page) so the
                            // model/params (thinkingLevel) sheet is reachable without digging
                            // into the composer "+" menu. Mirrors the Android ChatTopBar.
                            ModelSelectorButton(
                                modelName = displayModel,
                                onClick = viewModel::openModelSheet,
                            )
                        }
                    },
                    navigationIcon = {
                        if (onOpenDrawer != null) {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = stringResource(Res.string.cd_open_drawer),
                                )
                            }
                        }
                    },
                    actions = {
                        // Temp chat toggle — interactive on the landing page; stays
                        // visible (ON) as a persistent indicator once a temporary chat
                        // is active. Gated on TEMPORARY_CHAT.USE.
                        if ((isLandingPage || uiState.isTemporaryChat) && uiState.temporaryChatEnabled) {
                            TempChatToggle(
                                isTemporary = uiState.isTemporaryChat,
                                onToggle = viewModel::toggleTemporaryChat,
                            )
                        }
                        // Options menu
                        Box {
                            IconButton(onClick = { showOptionsMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(Res.string.cd_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = showOptionsMenu,
                                onDismissRequest = { showOptionsMenu = false },
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                // Search — only in active conversation
                                if (uiState.conversationId != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.menu_search)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.openSearch()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Search, contentDescription = null)
                                        },
                                    )
                                }
                                // Presets
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.load_preset)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showPresetPicker = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.save_as_preset)) },
                                    onClick = {
                                        showOptionsMenu = false
                                        showSavePresetDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.SaveAs, contentDescription = null)
                                    },
                                )
                                // Prompts Library — gated on PROMPTS.USE
                                if (onNavigateToPromptsLibrary != null && uiState.promptsEnabled) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.prompts_library)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            onNavigateToPromptsLibrary()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                        },
                                    )
                                }
                                // Compare Models — gated on MULTI_CONVO.USE
                                if (uiState.multiConvoEnabled) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = stringResource(Res.string.compare_models),
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (uiState.comparisonState.isEnabled) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = stringResource(Res.string.cd_comparison_enabled),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.toggleComparison()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Compare, contentDescription = null)
                                        },
                                    )
                                }
                                // Conversation-specific actions
                                if (uiState.conversationId != null) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    Text(
                                        text = uiState.conversationTitle ?: stringResource(Res.string.new_chat),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                    if (uiState.sharedLinksEnabled) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(Res.string.action_share)) },
                                            onClick = {
                                                showOptionsMenu = false
                                                viewModel.shareConversation()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Share, contentDescription = null)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.menu_rename)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            showRenameDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Edit, contentDescription = null)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.action_duplicate)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.duplicateConversation()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.action_archive)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.archiveConversation()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Archive, contentDescription = null)
                                        },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(Res.string.delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showOptionsMenu = false
                                            viewModel.deleteConversation()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )

                // In-conversation search bar
                AnimatedVisibility(
                    visible = uiState.isSearchOpen,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    InConvoSearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChanged,
                        currentMatchIndex = uiState.currentSearchMatchIndex,
                        totalMatches = uiState.searchMatchIndices.size,
                        onPreviousMatch = viewModel::previousSearchMatch,
                        onNextMatch = viewModel::nextSearchMatch,
                        onClose = viewModel::closeSearch,
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            when {
                isLandingPage && !hasMessages -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LandingContent(
                            selectedModel = uiState.selectedModel,
                            selectedAgentName = agentName,
                        )
                    }
                }
                isLoading && uiState.displayMessages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }
                else -> {
                    MessageList(
                        displayMessages = uiState.displayMessages,
                        isStreaming = uiState.isStreaming,
                        streamingContent = uiState.streamingContent,
                        activeToolCalls = uiState.activeToolCalls,
                        streamingAttachments = uiState.streamingAttachments,
                        onSiblingNavigation = viewModel::switchBranch,
                        onEditMessage = viewModel::startEditing,
                        onRegenerateMessage = { messageId -> viewModel.regenerateMessage(messageId) },
                        onCopyMessage = { messageId -> viewModel.getMessageText(messageId) },
                        onFeedback = viewModel::submitFeedback,
                        onContinue = { viewModel.continueGeneration() },
                        onReadAloud = viewModel::readAloud,
                        onFork = viewModel::showForkOptions,
                        currentlyReadingMessageId = uiState.currentlyReadingMessageId,
                        editingMessageId = uiState.editingMessageId,
                        editingText = uiState.editingText,
                        onEditTextChange = viewModel::onEditTextChanged,
                        onEditSaveAndSubmit = viewModel::submitEdit,
                        onEditSaveOnly = viewModel::saveEditOnly,
                        onEditCancel = viewModel::cancelEditing,
                        baseUrl = uiState.serverUrl,
                        fontSizeMultiplier = fontSizeMultiplier,
                        isRefreshing = uiState.isRefreshingMessages,
                        onRefresh = viewModel::refreshMessages,
                        userAvatarUrl = uiState.userAvatarUrl,
                        userName = uiState.userName,
                        selectedEndpoint = uiState.selectedEndpoint,
                        streamingSenderName = run {
                            val model = uiState.selectedModel
                            if (uiState.selectedEndpoint == EndpointConstants.AGENTS && model != null) {
                                uiState.agents.find { it.id == model }?.name ?: model
                            } else {
                                model ?: stringResource(Res.string.sender_assistant)
                            }
                        },
                        showImageDescriptions = prefs.showImageDescriptions,
                        chatLayoutStyle = prefs.chatLayoutStyle,
                        showAvatars = prefs.showAvatars,
                        showBubbles = prefs.showBubbles,
                        useKatex = useKatex,
                        searchQuery = if (uiState.isSearchOpen) uiState.searchQuery else null,
                        searchMatchIndices = uiState.searchMatchIndices,
                        currentSearchMatchIndex = uiState.currentSearchMatchIndex,
                        searchScrollToIndex = uiState.searchScrollToIndex,
                        onSearchScrollHandle = viewModel::onSearchScrollHandled,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ChatInput overlays at the bottom so gradient shows content behind
            IosChatInput(
                inputText = uiState.inputText,
                isStreaming = uiState.isStreaming,
                onInputChanged = viewModel::onInputChanged,
                onSend = {
                    viewModel.sendMessage()
                },
                onStop = viewModel::stopGeneration,
                enabledTools = uiState.effectiveEnabledTools,
                onToggleTool = viewModel::toggleTool,
                mcpServers = uiState.mcpServers,
                selectedMcpServerNames = uiState.selectedMcpServerNames,
                onToggleMcpServer = viewModel::toggleMcpServer,
                isRecording = uiState.isRecording,
                isTranscribing = uiState.isTranscribing,
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onOpenModelParameters = viewModel::showModelParameters,
                onOpenModelSelector = viewModel::openModelSheet,
                selectedModelDisplay = displayModel,
                isCodeInterpreterAvailable = uiState.isCodeInterpreterAvailable,
                attachedFiles = attachedFiles,
                onRemoveFile = viewModel::removeFile,
                hasClipboardImage = hasClipboardImage,
                onPasteImage = {
                    coroutineScope.launch {
                        val imageData = readClipboardImage()
                        if (imageData != null) {
                            viewModel.onFilesSelected(listOf(imageData))
                        }
                        // Re-check clipboard after paste
                        hasClipboardImage = clipboardHasImage()
                    }
                },
                onAttachFiles = {
                    openDocumentPicker { files ->
                        if (files.isNotEmpty()) {
                            viewModel.onFilesSelected(files)
                        }
                    }
                },
                onTakePhoto = {
                    openCamera { files ->
                        if (files.isNotEmpty()) {
                            viewModel.onFilesSelected(files)
                        }
                    }
                },
                onPickPhotos = {
                    openPhotoPicker { files ->
                        if (files.isNotEmpty()) {
                            viewModel.onFilesSelected(files)
                        }
                    }
                },
                webSearchEnabled = uiState.webSearchEnabled,
                runCodeEnabled = uiState.runCodeEnabled,
                fileSearchEnabled = uiState.fileSearchEnabled,
                mcpServersEnabled = uiState.mcpServersEnabled,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // Model selector bottom sheet
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
            endpointKeyStates = uiState.endpointKeyStates,
            onSetApiKey = { name -> onNavigateToProviderKeys(name) },
        )
    }

    // Preset picker dialog
    if (showPresetPicker) {
        PresetPicker(
            presets = uiState.presets,
            onPresetSelect = { preset ->
                viewModel.loadPreset(preset)
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false },
            onEditPreset = { preset ->
                viewModel.loadPreset(preset)
                showPresetPicker = false
            },
            onDeletePreset = { preset ->
                preset.presetId?.let { viewModel.deletePreset(it) }
            },
        )
    }

    // Save preset dialog
    if (showSavePresetDialog) {
        SavePresetDialog(
            currentEndpoint = uiState.selectedEndpoint ?: "",
            currentModel = uiState.selectedModel,
            onSave = { name ->
                viewModel.savePreset(name)
                showSavePresetDialog = false
            },
            onDismiss = { showSavePresetDialog = false },
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        var title by remember { mutableStateOf(uiState.conversationTitle ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(Res.string.dialog_title_rename)) },
            text = {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
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
                    onClick = {
                        viewModel.renameConversation(title)
                        showRenameDialog = false
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text(stringResource(Res.string.action_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
    }
}

@Composable
actual fun NewChatScreen(
    onConversationStart: (String) -> Unit,
    modifier: Modifier,
    initialAgentId: String?,
    onOpenDrawer: (() -> Unit)?,
    onNavigateToPromptsLibrary: (() -> Unit)?,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    ChatScreen(
        modifier = modifier,
        initialAgentId = initialAgentId,
        onConversationStart = onConversationStart,
        onOpenDrawer = onOpenDrawer,
        onNavigateToPromptsLibrary = onNavigateToPromptsLibrary,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
    )
}
