package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.feature.chat.components.ChatFloatingTopBar
import com.garfiec.librechat.feature.chat.components.IosChatInput
import com.garfiec.librechat.feature.chat.components.LandingContent
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.MessageList
import com.garfiec.librechat.feature.chat.components.ModelSelectorSheet
import com.garfiec.librechat.feature.chat.components.PresetPicker
import com.garfiec.librechat.feature.chat.components.SavePresetDialog
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.clipboardHasImage
import com.garfiec.librechat.feature.chat.util.collapseParallelToPrimary
import com.garfiec.librechat.feature.chat.util.openCamera
import com.garfiec.librechat.feature.chat.util.openDocumentPicker
import com.garfiec.librechat.feature.chat.util.openPhotoPicker
import com.garfiec.librechat.feature.chat.util.readClipboardImage
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
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
    isTemporaryRoute: Boolean,
    initialAgentId: String?,
    initialEndpoint: String?,
    initialModel: String?,
    onConversationStart: ((conversationId: String, isTemporary: Boolean) -> Unit)?,
    onNavigateToConversation: ((String) -> Unit)?,
    onOpenDrawer: (() -> Unit)?,
    onNavigateToPromptsLibrary: (() -> Unit)?,
    onNavigateBack: (() -> Unit)?,
    onShowAllMedia: (() -> Unit)?,
    onAttachFromServer: () -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    val viewModel: ChatViewModel =
        koinViewModel {
            parametersOf(conversationId, initialAgentId, isTemporaryRoute, initialEndpoint, initialModel)
        }
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
                // Carry temp-ness onto the Chat(id) route so the new VM stays temp-aware and
                // never persists the server-hidden conversation to Room.
                onConversationStart(navId, uiState.isTemporaryChat)
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
    var showSecondaryModelSheet by remember { mutableStateOf(false) }
    var activeComparisonTab by remember { mutableStateOf(0) }

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
                duration = SnackbarDuration.Long,
            )
            viewModel.dismissError()
        }
    }

    UserKeyErrorSnackbarEffect(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
    )

    QueuedMessageDroppedSnackbarEffect(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
    )

    val sendBlockMessage = uiState.sendBlockReason?.asString()

    ChatRoot(
        inlineArtifactPrefs = prefs.inlineArtifactPrefs,
        mermaidRenderCache = viewModel.mermaidRenderCache,
        parsedMarkdownCache = viewModel.parsedMarkdownCache,
        subagentProgress = uiState.subagentProgress,
        mediaPreview = uiState.mediaPreview,
        onOpenMedia = viewModel::openMedia,
        onCloseMedia = viewModel::closeMedia,
        onDownloadAttachment = viewModel::downloadFileBytes,
    ) {
    Scaffold(
        modifier = modifier.imePadding(),
        // The floating top bar draws behind the status bar itself, so the body extends under the
        // status bar — only reserve the navigation-bar inset here (for the snackbar).
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        // The composer overlays the message list at the bottom; the list reserves a scrollable
        // bottom inset so its latest content rests above the bar. We measure the bar's actual
        // height (which grows as queued ghost rows stack above it) so streaming text never hides
        // behind the ghosts, while the list still scrolls *behind* the translucent overlay.
        var inputBarHeightPx by remember { mutableIntStateOf(0) }
        var topBarHeightPx by remember { mutableIntStateOf(0) }
        val bottomContentPadding = with(LocalDensity.current) {
            maxOf(160.dp, inputBarHeightPx.toDp() + 16.dp)
        }
        // Floor the top inset at status bar + one chip row so content clears the bar on the first
        // frame (before onSizeChanged reports the real height), avoiding a content-under-bar flash
        // on each screen entry. Mirrors the bottomContentPadding floor.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topContentPadding = with(LocalDensity.current) {
            maxOf(statusBarTop + 56.dp, topBarHeightPx.toDp())
        }
        // Non-scrolling bodies (landing, loading) clear the floating bar with a plain top padding;
        // only the active MessageList takes the inset as scrollable contentPadding.
        val topPaddedFill = Modifier
            .fillMaxSize()
            .padding(top = topContentPadding)
        // Streaming-bubble sender label for the active (primary) model: the agent's
        // display name under the agents endpoint, else the raw model id.
        val senderName = run {
            val model = uiState.selectedModel
            if (uiState.selectedEndpoint == EndpointConstants.AGENTS && model != null) {
                uiState.agents.find { it.id == model }?.name ?: model
            } else {
                model ?: stringResource(Res.string.sender_assistant)
            }
        }
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                isLandingPage && !hasMessages -> {
                    Box(
                        modifier = topPaddedFill,
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
                        modifier = topPaddedFill,
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }
                uiState.comparisonState.isEnabled -> {
                    ComparisonPanes(
                        uiState = uiState,
                        viewModel = viewModel,
                        displayModel = displayModel,
                        senderName = senderName,
                        fontSizeMultiplier = fontSizeMultiplier,
                        showImageDescriptions = prefs.showImageDescriptions,
                        chatLayoutStyle = prefs.chatLayoutStyle,
                        showAvatars = prefs.showAvatars,
                        showBubbles = prefs.showBubbles,
                        useKatex = useKatex,
                        bottomContentPadding = bottomContentPadding,
                        onCopyMessage = { messageId -> viewModel.getMessageText(messageId) },
                        onShowSecondaryModelSheet = { showSecondaryModelSheet = true },
                        onComparisonTabChange = { activeComparisonTab = it },
                        modifier = topPaddedFill,
                    )
                }
                else -> {
                    val singleDisplayMessages = remember(uiState.displayMessages) {
                        collapseParallelToPrimary(uiState.displayMessages)
                    }
                    MessageList(
                        displayMessages = singleDisplayMessages,
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
                        streamingSenderName = senderName,
                        showImageDescriptions = prefs.showImageDescriptions,
                        chatLayoutStyle = prefs.chatLayoutStyle,
                        showAvatars = prefs.showAvatars,
                        showBubbles = prefs.showBubbles,
                        useKatex = useKatex,
                        searchQuery = if (uiState.isSearchOpen) uiState.searchQuery else null,
                        searchMatchIndices = uiState.searchMatchIndices,
                        currentSearchMatchIndex = uiState.currentSearchMatchIndex,
                        searchFocusRequest = uiState.searchFocusRequest,
                        onSearchScrollHandle = viewModel::onSearchScrollHandled,
                        bottomContentPadding = bottomContentPadding,
                        topContentPadding = topContentPadding,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ChatInput overlays at the bottom so gradient shows content behind
            val isAnyStreaming = uiState.isStreaming ||
                uiState.comparisonState.primaryIsStreaming ||
                uiState.comparisonState.secondaryIsStreaming
            val isSecondaryTab = uiState.comparisonState.isEnabled && activeComparisonTab == 1
            IosChatInput(
                inputText = uiState.inputText,
                isStreaming = isAnyStreaming,
                onInputChanged = viewModel::onInputChanged,
                onSend = {
                    viewModel.sendMessage()
                },
                onStop = viewModel::stopGeneration,
                onQueue = { viewModel.queueMessage() },
                canQueue = uiState.canQueueFollowUp,
                enabledTools = uiState.effectiveEnabledTools,
                pinnedToolKeys = uiState.pinnedToolChips,
                onToggleTool = viewModel::toggleTool,
                mcpServers = uiState.mcpServers,
                selectedMcpServerNames = uiState.selectedMcpServerNames,
                onToggleMcpServer = viewModel::toggleMcpServer,
                isRecording = uiState.isRecording,
                isTranscribing = uiState.isTranscribing,
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onOpenModelParameters = viewModel::showModelParameters,
                onOpenModelSelector = {
                    if (isSecondaryTab) {
                        showSecondaryModelSheet = true
                    } else {
                        viewModel.openModelSheet()
                    }
                },
                selectedModelDisplay = if (isSecondaryTab) {
                    viewModel.getSecondaryModelDisplayName()
                        ?: uiState.comparisonState.secondaryModel
                        ?: displayModel
                } else {
                    displayModel
                },
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
                onAttachFromServer = onAttachFromServer,
                onPickPhotos = {
                    openPhotoPicker { files ->
                        if (files.isNotEmpty()) {
                            viewModel.onFilesSelected(files)
                        }
                    }
                },
                webSearchEnabled = uiState.webSearchEnabled,
                urlContextEnabled = uiState.urlContextProviderGate,
                runCodeEnabled = uiState.runCodeEnabled,
                fileSearchEnabled = uiState.fileSearchEnabled,
                mcpServersEnabled = uiState.mcpServersEnabled,
                gates = uiState.chatInputGates,
                contextUsage = uiState.contextUsage,
                tokenUsage = uiState.tokenUsage,
                contextUsageEnabled = uiState.contextUsageEnabled,
                contextBarPlacement = uiState.contextBarPlacement,
                contextGaugeExpanded = uiState.contextGaugeExpanded,
                onContextGaugeExpandedChange = viewModel::setContextGaugeExpanded,
                queuedPausedCount = uiState.pausedQueueCount,
                isEditingQueued = uiState.isEditingQueued,
                onCommitEdit = viewModel::commitQueuedEdit,
                onCancelEdit = viewModel::cancelQueuedEdit,
                isAwaitingUploadSend = uiState.isAwaitingUploadSend,
                onCancelPendingSend = viewModel::cancelPendingUploadSend,
                onSendQueuedMessages = viewModel::sendQueuedNow,
                queuedMessages = uiState.messageQueue,
                onEditQueuedMessage = viewModel::editQueued,
                onCancelQueuedMessage = viewModel::cancelQueued,
                onReorderQueuedMessages = viewModel::reorderQueue,
                fontSizeMultiplier = fontSizeMultiplier,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { inputBarHeightPx = it.height },
            )

            // Floating top bar overlays the message list (drawn last so it sits above content),
            // measured so the content reserves a matching scrollable top inset.
            ChatFloatingTopBar(
                uiState = uiState,
                viewModel = viewModel,
                onLoadPreset = { showPresetPicker = true },
                onSavePreset = { showSavePresetDialog = true },
                onRename = { showRenameDialog = true },
                onOpenDrawer = onOpenDrawer,
                onShowAllMedia = onShowAllMedia,
                onOpenPromptsLibrary = onNavigateToPromptsLibrary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topBarHeightPx = it.height },
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
                showSecondaryModelSheet = false
            },
            onDismiss = { showSecondaryModelSheet = false },
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

    // Delete confirmation — matches Android so a destructive delete can't fire from a single tap.
    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(stringResource(Res.string.dialog_title_delete_conversation)) },
            text = {
                Text(
                    text = stringResource(Res.string.dialog_delete_conversation_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::deleteConversation) {
                    Text(
                        text = stringResource(Res.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
    }
}

@Composable
actual fun NewChatScreen(
    onConversationStart: (conversationId: String, isTemporary: Boolean) -> Unit,
    modifier: Modifier,
    initialAgentId: String?,
    initialEndpoint: String?,
    initialModel: String?,
    onOpenDrawer: (() -> Unit)?,
    onNavigateToPromptsLibrary: (() -> Unit)?,
    onAttachFromServer: () -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    ChatScreen(
        modifier = modifier,
        initialAgentId = initialAgentId,
        initialEndpoint = initialEndpoint,
        initialModel = initialModel,
        onConversationStart = onConversationStart,
        onOpenDrawer = onOpenDrawer,
        onNavigateToPromptsLibrary = onNavigateToPromptsLibrary,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
        onAttachFromServer = onAttachFromServer,
    )
}
