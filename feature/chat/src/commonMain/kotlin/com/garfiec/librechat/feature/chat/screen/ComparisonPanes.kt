package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.feature.chat.components.ComparisonDualPane
import com.garfiec.librechat.feature.chat.components.ComparisonTabBar
import com.garfiec.librechat.feature.chat.components.MessageList
import com.garfiec.librechat.feature.chat.components.ModelSelectorButton
import com.garfiec.librechat.feature.chat.components.SecondaryMessageList
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.select_model
import com.garfiec.librechat.feature.chat.util.buildComparisonDisplayMessages
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Shared Compare Models layout for both platforms. Lays out the primary/secondary
 * panes side-by-side on wide screens (`maxWidth >= 600.dp`) or as a tab pager on
 * phones, deriving each pane's content from the persisted per-agent parts (see
 * [buildComparisonDisplayMessages]). All pieces are `commonMain`; the only
 * platform-variant is [onCopyMessage] (Android writes the system clipboard, iOS
 * keeps its own copy behavior) and the [modifier] each caller supplies for sizing
 * (Android weights it in a Column, iOS fills the Box above the input overlay).
 */
@Composable
internal fun ComparisonPanes(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    displayModel: String?,
    senderName: String,
    fontSizeMultiplier: Float,
    showImageDescriptions: Boolean,
    chatLayoutStyle: String,
    showAvatars: Boolean,
    showBubbles: Boolean,
    useKatex: Boolean,
    bottomContentPadding: Dp,
    onCopyMessage: (String) -> Unit,
    onShowSecondaryModelSheet: () -> Unit,
    onComparisonTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val comparisonState = uiState.comparisonState

    val canBranch = comparisonState.parallelMessageId != null &&
        !comparisonState.primaryIsStreaming &&
        !comparisonState.secondaryIsStreaming

    val primaryDisplayMessages = remember(
        uiState.displayMessages,
        comparisonState.parallelMessageId,
        comparisonState.primaryFinalContent,
        senderName,
    ) {
        buildComparisonDisplayMessages(
            uiState.displayMessages,
            secondary = false,
            comparisonState.parallelMessageId,
            comparisonState.primaryFinalContent,
            senderName,
        )
    }
    val secondarySenderName = viewModel.getSecondaryModelDisplayName()
        ?: comparisonState.secondaryModel ?: "Assistant"
    val secondaryEndpoint = comparisonState.secondaryEndpoint ?: EndpointConstants.AGENTS
    // Re-point the secondary bubble avatar at its own agent: the persisted parallel
    // message only carries the primary's endpoint/iconURL. For an agents-endpoint
    // secondary, resolve the agent's avatar; otherwise fall back to the endpoint icon.
    val secondaryIconUrl = remember(
        secondaryEndpoint,
        comparisonState.secondaryModel,
        uiState.agents,
        uiState.serverUrl,
    ) {
        val model = comparisonState.secondaryModel
        if (secondaryEndpoint == EndpointConstants.AGENTS && model != null) {
            uiState.agents.find { it.id == model }?.avatarUrl?.let { url ->
                if (url.startsWith("http")) url else "${uiState.serverUrl}$url"
            }
        } else {
            null
        }
    }
    val secondaryDisplayMessages = remember(
        uiState.displayMessages,
        comparisonState.parallelMessageId,
        comparisonState.secondaryFinalContent,
        secondarySenderName,
        secondaryEndpoint,
        secondaryIconUrl,
    ) {
        buildComparisonDisplayMessages(
            uiState.displayMessages,
            secondary = true,
            comparisonState.parallelMessageId,
            comparisonState.secondaryFinalContent,
            secondarySenderName,
            secondaryEndpoint = secondaryEndpoint,
            secondaryIconUrl = secondaryIconUrl,
        )
    }

    val primaryMessageList: @Composable () -> Unit = {
        MessageList(
            displayMessages = primaryDisplayMessages,
            isStreaming = comparisonState.primaryIsStreaming || uiState.isStreaming,
            justSettledMessageId = uiState.justSettledMessageId,
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
            onCopyMessage = onCopyMessage,
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
            // The comparison container is already padded clear of the floating bar.
            topContentPadding = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
    }

    val secondaryModelName = viewModel.getSecondaryModelDisplayName()
        ?: comparisonState.secondaryModel
        ?: stringResource(Res.string.select_model)

    val secondaryMessageList: @Composable () -> Unit = {
        SecondaryMessageList(
            displayMessages = secondaryDisplayMessages,
            isStreaming = comparisonState.secondaryIsStreaming,
            justSettledMessageId = uiState.justSettledMessageId,
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

    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= 600.dp) {
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
                modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
