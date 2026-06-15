package com.garfiec.librechat.feature.chat.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.feature.chat.components.ChatInput
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.InConvoSearchBar
import com.garfiec.librechat.feature.chat.components.TempChatToggle
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import com.garfiec.librechat.feature.chat.viewmodel.asString
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    onLoadPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenSearch: () -> Unit = {},
    onOpenPromptsLibrary: (() -> Unit)? = null,
    onShowAllMedia: (() -> Unit)? = null,
    promptsEnabled: Boolean = true,
    presetsEnabled: Boolean = true,
    multiConvoEnabled: Boolean = true,
    isTemporaryChat: Boolean = false,
    onToggleTemporaryChat: () -> Unit = {},
    showTempChatToggle: Boolean = false,
    isComparisonEnabled: Boolean = false,
    onToggleComparison: () -> Unit = {},
    conversationId: String? = null,
    conversationTitle: String? = null,
    sharedLinksEnabled: Boolean = false,
    onShare: () -> Unit = {},
    onRename: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hamburger menu button to open drawer
        if (onOpenDrawer != null) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open navigation drawer",
                )
            }
        }

        // Show the conversation title when viewing an existing conversation. The header
        // intentionally has no model selector — model/params stay reachable from the
        // composer "+" menu (tools sheet), a deliberate mobile decluttering choice that
        // diverges from web's header model selector.
        if (conversationId != null && !conversationTitle.isNullOrBlank()) {
            Text(
                text = conversationTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (showTempChatToggle) {
            TempChatToggle(
                isTemporary = isTemporaryChat,
                onToggle = onToggleTemporaryChat,
            )
        }
        Box {
            IconButton(onClick = { showOverflowMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.cd_more_options),
                )
            }
            DropdownMenu(
                expanded = showOverflowMenu,
                onDismissRequest = { showOverflowMenu = false },
                shape = RoundedCornerShape(16.dp),
            ) {
                if (conversationId != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_search)) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenSearch()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                    )
                }
                if (onShowAllMedia != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_show_all_media)) },
                        onClick = {
                            showOverflowMenu = false
                            onShowAllMedia()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        },
                    )
                }
                // Preset load/save — hidden when the server disables `interface.presets`
                // (or `interface.modelSelect`), matching web's Header.tsx presets menu.
                if (presetsEnabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.load_preset)) },
                        onClick = {
                            showOverflowMenu = false
                            onLoadPreset()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.save_as_preset)) },
                        onClick = {
                            showOverflowMenu = false
                            onSavePreset()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.SaveAs, contentDescription = null)
                        },
                    )
                }
                if (onOpenPromptsLibrary != null && promptsEnabled) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.prompts_library)) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenPromptsLibrary()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        },
                    )
                }
                if (multiConvoEnabled) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(Res.string.compare_models),
                                    modifier = Modifier.weight(1f),
                                )
                                if (isComparisonEnabled) {
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
                            showOverflowMenu = false
                            onToggleComparison()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Compare, contentDescription = null)
                        },
                    )
                }
                if (conversationId != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = conversationTitle ?: "New Chat",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (sharedLinksEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.action_share)) },
                            onClick = {
                                showOverflowMenu = false
                                onShare()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_rename)) },
                        onClick = {
                            showOverflowMenu = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_duplicate)) },
                        onClick = {
                            showOverflowMenu = false
                            onDuplicate()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_archive)) },
                        onClick = {
                            showOverflowMenu = false
                            onArchive()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Archive, contentDescription = null)
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onDelete()
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
    }
}
