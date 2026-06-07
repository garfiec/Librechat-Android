package com.garfiec.librechat.feature.chat.screen

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.core.ui.components.ModelParameterSheet
import com.garfiec.librechat.feature.chat.components.ChatInput
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.ComparisonDualPane
import com.garfiec.librechat.feature.chat.components.ComparisonTabBar
import com.garfiec.librechat.feature.chat.components.ForkOptionsBottomSheet
import com.garfiec.librechat.feature.chat.components.InConvoSearchBar
import com.garfiec.librechat.feature.chat.components.LandingContent
import com.garfiec.librechat.feature.chat.components.MessageList
import com.garfiec.librechat.feature.chat.components.ModelSelectorButton
import com.garfiec.librechat.feature.chat.components.ModelSelectorSheet
import com.garfiec.librechat.feature.chat.components.PresetPicker
import com.garfiec.librechat.feature.chat.components.SavePresetDialog
import com.garfiec.librechat.feature.chat.components.SecondaryMessageList
import com.garfiec.librechat.feature.chat.components.TempChatToggle
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.MessageNode
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

    // Device speech recognizer launcher (used when server STT is not available)
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val transcribed = matches?.firstOrNull()
            if (!transcribed.isNullOrBlank()) {
                viewModel.onDeviceSpeechResult(transcribed)
            }
        }
    }

    // Runtime permission request for RECORD_AUDIO (server STT path)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Microphone permission is required for voice input")
            }
        }
    }

    val onStartRecordingWithPermission: () -> Unit = {
        val useServerStt = sttEngine.equals("whisper", ignoreCase = true) ||
            (sttEngine.isBlank() || sttEngine.equals("default", ignoreCase = true)) &&
            uiState.serverSttEnabled

        if (useServerStt) {
            if (!uiState.serverSttEnabled && sttEngine.equals("whisper", ignoreCase = true)) {
                // User selected Whisper but server STT is not configured
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "Server speech-to-text is not enabled. " +
                            "Ask your server admin to enable STT, or switch to Device or Google engine.",
                    )
                }
            } else {
                // Server STT path: record audio and upload for transcription
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.startRecording()
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        } else {
            // Device speech recognizer path (Device, Google, or Default without server)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                // Apply user's STT language preference
                val languageLocale = mapSttLanguageToLocale(sttLanguage)
                if (languageLocale != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageLocale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageLocale)
                }
            }
            // Apply user's STT engine preference (set the recognizer package)
            val enginePackage = mapSttEngineToPackage(sttEngine)
            if (enginePackage != null) {
                intent.setPackage(enginePackage)
            }
            try {
                speechRecognizerLauncher.launch(intent)
            } catch (_: Exception) {
                val engineLabel = if (sttEngine.equals("google", ignoreCase = true)) {
                    "Google speech recognition is not available. Is the Google app installed?"
                } else {
                    "Speech recognition is not available on this device"
                }
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(engineLabel)
                }
            }
        }
    }

    // When a new conversation starts, navigate to Chat(conversationId) immediately
    // (at StreamEvent.Created) so the NewChat landing page stays clean in the back
    // stack. The new ChatViewModel at Chat(id) will resume the active stream.
    // onPendingNavigationHandled() resets this ViewModel to a fresh landing state.
    LaunchedEffect(uiState.pendingNavigationConversationId) {
        val pendingId = uiState.pendingNavigationConversationId
        if (pendingId != null && onConversationStart != null) {
            onConversationStart(pendingId)
            viewModel.onPendingNavigationHandled()
        }
    }

    // Show errors in snackbar
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

    // Stream resume on foreground
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    // Navigate to forked conversation
    LaunchedEffect(uiState.forkedConversationId) {
        val forkId = uiState.forkedConversationId
        if (forkId != null) {
            viewModel.onForkedConversationHandled()
            if (onNavigateToConversation != null) {
                onNavigateToConversation(forkId)
            } else if (onConversationStart != null) {
                onConversationStart(forkId)
            }
        }
    }

    // Navigate to duplicated conversation
    LaunchedEffect(uiState.duplicatedConversationId) {
        val dupId = uiState.duplicatedConversationId
        if (dupId != null) {
            viewModel.onDuplicatedConversationHandled()
            if (onNavigateToConversation != null) {
                onNavigateToConversation(dupId)
            } else if (onConversationStart != null) {
                onConversationStart(dupId)
            }
        }
    }

    // Copy share link to clipboard
    LaunchedEffect(shareLinkUrl) {
        val url = shareLinkUrl
        if (url != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Share Link", url))
            viewModel.onShareLinkHandled()
            snackbarHostState.showSnackbar("Share link copied to clipboard")
        }
    }

    // Navigate back after delete/archive (conversationId becomes null)
    var hadConversation by remember { mutableStateOf(uiState.conversationId != null) }
    LaunchedEffect(uiState.conversationId) {
        if (hadConversation && uiState.conversationId == null) {
            onNavigateBack?.invoke()
        }
        hadConversation = uiState.conversationId != null
    }

    ChatRoot(
        inlineArtifactPrefs = prefs.inlineArtifactPrefs,
        mermaidRenderCache = viewModel.mermaidRenderCache,
        parsedMarkdownCache = viewModel.parsedMarkdownCache,
        subagentProgress = uiState.subagentProgress,
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
                when (uiState.screenState) {
                    ChatScreenState.LANDING -> {
                        LandingContent(
                            selectedModel = uiState.selectedModel,
                            selectedAgentName = agentName,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ChatScreenState.LOADING -> {
                        LoadingIndicator(
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ChatScreenState.ACTIVE -> {
                        val comparisonState = uiState.comparisonState
                        if (comparisonState.isEnabled) {
                            val screenWidthDp = LocalConfiguration.current.screenWidthDp
                            val isWideScreen = screenWidthDp >= 600

                            val canBranch = comparisonState.parallelMessageId != null &&
                                !comparisonState.primaryIsStreaming &&
                                !comparisonState.secondaryIsStreaming

                            // Replace the parallel response message's content with
                            // the captured streaming buffer for each agent's pane.
                            // The server-loaded message may only contain the primary
                            // agent's content, so we substitute from the buffers.
                            val primarySenderName = run {
                                val model = uiState.selectedModel
                                if (uiState.selectedEndpoint == EndpointConstants.AGENTS && model != null) {
                                    uiState.agents.find { it.id == model }?.name ?: model
                                } else {
                                    model ?: "Assistant"
                                }
                            }
                            val primaryDisplayMessages = remember(
                                uiState.displayMessages,
                                comparisonState.parallelMessageId,
                                comparisonState.primaryFinalContent,
                            ) {
                                buildComparisonDisplayMessages(
                                    uiState.displayMessages,
                                    comparisonState.parallelMessageId,
                                    comparisonState.primaryFinalContent,
                                    primarySenderName,
                                )
                            }
                            val secondarySenderName = viewModel.getSecondaryModelDisplayName()
                                ?: comparisonState.secondaryModel ?: "Assistant"
                            val secondaryDisplayMessages = remember(
                                uiState.displayMessages,
                                comparisonState.parallelMessageId,
                                comparisonState.secondaryFinalContent,
                                secondarySenderName,
                            ) {
                                buildComparisonDisplayMessages(
                                    uiState.displayMessages,
                                    comparisonState.parallelMessageId,
                                    comparisonState.secondaryFinalContent,
                                    secondarySenderName,
                                )
                            }

                            val primaryMessageList: @Composable () -> Unit = {
                                MessageList(
                                    displayMessages = primaryDisplayMessages,
                                    isStreaming = comparisonState.primaryIsStreaming || uiState.isStreaming,
                                    streamingContent = if (comparisonState.primaryIsStreaming) {
                                        comparisonState.primaryStreamingContent
                                    } else {
                                        uiState.streamingContent
                                    },
                                    activeToolCalls = if (comparisonState.primaryIsStreaming) {
                                        comparisonState.primaryActiveToolCalls
                                    } else {
                                        uiState.activeToolCalls
                                    },
                                    streamingAttachments = uiState.streamingAttachments,
                                    onSiblingNavigation = viewModel::switchBranch,
                                    onEditMessage = viewModel::startEditing,
                                    onRegenerateMessage = viewModel::regenerateMessage,
                                    onCopyMessage = { messageId ->
                                        val text = viewModel.getMessageText(messageId)
                                        if (text.isNotBlank()) {
                                            clipboardManager.setPrimaryClip(
                                                ClipData.newPlainText("Message", text),
                                            )
                                        }
                                    },
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
                                    streamingSenderName = primarySenderName,
                                    showImageDescriptions = showImageDescriptions,
                                    chatLayoutStyle = chatLayoutStyle,
                                    showAvatars = showAvatars,
                                    showBubbles = showBubbles,
                                    useKatex = useKatex,
                                    searchQuery = if (uiState.isSearchOpen) uiState.searchQuery else null,
                                    searchMatchIndices = uiState.searchMatchIndices,
                                    currentSearchMatchIndex = uiState.currentSearchMatchIndex,
                                    searchScrollToIndex = uiState.searchScrollToIndex,
                                    onSearchScrollHandle = viewModel::onSearchScrollHandled,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            val secondaryEndpoint = comparisonState.secondaryEndpoint ?: "agents"
                            val secondaryModelName = viewModel.getSecondaryModelDisplayName()
                                ?: comparisonState.secondaryModel
                                ?: stringResource(Res.string.select_model)

                            val secondaryMessageList: @Composable () -> Unit = {
                                SecondaryMessageList(
                                    displayMessages = secondaryDisplayMessages,
                                    isStreaming = comparisonState.secondaryIsStreaming,
                                    streamingContent = comparisonState.secondaryStreamingContent,
                                    activeToolCalls = comparisonState.secondaryActiveToolCalls,
                                    streamingAttachments = uiState.streamingAttachments,
                                    error = null,
                                    baseUrl = uiState.serverUrl,
                                    fontSizeMultiplier = fontSizeMultiplier,
                                    selectedEndpoint = secondaryEndpoint,
                                    streamingSenderName = secondaryModelName,
                                    showImageDescriptions = showImageDescriptions,
                                    chatLayoutStyle = chatLayoutStyle,
                                    showAvatars = showAvatars,
                                    showBubbles = showBubbles,
                                    useKatex = useKatex,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            val onContinuePrimary = if (canBranch && comparisonState.primaryAgentId != null) {
                                { viewModel.branchFromComparison(comparisonState.primaryAgentId) }
                            } else {
                                null
                            }
                            val onContinueSecondary = if (canBranch && comparisonState.secondaryAgentId != null) {
                                { viewModel.branchFromComparison(comparisonState.secondaryAgentId) }
                            } else {
                                null
                            }

                            if (isWideScreen) {
                                // Tablet: dual pane side-by-side
                                ComparisonDualPane(
                                    primaryModelSelector = {
                                        ModelSelectorButton(
                                            modelName = displayModel,
                                            onClick = viewModel::openModelSheet,
                                        )
                                    },
                                    secondaryModelSelector = {
                                        ModelSelectorButton(
                                            modelName = secondaryModelName,
                                            onClick = { showSecondaryModelSheet = true },
                                        )
                                    },
                                    primaryContent = primaryMessageList,
                                    secondaryContent = secondaryMessageList,
                                    onContinueWithPrimary = onContinuePrimary,
                                    onContinueWithSecondary = onContinueSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                // Phone: tab bar with pager
                                ComparisonTabBar(
                                    primaryModelName = displayModel ?: "Primary",
                                    secondaryModelName = secondaryModelName,
                                    primaryContent = primaryMessageList,
                                    secondaryContent = secondaryMessageList,
                                    onContinueWithPrimary = onContinuePrimary,
                                    onContinueWithSecondary = onContinueSecondary,
                                    onTabChange = { activeComparisonTab = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            MessageList(
                                displayMessages = uiState.displayMessages,
                                isStreaming = uiState.isStreaming,
                                streamingContent = uiState.streamingContent,
                                activeToolCalls = uiState.activeToolCalls,
                                streamingAttachments = uiState.streamingAttachments,
                                onSiblingNavigation = viewModel::switchBranch,
                                onEditMessage = viewModel::startEditing,
                                onRegenerateMessage = viewModel::regenerateMessage,
                                onCopyMessage = { messageId ->
                                    val text = viewModel.getMessageText(messageId)
                                    if (text.isNotBlank()) {
                                        clipboardManager.setPrimaryClip(
                                            ClipData.newPlainText("Message", text),
                                        )
                                    }
                                },
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
                                        model ?: "Assistant"
                                    }
                                },
                                showImageDescriptions = showImageDescriptions,
                                chatLayoutStyle = chatLayoutStyle,
                                showAvatars = showAvatars,
                                showBubbles = showBubbles,
                                useKatex = useKatex,
                                searchQuery = if (uiState.isSearchOpen) uiState.searchQuery else null,
                                searchMatchIndices = uiState.searchMatchIndices,
                                currentSearchMatchIndex = uiState.currentSearchMatchIndex,
                                searchScrollToIndex = uiState.searchScrollToIndex,
                                onSearchScrollHandle = viewModel::onSearchScrollHandled,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
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

    if (showSavePresetDialog) {
        SavePresetDialog(
            currentEndpoint = uiState.selectedEndpoint,
            currentModel = uiState.selectedModel,
            onSave = { name ->
                viewModel.savePreset(name)
                showSavePresetDialog = false
            },
            onDismiss = { showSavePresetDialog = false },
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
                showSavePresetDialog = true
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
            endpointKeyStates = uiState.endpointKeyStates,
            onSetApiKey = { name -> onNavigateToProviderKeys(name) },
        )
    }
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

/**
 * Maps the user-facing STT engine name (from settings) to an Android package name
 * that can be set on the device speech recognition intent. Returns null for "Default",
 * empty/unknown values, or "Whisper" (which uses server-side STT, not a device package).
 *
 * Note: "Whisper" is handled separately in [onStartRecordingWithPermission] above --
 * it routes through the server STT path (record audio + upload) rather than the device
 * speech recognizer. This function is only called for the device recognizer path.
 */
private fun mapSttEngineToPackage(engine: String): String? = when (engine.lowercase()) {
    "google" -> "com.google.android.googlequicksearchbox"
    "whisper" -> null // Server-side engine; never reaches device recognizer path
    "device" -> null // Explicit on-device; uses system default speech recognizer
    "default", "" -> null
    else -> null
}

/**
 * Maps the user-facing STT language name (from settings) to a BCP-47 locale tag
 * for [RecognizerIntent.EXTRA_LANGUAGE]. Returns null for "Auto-detect" or
 * empty values, which lets the recognizer use the device default.
 */
private fun mapSttLanguageToLocale(language: String): String? = when (language.lowercase()) {
    "english" -> "en-US"
    "spanish" -> "es-ES"
    "french" -> "fr-FR"
    "german" -> "de-DE"
    "japanese" -> "ja-JP"
    "chinese" -> "zh-CN"
    "auto-detect", "" -> null
    else -> null
}

/**
 * Replaces the parallel response message's content with [finalContent] captured from
 * the streaming buffer. The server-loaded message may only contain the primary agent's
 * content, so for the secondary pane we substitute the captured text.
 * Also updates the sender name so the bubble shows the correct model.
 */
private fun buildComparisonDisplayMessages(
    displayMessages: List<MessageNode>,
    parallelMessageId: String?,
    finalContent: String?,
    senderName: String?,
): List<MessageNode> {
    if (parallelMessageId == null || finalContent.isNullOrBlank()) return displayMessages
    return displayMessages.map { node ->
        if (node.message.messageId == parallelMessageId) {
            // Substitute the parallel message content with the captured final content for this pane
            node.copy(
                message = node.message.copy(
                    content = listOf(
                        MessageContentPart(type = ContentType.TEXT, text = finalContent),
                    ),
                    sender = senderName ?: node.message.sender,
                ),
            )
        } else {
            node
        }
    }
}
