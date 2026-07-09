package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.SubagentPhase
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * Live subagent traces (v0.8.6) keyed by the parent `subagent` tool_call id. Owned by
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.SubagentTraceDelegate].
 */
@Immutable
data class SubagentState(
    /** Folded from `on_subagent_update` SSE events while streaming; reset on each new run and
     *  conversation switch alongside `activeToolCalls`. On reload the persisted
     *  `AgentToolCall.subagentContent` is authoritative instead. */
    val subagentProgress: Map<String, SubagentTrace> = emptyMap(),
)

/**
 * Live progress of a single child agent's run (v0.8.6 subagents), accumulated
 * from `on_subagent_update` SSE envelopes and keyed in
 * [ChatUiState.subagentProgress] by the parent `subagent` tool_call id.
 *
 * [parts] are the child's flat content (reasoning / tool calls / text) folded in
 * arrival order — the same shapes a normal message renders, so the trace card
 * reuses the existing content-part renderers (depth 1; a subagent never nests
 * another subagent card). On reload this live trace is superseded by the
 * authoritative persisted `AgentToolCall.subagentContent`.
 */
@Immutable
data class SubagentTrace(
    val parentToolCallId: String,
    val subagentRunId: String? = null,
    val subagentType: String? = null,
    /** Latest phase label for the live ticker (e.g. the agent's display name). */
    val label: String? = null,
    /** Latest lifecycle/content phase reported by the server. */
    val phase: String = SubagentPhase.START,
    val parts: List<MessageContentPart> = emptyList(),
    /** True once the server reports a terminal phase (`stop`/`error`). */
    val isComplete: Boolean = false,
)
