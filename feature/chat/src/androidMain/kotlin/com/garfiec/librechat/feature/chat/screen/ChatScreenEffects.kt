package com.garfiec.librechat.feature.chat.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel

/**
 * Hosts the chat screen's one-shot side effects so [ChatScreen] reads as layout.
 * Covers: new-conversation navigation handoff, error/share-link snackbars,
 * fork/duplicate navigation, stream resume on foreground, the provider-key error
 * snackbar, and back-navigation after a delete/archive clears the conversation.
 */
@Composable
internal fun ChatScreenEffects(
    uiState: ChatUiState,
    shareLinkUrl: String?,
    viewModel: ChatViewModel,
    snackbarHostState: SnackbarHostState,
    clipboardManager: ClipboardManager,
    onConversationStart: ((conversationId: String, isTemporary: Boolean) -> Unit)?,
    onNavigateToConversation: ((String) -> Unit)?,
    onNavigateBack: (() -> Unit)?,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    // When a new conversation starts, navigate to Chat(conversationId) immediately
    // (at StreamEvent.Created) so the NewChat landing page stays clean in the back
    // stack. The new ChatViewModel at Chat(id) will resume the active stream.
    // onPendingNavigationHandled() resets this ViewModel to a fresh landing state.
    LaunchedEffect(uiState.pendingNavigationConversationId) {
        val pendingId = uiState.pendingNavigationConversationId
        if (pendingId != null && onConversationStart != null) {
            // Carry temp-ness onto the Chat(id) route so the new (and any process-death-restored)
            // VM stays temp-aware and never persists the server-hidden conversation to Room.
            onConversationStart(pendingId, uiState.isTemporaryChat)
            viewModel.onPendingNavigationHandled()
        }
    }

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

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(uiState.forkedConversationId) {
        val forkId = uiState.forkedConversationId
        if (forkId != null) {
            viewModel.onForkedConversationHandled()
            if (onNavigateToConversation != null) {
                onNavigateToConversation(forkId)
            } else if (onConversationStart != null) {
                // Forks are always real (non-temp) conversations.
                onConversationStart(forkId, false)
            }
        }
    }

    LaunchedEffect(uiState.duplicatedConversationId) {
        val dupId = uiState.duplicatedConversationId
        if (dupId != null) {
            viewModel.onDuplicatedConversationHandled()
            if (onNavigateToConversation != null) {
                onNavigateToConversation(dupId)
            } else if (onConversationStart != null) {
                // Duplicates are always real (non-temp) conversations.
                onConversationStart(dupId, false)
            }
        }
    }

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
}
