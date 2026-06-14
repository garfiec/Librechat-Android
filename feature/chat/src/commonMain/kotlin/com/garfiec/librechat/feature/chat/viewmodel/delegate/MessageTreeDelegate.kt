package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.util.mergeFinalMessagesInMemory
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle

/**
 * Owns the message-tree branch state and the in-place streaming anchor — the parts
 * of [com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel] that decide which
 * sibling is displayed per parent and where the in-flight reply attaches in the
 * display path. Also owns the temporary-chat toggle and the temp-chat in-memory
 * finalization (the one finalize path that must never touch Room).
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
     * reachable via sibling navigation, and loadConversation() rebuilds the real
     * path on Final. This truncated displayMessages then simply persists in state
     * for the duration of the stream: safe because no streaming entry point writes
     * to Room mid-stream and none mutate activeBranches, so the Room observer never
     * re-emits to rebuild (and un-truncate) the path before the stream completes.
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
     * Finalizes a temporary chat's display purely in memory, WITHOUT persisting to
     * Room. For a normal chat, handleFinal calls loadConversation, which routes
     * through [com.garfiec.librechat.core.data.repository.MessageRepository.getMessages]'s
     * read-through cache and upserts the message rows to disk — for a temp chat that
     * would leave the message content on disk forever even though the conversation
     * never appears in history. Instead we merge the final request/response messages
     * from the SSE event into the existing in-memory list (replacing the optimistic
     * user message by id) and recompute the display path. Nothing touches the DB.
     */
    fun finalizeTemporaryChatDisplay(event: StreamEvent.Final) {
        val finalMessages = listOfNotNull(event.requestMessage, event.responseMessage ?: event.message)
        if (finalMessages.isEmpty()) return
        stateHandle.update {
            val mergedMessages = mergeFinalMessagesInMemory(messages, finalMessages)
            copy(
                messages = mergedMessages,
                displayMessages = buildActiveMessagePath(mergedMessages, activeBranches),
                screenState = ChatScreenState.ACTIVE,
            )
        }
    }
}
