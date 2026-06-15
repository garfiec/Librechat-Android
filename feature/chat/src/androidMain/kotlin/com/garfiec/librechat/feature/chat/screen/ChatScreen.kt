package com.garfiec.librechat.feature.chat.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.feature.chat.components.ChatInput
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.InConvoSearchBar
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import com.garfiec.librechat.feature.chat.viewmodel.asString
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
    onShowAllMedia: (() -> Unit)?,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    val viewModel: ChatViewModel = koinViewModel { parametersOf(conversationId, initialAgentId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val attachedFiles by viewModel.attachedFiles.collectAsStateWithLifecycle()
    val shareLinkUrl by viewModel.shareLinkUrl.collectAsStateWithLifecycle()
    val prefs by viewModel.chatPreferences.collectAsStateWithLifecycle()
    val showImageDescriptions = prefs.showImageDescriptions
    val dismissKeyboardOnSend = prefs.dismissKeyboardOnSend
    val chatLayoutStyle = prefs.chatLayoutStyle
    val showAvatars = prefs.showAvatars
    val showBubbles = prefs.showBubbles
    val useKatex = prefs.latexRenderer == LatexRenderer.KATEX
    val sttEngine = prefs.sttEngine
    val sttLanguage = prefs.sttLanguage
    val keyboardController = LocalSoftwareKeyboardController.current
    val fontSizeMultiplier = when (uiState.chatFontSize) {
        ChatFontSize.SMALL -> 0.85f
        ChatFontSize.MEDIUM -> 1.0f
        ChatFontSize.LARGE -> 1.2f
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var showPresetPicker by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showSecondaryModelSheet by remember { mutableStateOf(false) }
    var activeComparisonTab by remember { mutableStateOf(0) }

    // Header/composer model labels (agent name vs model name, with the "never a raw
    // model string under agents" rule). Shared with iOS via rememberChatModelLabel.
    val (agentName, displayModel) = rememberChatModelLabel(
        selectedEndpoint = uiState.selectedEndpoint,
        selectedModel = uiState.selectedModel,
        agents = uiState.agents,
    )

    val onStartRecordingWithPermission = rememberChatStartRecording(
        viewModel = viewModel,
        sttEngine = sttEngine,
        sttLanguage = sttLanguage,
        serverSttEnabled = uiState.serverSttEnabled,
        snackbarHostState = snackbarHostState,
        coroutineScope = coroutineScope,
    )

    ChatScreenEffects(
        uiState = uiState,
        shareLinkUrl = shareLinkUrl,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        clipboardManager = clipboardManager,
        onConversationStart = onConversationStart,
        onNavigateToConversation = onNavigateToConversation,
        onNavigateBack = onNavigateBack,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
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
    ) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            Column {
                ChatTopBar(
                    onLoadPreset = { showPresetPicker = true },
                    onSavePreset = { showSavePresetDialog = true },
                    onOpenDrawer = onOpenDrawer,
                    onOpenSearch = viewModel::openSearch,
                    onOpenPromptsLibrary = onNavigateToPromptsLibrary,
                    onShowAllMedia = onShowAllMedia,
                    promptsEnabled = uiState.promptsEnabled,
                    presetsEnabled = uiState.presetsEnabled,
                    multiConvoEnabled = uiState.multiConvoEnabled,
                    isTemporaryChat = uiState.isTemporaryChat,
                    onToggleTemporaryChat = viewModel::toggleTemporaryChat,
                    // Interactive on the new-chat landing; once a temporary chat is
                    // active it stays visible (ON) as a persistent indicator.
                    showTempChatToggle = (uiState.conversationId == null || uiState.isTemporaryChat) &&
                        uiState.temporaryChatEnabled,
                    isComparisonEnabled = uiState.comparisonState.isEnabled,
                    onToggleComparison = viewModel::toggleComparison,
                    conversationId = uiState.conversationId,
                    conversationTitle = uiState.conversationTitle,
                    sharedLinksEnabled = uiState.sharedLinksEnabled,
                    onShare = viewModel::shareConversation,
                    onRename = viewModel::showRenameDialog,
                    onDuplicate = viewModel::duplicateConversation,
                    onArchive = viewModel::archiveConversation,
                    onDelete = viewModel::showDeleteConfirmation,
                )
                // In-conversation search bar overlay
                if (uiState.isSearchOpen) {
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ChatContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    agentName = agentName,
                    displayModel = displayModel,
                    fontSizeMultiplier = fontSizeMultiplier,
                    showImageDescriptions = showImageDescriptions,
                    chatLayoutStyle = chatLayoutStyle,
                    showAvatars = showAvatars,
                    showBubbles = showBubbles,
                    useKatex = useKatex,
                    onShowSecondaryModelSheet = { showSecondaryModelSheet = true },
                    onComparisonTabChange = { activeComparisonTab = it },
                )
            }

            // ChatInput overlays at the bottom so gradient shows content behind
            val isAnyStreaming = uiState.isStreaming ||
                uiState.comparisonState.primaryIsStreaming ||
                uiState.comparisonState.secondaryIsStreaming
            ChatInput(
                inputText = uiState.inputText,
                isStreaming = isAnyStreaming,
                onInputChanged = viewModel::onInputChanged,
                onSend = {
                    viewModel.sendMessage()
                    if (dismissKeyboardOnSend) {
                        keyboardController?.hide()
                    }
                },
                onStop = viewModel::stopGeneration,
                attachedFiles = attachedFiles,
                onFilesSelected = viewModel::onFilesSelected,
                onRemoveFile = viewModel::removeFile,
                promptSuggestions = uiState.availablePrompts,
                onPromptSelected = viewModel::handlePromptMention,
                onSlashCommandSelected = viewModel::handleSlashCommand,
                isRecording = uiState.isRecording,
                isTranscribing = uiState.isTranscribing,
                onStartRecording = onStartRecordingWithPermission,
                onStopRecording = viewModel::stopRecording,
                enabledTools = uiState.effectiveEnabledTools,
                onToggleTool = viewModel::toggleTool,
                mcpServers = uiState.mcpServers,
                selectedMcpServerNames = uiState.selectedMcpServerNames,
                onToggleMcpServer = viewModel::toggleMcpServer,
                onOpenModelParameters = viewModel::showModelParameters,
                onOpenModelSelector = {
                    if (uiState.comparisonState.isEnabled && activeComparisonTab == 1) {
                        showSecondaryModelSheet = true
                    } else {
                        viewModel.openModelSheet()
                    }
                },
                selectedModelDisplay = if (uiState.comparisonState.isEnabled && activeComparisonTab == 1) {
                    viewModel.getSecondaryModelDisplayName()
                        ?: uiState.comparisonState.secondaryModel
                        ?: displayModel
                } else {
                    displayModel
                },
                isCodeInterpreterAvailable = uiState.isCodeInterpreterAvailable,
                webSearchEnabled = uiState.webSearchEnabled,
                runCodeEnabled = uiState.runCodeEnabled,
                fileSearchEnabled = uiState.fileSearchEnabled,
                mcpServersEnabled = uiState.mcpServersEnabled,
                gates = uiState.chatInputGates,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    ChatScreenDialogs(
        uiState = uiState,
        viewModel = viewModel,
        sendBlockMessage = sendBlockMessage,
        showPresetPicker = showPresetPicker,
        showSavePresetDialog = showSavePresetDialog,
        showSecondaryModelSheet = showSecondaryModelSheet,
        onSetShowPresetPicker = { showPresetPicker = it },
        onSetShowSavePresetDialog = { showSavePresetDialog = it },
        onSetShowSecondaryModelSheet = { showSecondaryModelSheet = it },
        onNavigateToProviderKeys = onNavigateToProviderKeys,
    )
    }
}
