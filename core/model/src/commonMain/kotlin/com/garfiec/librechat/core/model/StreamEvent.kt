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
        /** Deferred office-doc preview lifecycle (v0.8.6): `pending`/`ready`/`failed`.
         *  The same attachment is emitted twice (pending → ready/failed); the chat
         *  layer upserts by [fileId]. Null for ordinary attachments. */
        val status: String? = null,
        val text: String? = null,
        val textFormat: String? = null,
        val previewError: String? = null,
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

    /**
     * A live progress envelope forwarded by the server's `on_subagent_update`
     * SSE event (v0.8.6 subagents). Each envelope reports one [phase] of a child
     * agent's run, correlated to the parent's `subagent` tool_call via
     * [parentToolCallId] (or [subagentRunId] when the parent id hasn't surfaced
     * yet — mirrors the web client's claim-by-run-id then oldest-unclaimed logic).
     *
     * Content-bearing phases (`message_delta`, `reasoning_delta`, `run_step`,
     * `run_step_completed`) carry their payload pre-mapped in [inner] as the
     * equivalent flat [StreamEvent] (e.g. [ContentDelta], [ThinkingDelta],
     * [ToolCallStart], [ToolCallComplete]) — the inner `data` of a subagent
     * phase is structurally identical to the matching top-level LangGraph event,
     * so the chat reducer folds it with the same logic. Lifecycle phases
     * (`start`, `stop`, `error`) carry [inner] = null and only advance the ticker.
     *
     * The persisted counterpart (after the run finishes) is
     * `AgentToolCall.subagentContent`, attached to the parent tool_call so a
     * reload still shows the trace.
     */
    data class SubagentUpdate(
        val phase: String,
        val parentToolCallId: String? = null,
        val subagentRunId: String? = null,
        val subagentType: String? = null,
        val subagentAgentId: String? = null,
        val label: String? = null,
        /** The phase's content pre-mapped to a flat event, or null for lifecycle phases. */
        val inner: StreamEvent? = null,
    ) : StreamEvent
}
