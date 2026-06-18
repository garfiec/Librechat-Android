package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.util.finalMessages
import com.garfiec.librechat.feature.chat.util.mergeFinalMessagesInMemory
import com.garfiec.librechat.feature.chat.util.resolvedResponseMessage
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle

/**
 * Owns the message-tree branch state and the in-place streaming anchor — the parts
 * of [com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel] that decide which
 * sibling is displayed per parent and where the in-flight reply attaches in the
 * display path. Also owns the temporary-chat toggle and the in-memory finalize
 * ([finalizeChatDisplay]) that drives the gap-free completion view.
 *
 * Pure state transforms over [ChatStateHandle] — no repositories, no coroutines.
 * The streaming-anchor invariant lives here (see [anchorStreamTo]); the send/edit
 * paths call into this delegate to truncate the path so the trailing streaming
 * bubble renders as the pending child of the right branch.
 */
class MessageTreeDelegate(
    private val stateHandle: ChatStateHandle,
) {

    fun switchBranch(parentMessageId: String, siblingIndex: Int) {
        // Ignore branch switches mid-stream: the in-flight reply's path is truncated at
        // its parent (see anchorStreamTo / buildActiveMessagePath's streamingLeafId), and
        // mutating activeBranches re-triggers loadConversation's combine, which rebuilds
        // displayMessages WITHOUT the leaf — un-truncating the path and dropping the
        // streaming reply back to the end. editMessage/regenerateMessage are likewise
        // gated on isStreaming; this closes the same gap for sibling navigation.
        if (stateHandle.state.isStreaming) return
        // Only mutate the branch selection; the observeMessages/activeBranches combine in
        // loadConversation recomputes displayMessages off the Main thread in response, so the
        // tree walk no longer runs synchronously on this UI click path.
        stateHandle.update {
            val newBranches = activeBranches.toMutableMap()
            newBranches[parentMessageId] = siblingIndex
            copy(activeBranches = newBranches)
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
        stateHandle.update {
            copy(
                displayMessages = buildActiveMessagePath(messages, activeBranches, parentMessageId),
            )
        }
    }

    fun toggleTemporaryChat() {
        // Only togglable before the conversation exists. Once a temporary chat is
        // active the toggle is a read-only indicator — the server already created
        // it temporary, so flipping it off here would be misleading.
        if (stateHandle.state.conversationId != null) return
        stateHandle.update { copy(isTemporaryChat = !isTemporaryChat) }
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
        val finalMessages = event.finalMessages()
        // Retiring the streaming bubble (clearing the streaming fields) and swapping in the
        // finalized message MUST be ONE update: with a Main.immediate StateFlow every write is
        // observed, so two updates leave a frame where the bubble is gone but the reply isn't
        // in displayMessages yet — the completion flash. (Comparison chats clear in handleFinal.)
        stateHandle.update {
            val mergedMessages = if (finalMessages.isEmpty()) {
                messages
            } else {
                mergeFinalMessagesInMemory(messages, finalMessages)
            }
            copy(
                messages = mergedMessages,
                displayMessages = buildActiveMessagePath(mergedMessages, activeBranches),
                screenState = ChatScreenState.ACTIVE,
                isStreaming = false,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
            )
        }
        if (finalMessages.isEmpty()) return emptyList()
        val response = event.resolvedResponseMessage()
        val request = event.requestMessage
            ?: response?.parentMessageId?.let { parentId ->
                stateHandle.state.messages.firstOrNull { it.messageId == parentId }
            }
        return listOfNotNull(request, response)
    }
}
