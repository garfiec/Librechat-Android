package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.feature.chat.viewmodel.ConversationActionsHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationActionsDelegate(
    private val handle: ConversationActionsHandle,
    private val conversationRepository: ConversationRepository,
    private val shareRepository: ShareRepository,
) {

    private val _shareLinkUrl = MutableStateFlow<String?>(null)
    val shareLinkUrl: StateFlow<String?> = _shareLinkUrl.asStateFlow()

    fun showRenameDialog() {
        handle.update { actions = actions.copy(showRenameDialog = true) }
    }

    fun dismissRenameDialog() {
        handle.update { actions = actions.copy(showRenameDialog = false) }
    }

    fun renameConversation(newTitle: String) {
        val conversationId = handle.state.conversationId ?: return
        handle.update { actions = actions.copy(showRenameDialog = false) }
        handle.scope.launch {
            when (conversationRepository.updateTitle(conversationId, newTitle)) {
                is Result.Success -> {
                    handle.update { conversation = conversation.copy(conversationTitle = newTitle) }
                }
                is Result.Error -> {
                    handle.setError("Failed to rename conversation")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun showDeleteConfirmation() {
        handle.update { actions = actions.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        handle.update { actions = actions.copy(showDeleteConfirmation = false) }
    }

    fun deleteConversation() {
        val conversationId = handle.state.conversationId ?: return
        handle.update { actions = actions.copy(showDeleteConfirmation = false) }
        handle.scope.launch {
            when (conversationRepository.delete(conversationId)) {
                is Result.Success -> {
                    // Signal navigation back by clearing the conversation
                    handle.update { conversation = conversation.copy(conversationId = null) }
                }
                is Result.Error -> {
                    handle.setError("Failed to delete conversation")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun archiveConversation() {
        val conversationId = handle.state.conversationId ?: return
        handle.scope.launch {
            when (conversationRepository.archive(conversationId, true)) {
                is Result.Success -> {
                    // Signal navigation back by clearing the conversation
                    handle.update { conversation = conversation.copy(conversationId = null) }
                }
                is Result.Error -> {
                    handle.setError("Failed to archive conversation")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun duplicateConversation() {
        val conversationId = handle.state.conversationId ?: return
        val title = handle.state.conversationTitle
        handle.scope.launch {
            when (val result = conversationRepository.duplicateConversation(conversationId, title)) {
                is Result.Success -> {
                    handle.update { actions = actions.copy(duplicatedConversationId = result.data.conversationId) }
                }
                is Result.Error -> {
                    handle.setError("Failed to duplicate conversation")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun onDuplicatedConversationHandled() {
        handle.update { actions = actions.copy(duplicatedConversationId = null) }
    }

    fun shareConversation() {
        val conversationId = handle.state.conversationId ?: return
        handle.scope.launch {
            when (val result = shareRepository.createShareLink(conversationId)) {
                is Result.Success -> {
                    handle.setError(null)
                    // Store the share URL to be copied by the UI
                    _shareLinkUrl.value = result.data
                }
                is Result.Error -> {
                    handle.setError(result.message ?: "Failed to create share link")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun onShareLinkHandled() {
        _shareLinkUrl.value = null
    }

    fun showForkOptions(messageId: String) {
        handle.update { actions = actions.copy(showForkOptionsForMessageId = messageId) }
    }

    fun dismissForkOptions() {
        handle.update { actions = actions.copy(showForkOptionsForMessageId = null) }
    }

    fun forkFromMessage(messageId: String, option: String, splitAtTarget: Boolean = false) {
        val conversationId = handle.state.conversationId ?: return
        val latestMessageId = handle.state.displayMessages.lastOrNull()?.message?.messageId
        handle.update {
            actions = actions.copy(
                showForkOptionsForMessageId = null,
                isForkInProgress = true,
            )
        }
        handle.scope.launch {
            val result = conversationRepository.forkConversation(
                conversationId = conversationId,
                messageId = messageId,
                option = option,
                splitAtTarget = if (splitAtTarget) true else null,
                latestMessageId = if (splitAtTarget) latestMessageId else null,
            )
            when (result) {
                is Result.Success -> {
                    handle.update {
                        actions = actions.copy(
                            forkedConversationId = result.data.conversationId,
                            isForkInProgress = false,
                        )
                    }
                }
                is Result.Error -> {
                    handle.update {
                        error = result.message ?: "Could not fork conversation"
                        actions = actions.copy(isForkInProgress = false)
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun onForkedConversationHandled() {
        handle.update { actions = actions.copy(forkedConversationId = null) }
    }
}
