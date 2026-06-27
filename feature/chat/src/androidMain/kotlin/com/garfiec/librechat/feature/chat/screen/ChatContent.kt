package com.garfiec.librechat.feature.chat.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.chat.components.ComparisonDualPane
import com.garfiec.librechat.feature.chat.components.ComparisonTabBar
import com.garfiec.librechat.feature.chat.components.LandingContent
import com.garfiec.librechat.feature.chat.components.MessageList
import com.garfiec.librechat.feature.chat.components.ModelSelectorButton
import com.garfiec.librechat.feature.chat.components.SecondaryMessageList
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.select_model
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the chat screen's main content area for each [ChatScreenState]: the
 * landing greeting, the loading spinner, or the active message list. In active
 * comparison mode it lays out the dual primary/secondary panes (side-by-side on
 * wide screens, a tab pager on phones); otherwise it shows the single message
 * list. Lives in a [ColumnScope] so the panes can claim the remaining height via
 * `weight`, leaving the bottom of the box for the composer overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.ChatContent(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    clipboardManager: ClipboardManager,
    agentName: String?,
    displayModel: String?,
    fontSizeMultiplier: Float,
    showImageDescriptions: Boolean,
    chatLayoutStyle: String,
    showAvatars: Boolean,
    showBubbles: Boolean,
    useKatex: Boolean,
    bottomContentPadding: Dp,
    topContentPadding: Dp,
    onShowSecondaryModelSheet: () -> Unit,
    onComparisonTabChange: (Int) -> Unit,
) {
    // Non-scrolling bodies (landing, loading, comparison panes) clear the floating bar with a
    // plain top padding; only the single active MessageList takes the inset as scrollable
    // contentPadding so its content scrolls up behind the bar's scrim.
    val topInsetModifier = Modifier
        .weight(1f)
        .padding(top = topContentPadding)
    when (uiState.screenState) {
        ChatScreenState.LANDING -> {
            LandingContent(
                selectedModel = uiState.selectedModel,
                selectedAgentName = agentName,
                modifier = topInsetModifier,
            )
        }
        ChatScreenState.LOADING -> {
            LoadingIndicator(
                modifier = topInsetModifier,
            )
        }
        ChatScreenState.ACTIVE -> {
            val comparisonState = uiState.comparisonState
            // Streaming-bubble sender label for the active model: the agent's
            // display name under the agents endpoint, else the raw model id.
            val senderName = run {
                val model = uiState.selectedModel
                if (uiState.selectedEndpoint == EndpointConstants.AGENTS && model != null) {
                    uiState.agents.find { it.id == model }?.name ?: model
                } else {
                    model ?: "Assistant"
                }
            }
            if (comparisonState.isEnabled) {
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val isWideScreen = screenWidthDp >= 600

                val canBranch = comparisonState.parallelMessageId != null &&
                    !comparisonState.primaryIsStreaming &&
                    !comparisonState.secondaryIsStreaming

                val primaryDisplayMessages = remember(
                    uiState.displayMessages,
                    comparisonState.parallelMessageId,
                    comparisonState.primaryFinalContent,
                ) {
                    buildComparisonDisplayMessages(
                        uiState.displayMessages,
                        comparisonState.parallelMessageId,
                        comparisonState.primaryFinalContent,
                        senderName,
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
                    ChatMessageListPane(
                        uiState = uiState,
                        viewModel = viewModel,
                        clipboardManager = clipboardManager,
                        fontSizeMultiplier = fontSizeMultiplier,
                        showImageDescriptions = showImageDescriptions,
                        chatLayoutStyle = chatLayoutStyle,
                        showAvatars = showAvatars,
                        showBubbles = showBubbles,
                        useKatex = useKatex,
                        // The comparison container is already padded clear of the floating bar.
                        topContentPadding = 0.dp,
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
                        streamingSenderName = senderName,
                        bottomContentPadding = bottomContentPadding,
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
                        bottomContentPadding = bottomContentPadding,
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
                                onClick = onShowSecondaryModelSheet,
                            )
                        },
                        primaryContent = primaryMessageList,
                        secondaryContent = secondaryMessageList,
                        onContinueWithPrimary = onContinuePrimary,
                        onContinueWithSecondary = onContinueSecondary,
                        modifier = topInsetModifier,
                    )
                } else {
                    ComparisonTabBar(
                        primaryModelName = displayModel ?: "Primary",
                        secondaryModelName = secondaryModelName,
                        primaryContent = primaryMessageList,
                        secondaryContent = secondaryMessageList,
                        onContinueWithPrimary = onContinuePrimary,
                        onContinueWithSecondary = onContinueSecondary,
                        onTabChange = onComparisonTabChange,
                        modifier = topInsetModifier,
                    )
                }
            } else {
                ChatMessageListPane(
                    uiState = uiState,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    fontSizeMultiplier = fontSizeMultiplier,
                    showImageDescriptions = showImageDescriptions,
                    chatLayoutStyle = chatLayoutStyle,
                    showAvatars = showAvatars,
                    showBubbles = showBubbles,
                    useKatex = useKatex,
                    topContentPadding = topContentPadding,
                    displayMessages = uiState.displayMessages,
                    isStreaming = uiState.isStreaming,
                    streamingContent = uiState.streamingContent,
                    activeToolCalls = uiState.activeToolCalls,
                    streamingSenderName = senderName,
                    bottomContentPadding = bottomContentPadding,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The single (non-comparison) and comparison-primary message lists differ only in
 * their streaming source, sender label, and modifier; everything else — the action
 * callbacks, editing state, search wiring, and display prefs — is identical. This
 * wrapper holds that shared configuration so both call sites stay in sync.
 */
@Composable
private fun ChatMessageListPane(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    clipboardManager: ClipboardManager,
    fontSizeMultiplier: Float,
    showImageDescriptions: Boolean,
    chatLayoutStyle: String,
    showAvatars: Boolean,
    showBubbles: Boolean,
    useKatex: Boolean,
    topContentPadding: Dp,
    displayMessages: List<MessageNode>,
    isStreaming: Boolean,
    streamingContent: String,
    activeToolCalls: List<ActiveToolCall>,
    streamingSenderName: String,
    bottomContentPadding: Dp,
    modifier: Modifier,
) {
    MessageList(
        displayMessages = displayMessages,
        isStreaming = isStreaming,
        streamingContent = streamingContent,
        activeToolCalls = activeToolCalls,
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
        streamingSenderName = streamingSenderName,
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
        bottomContentPadding = bottomContentPadding,
        topContentPadding = topContentPadding,
        modifier = modifier,
    )
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
