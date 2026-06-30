package com.garfiec.librechat.feature.conversations.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Shared conversation row-action logic (favorite/rename/archive/delete/share/duplicate/tags/export)
 * used by both [ConversationListViewModel] (Room-backed) and [ProjectChatsViewModel] (network-direct).
 *
 * Actions emit one-shot UI signals through [events] and run on the host VM's [scope]. After a
 * mutation that the host must reflect, [onMutated] is invoked: the Room-backed list passes a no-op
 * (its observe-Flow already re-emits), while the network-direct project list passes its `refresh`.
 */
class ConversationListActionsDelegate(
    private val scope: CoroutineScope,
    private val events: MutableSharedFlow<ConversationListEvent>,
    private val conversationRepository: ConversationRepository,
    private val tagRepository: TagRepository,
    private val shareRepository: ShareRepository,
    private val conversationExporter: ConversationExporter,
    private val onMutated: suspend () -> Unit,
) {

    fun toggleFavorite(conversation: Conversation) {
        val id = conversation.conversationId ?: return
        scope.launch {
            when (val result = tagRepository.toggleFavorite(id, conversation.tags)) {
                is Result.Error -> events.emit(
                    ConversationListEvent.ShowError(result.message ?: "Failed to update favorite"),
                )
                else -> onMutated()
            }
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        scope.launch {
            when (conversationRepository.updateTitle(id, newTitle)) {
                is Result.Error -> events.emit(ConversationListEvent.ShowError("Failed to rename conversation"))
                else -> onMutated()
            }
        }
    }

    fun archiveConversation(id: String) {
        scope.launch {
            when (conversationRepository.archive(id, true)) {
                is Result.Error -> events.emit(ConversationListEvent.ShowError("Failed to archive conversation"))
                else -> onMutated()
            }
        }
    }

    fun deleteConversation(id: String) {
        scope.launch {
            when (conversationRepository.delete(id)) {
                is Result.Error -> events.emit(ConversationListEvent.ShowError("Failed to delete conversation"))
                else -> onMutated()
            }
        }
    }

    fun shareConversation(conversationId: String) {
        scope.launch {
            when (val result = shareRepository.createShareLink(conversationId)) {
                is Result.Success -> events.emit(ConversationListEvent.ShareLinkCopied(result.data))
                is Result.Error -> events.emit(
                    ConversationListEvent.ShowError(result.message ?: "Failed to create share link"),
                )
                is Result.Loading -> Unit
            }
        }
    }

    fun duplicateConversation(conversationId: String, title: String?) {
        scope.launch {
            when (val result = conversationRepository.duplicateConversation(conversationId, title)) {
                is Result.Success -> result.data.conversationId?.let {
                    events.emit(ConversationListEvent.NavigateToConversation(it))
                }
                is Result.Error -> events.emit(
                    ConversationListEvent.ShowError(result.message ?: "Failed to duplicate conversation"),
                )
                is Result.Loading -> Unit
            }
        }
    }

    fun updateConversationTags(conversation: Conversation, userTags: List<String>) {
        val id = conversation.conversationId ?: return
        val wasFavorited = SAVED_TAG in conversation.tags
        val cleaned = userTags.filterNot { it == SAVED_TAG }
        val finalTags = if (wasFavorited) cleaned + SAVED_TAG else cleaned
        scope.launch {
            when (tagRepository.setConversationTags(id, finalTags)) {
                is Result.Success -> {
                    tagRepository.refreshTags()
                    // Refresh the host list too: the network-direct project view won't otherwise
                    // re-fetch the edited row's tags (no-op for the Room-backed list).
                    onMutated()
                }
                is Result.Error -> events.emit(ConversationListEvent.ShowError("Failed to update tags"))
                is Result.Loading -> Unit
            }
        }
    }

    fun exportConversation(conversationId: String, title: String?, format: ExportFormat) {
        scope.launch {
            val result = when (format) {
                ExportFormat.JSON -> conversationExporter.exportAsJson(conversationId)
                ExportFormat.MARKDOWN -> conversationExporter.exportAsMarkdown(conversationId)
            }
            when (result) {
                is Result.Success -> events.emit(
                    ConversationListEvent.ExportReady(result.data, format, title ?: "conversation"),
                )
                is Result.Error -> events.emit(
                    ConversationListEvent.ShowError(result.message ?: "Failed to export conversation"),
                )
                is Result.Loading -> Unit
            }
        }
    }
}
