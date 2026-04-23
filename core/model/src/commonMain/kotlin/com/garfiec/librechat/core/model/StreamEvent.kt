package com.garfiec.librechat.core.model

import com.garfiec.librechat.core.model.content.MessageContentPart

sealed interface StreamEvent {
    data class ContentDelta(
        val chunk: String,
        val messageId: String? = null,
        val agentId: String? = null,
        val groupId: Int? = null,
    ) : StreamEvent

    data class ToolCallStart(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val agentId: String? = null,
        val groupId: Int? = null,
    ) : StreamEvent

    data class ToolCallComplete(
        val toolCallId: String,
        val output: String,
        val attachments: List<Attachment>? = null,
        val agentId: String? = null,
        val groupId: Int? = null,
    ) : StreamEvent

    data class ThinkingDelta(
        val chunk: String,
        val agentId: String? = null,
        val groupId: Int? = null,
    ) : StreamEvent

    data class AttachmentCreated(
        val fileId: String,
        val filename: String,
        val type: String,
        val filepath: String? = null,
        val toolCallId: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : StreamEvent

    data class Final(
        val message: Message? = null,
        val conversation: Conversation? = null,
        val requestMessage: Message? = null,
        val responseMessage: Message? = null,
        val parseErrors: List<String> = emptyList(),
    ) : StreamEvent {
        val hasParseErrors: Boolean get() = parseErrors.isNotEmpty()
    }

    data class Sync(
        val aggregatedContent: List<MessageContentPart>,
    ) : StreamEvent

    data class Error(
        val message: String,
        val code: String? = null,
        val isNetworkError: Boolean = false,
    ) : StreamEvent

    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
    ) : StreamEvent

    data class Step(
        val stepType: String,
        val stepData: String,
    ) : StreamEvent

    data class Created(
        val conversationId: String,
        val messageId: String,
        val parentMessageId: String,
    ) : StreamEvent

    /**
     * Emitted when the server compacts earlier turns of a long agent chat into
     * a summary. The final summarized text is carried here; the chat UI surfaces
     * it as a "Summarized earlier messages" affordance rather than a normal
     * assistant message.
     *
     * Derived from the `Agents.SummarizeCompleteEvent` LangGraph event introduced
     * with context compaction in v0.8.5.
     */
    data class ContextSummary(
        val summary: String,
        val agentId: String? = null,
        val groupId: Int? = null,
    ) : StreamEvent
}
