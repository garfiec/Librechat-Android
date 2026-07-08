package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.viewmodel.ChatRequestBuilder
import com.garfiec.librechat.feature.chat.viewmodel.MessageEditingHandle
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the message-mutation send paths — edit (user / assistant), regenerate, and continue —
 * plus the inline edit-field UI state. Each path optimistically reshapes the tree (a new
 * sibling or a stream anchor via [MessageTreeDelegate]) and resubmits through
 * [StreamingManagerDelegate], reusing [ChatRequestBuilder] for the shared request pieces.
 *
 * The send-readiness gate and message-text extraction live in `ChatViewModel` (shared with
 * the new-message send path and TTS); they're injected as [runWhenSendReady] / [getMessageText].
 */
class MessageEditingDelegate(
    private val handle: MessageEditingHandle,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val treeDelegate: MessageTreeDelegate,
    private val streamingManager: StreamingManagerDelegate,
    private val requestBuilder: ChatRequestBuilder,
    private val getMessageText: (String) -> String,
    private val runWhenSendReady: (action: () -> Unit) -> Unit,
) {

    fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank() || handle.state.isStreaming) return

        val originalMessage = handle.state.messages.find { it.messageId == messageId } ?: return

        runWhenSendReady {
            if (originalMessage.isCreatedByUser) {
                editUserMessage(originalMessage, newText)
            } else {
                editAiMessage(originalMessage)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun editUserMessage(originalMessage: Message, newText: String) {
        val parentMessageId = originalMessage.parentMessageId

        // Optimistically insert the edited text as a new sibling of the original user
        // message and anchor the stream to it, so the new message — with the response
        // streaming below it — replaces the old branch in place. The anchor truncates
        // the active path here (see buildActiveMessagePath), mirroring the web client's
        // `currentMsg + initialResponse` placeholder insert. The optimistic id is sent
        // as `userMessageId` so the server adopts it; that lets the message reconcile by
        // id on Final — via loadConversation() for normal chats, or in-memory via
        // mergeFinalMessagesInMemory for temp chats (which never touch Room).
        val optimisticMessage = Message(
            messageId = Uuid.random().toString(),
            conversationId = handle.state.conversationId ?: "",
            parentMessageId = parentMessageId,
            text = newText,
            isCreatedByUser = true,
            sender = "User",
            createdAt = Clock.System.now().toString(),
            files = originalMessage.files,
        )
        handle.update {
            val updatedMessages = content.messages + optimisticMessage
            content = content.copy(
                messages = updatedMessages,
                displayMessages = buildActiveMessagePath(updatedMessages, content.activeBranches, optimisticMessage.messageId),
            )
        }

        launchSend(
            text = newText,
            parentMessageId = parentMessageId,
            userMessageId = optimisticMessage.messageId,
            files = originalMessage.files,
            isEdited = true,
            logLabel = "editUserMessage",
        )
    }

    private fun editAiMessage(aiMessage: Message) {
        val parentUserMessage = handle.state.messages.find {
            it.messageId == aiMessage.parentMessageId
        } ?: return

        // Editing an assistant message resubmits its parent user turn (isEdited +
        // isRegenerate) — the same shape as regenerate, so anchor the stream to the
        // parent user message and let the new response stream in below it, replacing
        // the old one. The web client seeds the placeholder with the edited content
        // for a transient preview; we don't (the regenerated server response is
        // authoritative on Final either way).
        if (handle.state.conversationId == null) return
        treeDelegate.anchorStreamTo(parentUserMessage.messageId)
        launchSend(
            text = parentUserMessage.text,
            parentMessageId = parentUserMessage.parentMessageId,
            overrideParentMessageId = parentUserMessage.messageId,
            files = parentUserMessage.files,
            isEdited = true,
            isRegenerate = true,
            logLabel = "editAiMessage",
        )
    }

    fun regenerateMessage(messageId: String) {
        if (handle.state.isStreaming) return

        val aiMessage = handle.state.messages.find { it.messageId == messageId } ?: return
        if (aiMessage.isCreatedByUser) return

        val parentUserMessage = handle.state.messages.find {
            it.messageId == aiMessage.parentMessageId
        } ?: return

        runWhenSendReady { regenerateMessageNow(parentUserMessage) }
    }

    private fun regenerateMessageNow(parentUserMessage: Message) {
        treeDelegate.anchorStreamTo(parentUserMessage.messageId)
        launchSend(
            text = parentUserMessage.text,
            parentMessageId = parentUserMessage.parentMessageId,
            overrideParentMessageId = parentUserMessage.messageId,
            files = parentUserMessage.files,
            isRegenerate = true,
            logLabel = "regenerateMessage",
        )
    }

    fun continueGeneration() {
        if (handle.state.isStreaming) return
        val lastAiMessage = handle.state.displayMessages.lastOrNull {
            !it.message.isCreatedByUser
        } ?: return

        val parentUserMessage = handle.state.messages.find {
            it.messageId == lastAiMessage.message.parentMessageId
        } ?: return

        runWhenSendReady { continueGenerationNow(lastAiMessage.message, parentUserMessage) }
    }

    private fun continueGenerationNow(lastAiMessage: Message, parentUserMessage: Message) {
        launchSend(
            text = parentUserMessage.text,
            parentMessageId = parentUserMessage.parentMessageId,
            overrideParentMessageId = parentUserMessage.messageId,
            responseMessageId = lastAiMessage.messageId,
            files = parentUserMessage.files,
            isEdited = true,
            isRegenerate = true,
            isContinued = true,
            logLabel = "continueGeneration",
        )
    }

    /**
     * Shared tail for the edit / regenerate / continue paths: snapshots the current
     * selection, builds the per-send request pieces via [ChatRequestBuilder], and launches
     * the resubmit stream. Each caller first reshapes the tree (optimistic insert or
     * [MessageTreeDelegate.anchorStreamTo]) and then supplies only the args that differ.
     *
     * Resubmits carry the original user turn's [files] so attachments survive an edit /
     * regenerate / continue (the server otherwise loses them). The new-message send path
     * (`doSendWithSpec`) stays in `ChatViewModel`: it additionally carries an added-conversation
     * for comparison mode and a bespoke stream-terminated callback this helper deliberately omits.
     */
    @Suppress("LongParameterList")
    private fun launchSend(
        text: String,
        parentMessageId: String?,
        logLabel: String,
        userMessageId: String? = null,
        overrideParentMessageId: String? = null,
        responseMessageId: String? = null,
        files: List<FileReference>? = null,
        isEdited: Boolean = false,
        isRegenerate: Boolean = false,
        isContinued: Boolean = false,
    ) {
        streamingManager.prepareForStreaming(isEdit = true)

        val state = handle.state
        val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = state.modelParameters.webSearch
        val ephemeralAgent = requestBuilder.buildEphemeralAgent()
        Logger.d { "$logLabel: webSearch=$webSearchEnabled, ephemeralAgent=$ephemeralAgent" }
        val dispatch = requestBuilder.currentDispatch()
        streamingManager.launchStream(
            chatRepository.startChat(
                text = text,
                conversationId = state.conversationId,
                endpoint = state.selectedEndpoint,
                endpointType = dispatch.endpointType,
                key = dispatch.key,
                modelDisplayLabel = dispatch.modelDisplayLabel,
                model = state.selectedModel,
                userMessageId = userMessageId,
                parentMessageId = parentMessageId,
                agentId = if (isAgent) state.selectedModel else null,
                overrideParentMessageId = overrideParentMessageId,
                responseMessageId = responseMessageId,
                isEdited = isEdited,
                isRegenerate = isRegenerate,
                isContinued = isContinued,
                webSearch = webSearchEnabled,
                files = files,
                ephemeralAgent = ephemeralAgent,
                isTemporary = state.isTemporaryChat,
                modelParams = requestBuilder.buildModelParams(),
            ),
        )
    }

    // ── Inline edit-field UI state ───────────────────────────────────

    fun startEditing(messageId: String) {
        val text = getMessageText(messageId)
        handle.update { editing = editing.copy(editingMessageId = messageId, editingText = text) }
    }

    fun onEditTextChanged(text: String) {
        handle.update { editing = editing.copy(editingText = text) }
    }

    fun cancelEditing() {
        handle.update { editing = editing.copy(editingMessageId = null, editingText = "") }
    }

    fun submitEdit() {
        val messageId = handle.state.editingMessageId ?: return
        val newText = handle.state.editingText.trim()
        if (newText.isBlank()) return

        handle.update { editing = editing.copy(editingMessageId = null, editingText = "") }
        editMessage(messageId, newText)
    }

    fun saveEditOnly() {
        val messageId = handle.state.editingMessageId ?: return
        val conversationId = handle.state.conversationId ?: return
        val newText = handle.state.editingText.trim()
        if (newText.isBlank()) return

        handle.update { editing = editing.copy(editingMessageId = null, editingText = "") }
        handle.scope.launch {
            messageRepository.updateMessageText(conversationId, messageId, newText)
        }
    }
}
