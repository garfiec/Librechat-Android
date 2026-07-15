package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.util.mergeFinalMessagesInMemory
import com.garfiec.librechat.feature.chat.util.resolvedResponseMessage
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.MessageTreeHandle

/**
 * Owns the message-tree branch state and the in-place streaming anchor — the parts
 * of [com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel] that decide which
 * sibling is displayed per parent and where the in-flight reply attaches in the
 * display path. Also owns the temporary-chat toggle and the in-memory finalize
 * ([finalizeChatDisplay]) that drives the gap-free completion view.
 *
 * Pure state transforms over [MessageTreeHandle] — no repositories, no coroutines.
 * The streaming-anchor invariant lives here (see [anchorStreamTo]); the send/edit
 * paths call into this delegate to truncate the path so the trailing streaming
 * bubble renders as the pending child of the right branch.
 */
class MessageTreeDelegate(
    private val handle: MessageTreeHandle,
) {

    fun switchBranch(parentMessageId: String, siblingIndex: Int) {
        // Ignore branch switches mid-stream: the in-flight reply's path is truncated at
        // its parent (see anchorStreamTo / buildActiveMessagePath's streamingLeafId), and
        // mutating activeBranches re-triggers loadConversation's combine, which rebuilds
        // displayMessages WITHOUT the leaf — un-truncating the path and dropping the
        // streaming reply back to the end. editMessage/regenerateMessage are likewise
        // gated on isStreaming; this closes the same gap for sibling navigation.
        if (handle.state.isStreaming) return
        // Only mutate the branch selection; the observeMessages/activeBranches combine in
        // loadConversation recomputes displayMessages off the Main thread in response, so the
        // tree walk no longer runs synchronously on this UI click path.
        handle.update {
            val newBranches = content.activeBranches.toMutableMap()
            newBranches[parentMessageId] = siblingIndex
            // The sibling we're switching to has a different message history, so the seeded context
            // gauge no longer describes it. Clear it (we're !isStreaming here, so no live SSE to
            // disturb) and let ContextProjectionDelegate re-project once displayMessages rebuilds
            // with the new tail. sameWindowAs() ignores the tail, so without this the stale numerator
            // would linger until the next send.
            content = content.copy(activeBranches = newBranches, contextUsage = null)
        }
    }

    /**
     * Rebuilds the active path truncated at [parentMessageId] — the message the
     * in-flight reply attaches to — so the streaming bubble renders in place
     * (replacing the stale branch for edit/regenerate) rather than being appended
     * after it. Used by the paths that reuse an existing message as the parent
     * (regenerate, edit-AI); the optimistic-message paths (send, edit-user) pass the
     * same leaf to [buildActiveMessagePath] inline alongside their message insert.
     *
     * The full tree stays in `messages` and the DB, so the old branch remains
     * reachable via sibling navigation, and on Final the untruncated path is rebuilt
     * in memory by [finalizeChatDisplay] (normal + temp chats) or by the background
     * Room reconcile (comparison chats). This truncated displayMessages then simply
     * persists in state for the duration of the stream: safe because no streaming
     * entry point writes to Room mid-stream and none mutate activeBranches, so the
     * Room observer never re-emits to rebuild (and un-truncate) the path before the
     * stream completes.
     */
    fun anchorStreamTo(parentMessageId: String) {
        handle.update {
            content = content.copy(
                displayMessages = buildActiveMessagePath(content.messages, content.activeBranches, parentMessageId),
            )
        }
    }

    fun toggleTemporaryChat() {
        // Only togglable before the conversation exists. Once a temporary chat is
        // active the toggle is a read-only indicator — the server already created
        // it temporary, so flipping it off here would be misleading.
        if (handle.state.conversationId != null) return
        handle.update { conversation = conversation.copy(isTemporaryChat = !conversation.isTemporaryChat) }
    }

    /**
     * Finalizes a chat's display purely in memory by merging the SSE [event]'s
     * request/response into the in-memory list (replacing the optimistic user message by id)
     * and recomputing the display path — no Room access. This keeps the just-streamed reply
     * on screen the instant `isStreaming` flips false, instead of round-tripping a network
     * reload. Normal chats follow up with a background Room reconcile (server stays source of
     * truth); temp chats deliberately skip it, since the read-through cache would upsert their
     * rows to disk. Keeping that "never touch Room" decision in the caller lets both share
     * this merge.
     *
     * Returns the completed turn (request then response) for the caller to persist, so the
     * cached rows can't drift from the screen. The request is backfilled from its in-memory
     * parent when the payload omits it — the server adopts the client-minted id (PR #139), so
     * the response's parentMessageId matches the optimistic user message, which was never
     * written to Room during streaming and would otherwise be lost on reopen. Temp chats
     * ignore the return value.
     */
    fun finalizeChatDisplay(event: StreamEvent.Final): List<Message> {
        // Defensive: upstream 0.8.7 sets responseMessage.attachments before saving, but the app
        // supports a backend version range. If the Final payload omitted a file that streamed in
        // via `attachment` SSE events, fold it back in (by fileId) so it survives the reload — the
        // streamed list is about to be cleared in the update below. Cheap insurance.
        val streamedAttachments = handle.state.streamingAttachments
        val response = event.resolvedResponseMessage()?.let { mergeStreamedAttachments(it, streamedAttachments) }
        val finalMessages = listOfNotNull(event.requestMessage, response)
        // Retiring the streaming bubble (clearing the streaming fields) and swapping in the
        // finalized message MUST be ONE update: with a Main.immediate StateFlow every write is
        // observed, so two updates leave a frame where the bubble is gone but the reply isn't
        // in displayMessages yet — the completion flash. (Comparison chats clear in handleFinal.)
        handle.update {
            val mergedMessages = if (finalMessages.isEmpty()) {
                content.messages
            } else {
                mergeFinalMessagesInMemory(content.messages, finalMessages)
            }
            // Retire the streaming bubble and swap in the finalized message in ONE slice copy —
            // all these fields live in `content`, so this stays a single StateFlow emission and
            // the completion-flash invariant holds structurally.
            content = content.copy(
                messages = mergedMessages,
                displayMessages = buildActiveMessagePath(mergedMessages, content.activeBranches),
                screenState = ChatScreenState.ACTIVE,
                isStreaming = false,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
                // The turn is finalized and (for normal chats) about to be persisted, so the
                // handed-off optimistic seed has done its job. Clear it unconditionally — not just
                // on an id match in loadConversation — so a backend that never echoes the
                // client-minted id can't strand the seed and keep re-appending it as a phantom
                // sibling. See pendingResumeUserMessage / NewChatSelectionHandoff.
                pendingResumeUserMessage = null,
            )
        }
        if (finalMessages.isEmpty()) return emptyList()
        val request = event.requestMessage
            ?: response?.parentMessageId?.let { parentId ->
                handle.state.messages.firstOrNull { it.messageId == parentId }
            }
        return listOfNotNull(request, response)
    }
}

/**
 * Upserts [streamed] attachments into [message] by `fileId`, keeping the server's copy on
 * conflict. Only fills gaps — when the Final payload already carried every streamed file, the
 * message is returned unchanged. Attachments with no fileId are skipped (can't be deduped, and
 * generated files always carry one).
 */
internal fun mergeStreamedAttachments(message: Message, streamed: List<Attachment>): Message {
    if (streamed.isEmpty()) return message
    val existing = message.attachments.orEmpty()
    val existingIds = existing.mapNotNull { it.fileId }.toHashSet()
    val toAdd = streamed.filter { it.fileId != null && it.fileId !in existingIds }
    if (toAdd.isEmpty()) return message
    return message.copy(attachments = existing + toAdd)
}
