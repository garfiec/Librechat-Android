package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.feature.chat.util.MessageNode

enum class ChatScreenState { LANDING, LOADING, ACTIVE }

/**
 * The message tree + live streaming surface: persisted messages, the active display path,
 * branch selection, and everything the SSE stream mutates (streaming text, tool calls,
 * attachments, retry/refresh flags, context/token usage). All five audited atomic transactions
 * (completion flash, begin-stream, reset, applyComposer, branch switch) live within this one
 * slice so they stay single StateFlow emissions. Written by [ChatViewModel],
 * StreamingManagerDelegate, MessageTreeDelegate, MessageEditingDelegate, ComparisonModeDelegate
 * and OfficePreviewDelegate.
 */
@Immutable
data class MessagesState(
    val screenState: ChatScreenState = ChatScreenState.LANDING,
    val messages: List<Message> = emptyList(),
    val displayMessages: List<MessageNode> = emptyList(),
    /**
     * A just-sent optimistic user message handed off from the NewChat landing VM, kept on screen
     * until the server persists its own copy. `loadConversation` reconciles it away by id once the
     * server's copy arrives. Null in every other case. See [NewChatSelectionHandoff].
     */
    val pendingResumeUserMessage: Message? = null,
    val activeBranches: Map<String, Int> = emptyMap(),
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val activeToolCalls: List<ActiveToolCall> = emptyList(),
    /** Attachments received during SSE streaming (e.g., tool-generated images). Cleared when
     *  streaming ends. */
    val streamingAttachments: List<Attachment> = emptyList(),
    /** SSE reconnection retry state (null when not retrying). */
    val retryInfo: RetryInfo? = null,
    /**
     * A messages fetch is in flight — a pull-to-refresh, or the background revalidate behind a
     * cache-first open. Drives `MessageList`'s pull-to-refresh indicator, which animates off this
     * boolean alone and needs no gesture. `refreshMessages` early-returns while it is set, so the
     * two paths cannot race each other's clear.
     */
    val isRefreshingMessages: Boolean = false,
    /**
     * The message fetch failed *and* the cache had nothing to fall back to. Drives the retryable
     * empty state; the `error` banner is transient and cannot carry this on its own. Cleared by
     * any load that succeeds (including an offline cache hit).
     */
    val messagesLoadFailed: Boolean = false,
    /** Latest context-window usage snapshot for the gauge (`on_context_usage` SSE / projection). */
    val contextUsage: ContextUsage? = null,
    /** Latest per-call provider token usage (`on_token_usage` SSE). */
    val tokenUsage: TokenUsage? = null,
    /**
     * The live human-review pause blocking this run, or null when nothing is awaiting the user
     * (v0.8.8 HITL). Set from `on_pending_action`, from `resumeState.pendingAction` on a
     * reconnect, and from `/chat/status` on a cold open; cleared when the user's decision is
     * accepted and at every stream end.
     *
     * [isStreaming] stays TRUE alongside it — the run has not finished, the SSE stream is still
     * open, and no `final` frame is coming until the pause resolves. Rendering keys off this
     * field, not off `isStreaming`, to tell "waiting on the model" from "waiting on you".
     */
    val pendingAction: PendingAction? = null,
    /** A decision for [pendingAction] is in flight; the resolve controls are disabled meanwhile. */
    val isResolvingPendingAction: Boolean = false,
)

@Immutable
data class RetryInfo(
    val attempt: Int,
    val maxAttempts: Int,
)

@Immutable
data class ActiveToolCall(
    val id: String,
    val name: String,
    val isComplete: Boolean = false,
    val output: String? = null,
    /** Raw tool-call arguments JSON from [StreamEvent.ToolCallStart]. Holds the
     *  image prompt/quality for image-gen tools so a placeholder can render mid-stream. */
    val input: String? = null,
)
