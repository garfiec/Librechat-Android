package com.garfiec.librechat.core.model

import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage

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
        /** Web-search sources when this is a `web_search` attachment (no file). */
        val webSearch: WebSearchData? = null,
        /** Retrieval citations when this is a `file_search` attachment (no file). */
        val fileSearch: FileSearchData? = null,
        /** The memory write when this is a `memory` attachment (no file). */
        val memory: MemoryArtifactData? = null,
        /** MCP UI resources when this is a `ui_resources` attachment (no file). */
        val uiResources: UiResources? = null,
    ) : StreamEvent

    data class Final(
        val message: Message? = null,
        val conversation: Conversation? = null,
        val requestMessage: Message? = null,
        val responseMessage: Message? = null,
        val parseErrors: List<String> = emptyList(),
        /**
         * The turn ended because it was aborted (user Stop), not because the model finished.
         * The server emits this frame over the SSE stream on abort — the abort POST itself only
         * acks — so it also covers a stop issued from another client on the same conversation.
         *
         * An aborted frame is deliberately poorer than a completed one: it carries `content`
         * parts but no `text`, a stub `conversation`, and a hardcoded `New Chat` title. Callers
         * that persist or apply those fields must special-case it.
         */
        val aborted: Boolean = false,
        /**
         * Aborted before the `created` milestone, so the server saved nothing at all —
         * [responseMessage] and [conversation] are both null. The turn never existed
         * server-side; there is nothing to reconcile against on a later load.
         */
        val earlyAbort: Boolean = false,
        /**
         * Steers the run accepted but never injected, handed back exactly once so the client can
         * re-home them as follow-ups instead of dropping the user's words. The server clears its
         * own copy when it writes this, so a frame that carries them and is ignored loses them.
         */
        val pendingSteers: List<PendingSteer> = emptyList(),
    ) : StreamEvent {
        val hasParseErrors: Boolean get() = parseErrors.isNotEmpty()
    }

    data class Sync(
        val aggregatedContent: List<MessageContentPart>,
    ) : StreamEvent

    /**
     * The run stopped and is waiting on the user: a tool batch needs approval, or the agent
     * asked a clarifying question. Carried live by the `on_pending_action` SSE event and, for a
     * client that reconnects into an already-paused run, by `resumeState.pendingAction` on the
     * sync frame.
     *
     * A paused run is still *active* server-side — no `final` frame is coming until the user
     * decides — so the stream stays open and the chat surface must render resolve controls
     * rather than a live cursor. Resolving posts to `/api/agents/chat/resume`; the continuation
     * arrives on this same stream.
     */
    data class PendingActionRequested(
        val pendingAction: PendingAction,
    ) : StreamEvent

    /**
     * A queued steer reached a tool-batch boundary and went into the run (v0.8.8
     * `on_steer_applied`). The steer is now a `steer` content part of the reply being produced,
     * so the client's own pending chip for [steerId] has served its purpose and should go.
     *
     * Ordering is not guaranteed against the steer's own HTTP 202: this event can arrive first,
     * naming a [steerId] the client has not yet learned. A consumer must therefore record the id
     * as applied rather than only removing a chip that may not exist yet.
     */
    data class SteerApplied(
        val steerId: String,
        /** Absolute index of the injected part within the reply's content. */
        val index: Int? = null,
        /** The steer's text as injected, for a client that never saw its own 202. */
        val text: String? = null,
        val responseMessageId: String? = null,
        val conversationId: String? = null,
    ) : StreamEvent

    /**
     * The server's still-queued steers, replayed on the resume `sync` frame
     * (`resumeState.pendingSteers`) so a client that reconnected mid-run — or opened the
     * conversation on another device — sees the same pending chips as the one that sent them.
     *
     * This is a full snapshot of what is still queued, not a delta: steers injected while the
     * client was away are absent because they are already part of the reply's content.
     */
    data class PendingSteersSynced(
        val pendingSteers: List<PendingSteer>,
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
     * Mid-stream conversation title, emitted by the server (v0.8.7) as an
     * `{event:'title', data:{conversationId, title}}` SSE frame when the interface
     * `titleTiming` is `"immediate"`. Lets the title reveal eagerly during the
     * stream; the post-stream title refetch remains the fallback for servers that
     * don't emit it.
     */
    data class TitleUpdate(
        val conversationId: String,
        val title: String,
    ) : StreamEvent

    /**
     * Provider-reported usage for a completed model call (v0.8.7 `on_token_usage`).
     * Powers the cost portion of the context gauge.
     */
    data class TokenUsageUpdate(
        val usage: TokenUsage,
    ) : StreamEvent

    /**
     * Context-window usage snapshot dispatched before each model call (v0.8.7
     * `on_context_usage`). Powers the context gauge (tokens used vs. window).
     */
    data class ContextUsageUpdate(
        val usage: ContextUsage,
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
