package com.garfiec.librechat.feature.chat.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.chat.components.LandingContent
import com.garfiec.librechat.feature.chat.components.MessageList
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.util.collapseParallelToPrimary
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel

/**
 * Renders the chat screen's main content area for each [ChatScreenState]: the
 * landing greeting, the loading spinner, or the active message list. In active
 * comparison mode it delegates to the shared [ComparisonPanes] (side-by-side on
 * wide screens, a tab pager on phones); otherwise it shows the single message
 * list. Lives in a [ColumnScope] so the panes can claim the remaining height via
 * `weight`, leaving the bottom of the box for the composer overlay.
 */
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
                ComparisonPanes(
                    uiState = uiState,
                    viewModel = viewModel,
                    displayModel = displayModel,
                    senderName = senderName,
                    fontSizeMultiplier = fontSizeMultiplier,
                    showImageDescriptions = showImageDescriptions,
                    chatLayoutStyle = chatLayoutStyle,
                    showAvatars = showAvatars,
                    showBubbles = showBubbles,
                    useKatex = useKatex,
                    bottomContentPadding = bottomContentPadding,
                    onCopyMessage = { messageId -> copyMessageToClipboard(viewModel, clipboardManager, messageId) },
                    onShowSecondaryModelSheet = onShowSecondaryModelSheet,
                    onComparisonTabChange = onComparisonTabChange,
                    modifier = topInsetModifier,
                )
            } else {
                val singleDisplayMessages = remember(uiState.displayMessages) {
                    collapseParallelToPrimary(uiState.displayMessages)
                }
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
                    displayMessages = singleDisplayMessages,
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

private fun copyMessageToClipboard(
    viewModel: ChatViewModel,
    clipboardManager: ClipboardManager,
    messageId: String,
) {
    val text = viewModel.getMessageText(messageId)
    if (text.isNotBlank()) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Message", text))
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
        onCopyMessage = { messageId -> copyMessageToClipboard(viewModel, clipboardManager, messageId) },
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
        searchFocusRequest = uiState.searchFocusRequest,
        onSearchScrollHandle = viewModel::onSearchScrollHandled,
        bottomContentPadding = bottomContentPadding,
        topContentPadding = topContentPadding,
        modifier = modifier,
    )
}
