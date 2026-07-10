package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable

/**
 * Identity and top-level metadata of the current conversation. Written by [ChatViewModel],
 * StreamingManagerDelegate, SendCompletionDelegate, ConversationActionsDelegate,
 * ComparisonModeDelegate and MessageTreeDelegate.
 */
@Immutable
data class ConversationMetaState(
    val conversationId: String? = null,
    val conversationTitle: String? = null,
    val isTemporaryChat: Boolean = false,
    val sharedLinksEnabled: Boolean = false,
    /** Set at StreamEvent.Created when a new conversation's conversationId becomes available.
     *  The UI navigates to Chat(id) and then clears this via [ChatViewModel.onPendingNavigationHandled],
     *  which also resets this ViewModel to a clean landing state. */
    val pendingNavigationConversationId: String? = null,
)
