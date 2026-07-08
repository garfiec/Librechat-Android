package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.SubagentPhase
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.viewmodel.SubagentHandle
import com.garfiec.librechat.feature.chat.viewmodel.SubagentTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Folds live `on_subagent_update` SSE envelopes (v0.8.6 subagents) into a
 * per-parent-tool-call [SubagentTrace] buffer in the chat state, so the chat
 * UI can show a child agent's reasoning / tool calls / text as it streams.
 *
 * Correlation mirrors the web client (useStepHandler): prefer the envelope's
 * `parentToolCallId`; if absent, claim the OLDEST unclaimed `subagent` tool_call
 * currently in the active stream. Envelopes that arrive before any subagent
 * tool_call has surfaced are buffered by `subagentRunId` and replayed once a
 * tool_call can be claimed.
 *
 * Lifecycle (owned here, invoked by ChatViewModel at the matching boundaries):
 * - [reset] on each new run/message and on conversation switch — buffers never
 *   leak across messages or bleed into another conversation.
 * - the per-trace [SubagentTrace.parts] list is growth-bounded ([MAX_PARTS]).
 * - [onParentToolCallResolved] marks a trace complete once its parent tool_call
 *   resolves, so we stop accumulating for that key.
 *
 * On a reloaded message the persisted `AgentToolCall.subagentContent` is
 * authoritative and the UI prefers it over any lingering live buffer — this
 * delegate only owns the live path.
 */
class SubagentTraceDelegate(
    private val handle: SubagentHandle,
    private val json: Json,
) {

    /** Maps a `subagentRunId` to the parent tool_call id once claimed. */
    private val runToToolCallId = mutableMapOf<String, String>()

    /** Parent tool_call ids already claimed by a subagent run (oldest-unclaimed fallback). */
    private val claimedToolCallIds = mutableSetOf<String>()

    /** Envelopes that arrived before a parent tool_call could be claimed, keyed by runId. */
    private val pendingByRunId = mutableMapOf<String, MutableList<StreamEvent.SubagentUpdate>>()

    /** Resets all correlation state. Call on new run/message and conversation switch. */
    fun reset() {
        runToToolCallId.clear()
        claimedToolCallIds.clear()
        pendingByRunId.clear()
        handle.update { subagents = subagents.copy(subagentProgress = emptyMap()) }
    }

    /** Marks the trace for a resolved parent tool_call complete (stops accumulation). */
    fun onParentToolCallResolved(toolCallId: String) {
        val existing = handle.state.subagentProgress[toolCallId] ?: return
        if (existing.isComplete) return
        handle.update {
            subagents = subagents.copy(subagentProgress = subagents.subagentProgress + (toolCallId to existing.copy(isComplete = true)))
        }
    }

    fun onUpdate(event: StreamEvent.SubagentUpdate) {
        val toolCallId = resolveParentToolCallId(event)
        if (toolCallId == null) {
            // Can't correlate yet — buffer until a subagent tool_call surfaces.
            val runId = event.subagentRunId ?: return
            pendingByRunId.getOrPut(runId) { mutableListOf() }.add(event)
            return
        }
        // Drain any earlier-buffered envelopes for this run first (in order).
        val runId = event.subagentRunId
        if (runId != null) {
            pendingByRunId.remove(runId)?.forEach { applyToTrace(toolCallId, it) }
        }
        applyToTrace(toolCallId, event)
    }

    /**
     * Resolves the parent `subagent` tool_call id for an envelope. Prefers
     * `parentToolCallId`; otherwise claims the oldest unclaimed `subagent`
     * tool_call in the active stream. Caches by `subagentRunId` so later
     * envelopes from the same run reuse the same parent.
     */
    private fun resolveParentToolCallId(event: StreamEvent.SubagentUpdate): String? {
        val runId = event.subagentRunId
        if (runId != null) {
            runToToolCallId[runId]?.let { return it }
        }
        val explicit = event.parentToolCallId
        if (!explicit.isNullOrBlank()) {
            if (runId != null) runToToolCallId[runId] = explicit
            claimedToolCallIds.add(explicit)
            return explicit
        }
        // Fallback: oldest unclaimed subagent tool_call in the active stream.
        val candidate = handle.state.activeToolCalls.firstOrNull {
            it.name.equals(ToolConstants.SUBAGENT, ignoreCase = true) && it.id !in claimedToolCallIds
        } ?: return null
        if (runId != null) runToToolCallId[runId] = candidate.id
        claimedToolCallIds.add(candidate.id)
        return candidate.id
    }

    private fun applyToTrace(toolCallId: String, event: StreamEvent.SubagentUpdate) {
        val current = handle.state.subagentProgress[toolCallId] ?: SubagentTrace(
            parentToolCallId = toolCallId,
            subagentRunId = event.subagentRunId,
        )
        if (current.isComplete) return

        val isTerminal = SubagentPhase.isTerminal(event.phase)
        val nextParts = event.inner?.let { foldInner(current.parts, it) } ?: current.parts

        handle.update {
            subagents = subagents.copy(
                subagentProgress = subagents.subagentProgress + (
                    toolCallId to current.copy(
                        subagentRunId = current.subagentRunId ?: event.subagentRunId,
                        subagentType = event.subagentType ?: current.subagentType,
                        label = event.label ?: current.label,
                        phase = event.phase,
                        parts = nextParts,
                        isComplete = isTerminal,
                    )
                    ),
            )
        }
    }

    /**
     * Folds a single content-bearing inner event into the trace's parts. Text and
     * reasoning deltas extend the trailing part of the same kind (so streamed
     * tokens coalesce); tool calls add/complete a TOOL_CALL part by id. Bounded
     * to [MAX_PARTS] so a talkative subagent can't grow the buffer unboundedly.
     */
    private fun foldInner(parts: List<MessageContentPart>, inner: StreamEvent): List<MessageContentPart> {
        return when (inner) {
            is StreamEvent.ContentDelta -> appendText(parts, inner.chunk)
            is StreamEvent.ThinkingDelta -> appendThink(parts, inner.chunk)
            is StreamEvent.ToolCallStart -> capParts(
                parts + MessageContentPart(
                    type = ContentType.TOOL_CALL,
                    toolCall = AgentToolCall(
                        id = inner.toolCallId,
                        name = inner.toolName,
                        args = inner.input.toJsonOrNull(),
                    ),
                ),
            )
            is StreamEvent.ToolCallComplete -> completeToolCall(parts, inner.toolCallId, inner.output)
            else -> parts // start/stop/error/run_step_delta carry no foldable content
        }
    }

    private fun appendText(parts: List<MessageContentPart>, chunk: String): List<MessageContentPart> {
        if (chunk.isEmpty()) return parts
        val last = parts.lastOrNull()
        return if (last != null && last.type == ContentType.TEXT) {
            parts.dropLast(1) + last.copy(text = (last.text ?: "") + chunk)
        } else {
            capParts(parts + MessageContentPart(type = ContentType.TEXT, text = chunk))
        }
    }

    private fun appendThink(parts: List<MessageContentPart>, chunk: String): List<MessageContentPart> {
        if (chunk.isEmpty()) return parts
        val last = parts.lastOrNull()
        return if (last != null && last.type == ContentType.THINK) {
            parts.dropLast(1) + last.copy(think = (last.think ?: "") + chunk)
        } else {
            capParts(parts + MessageContentPart(type = ContentType.THINK, think = chunk))
        }
    }

    private fun completeToolCall(
        parts: List<MessageContentPart>,
        toolCallId: String,
        output: String,
    ): List<MessageContentPart> {
        val index = parts.indexOfLast {
            it.type == ContentType.TOOL_CALL && it.toolCall?.id == toolCallId
        }
        if (index < 0) return parts
        val part = parts[index]
        val updated = part.copy(toolCall = part.toolCall?.copy(output = output))
        return parts.toMutableList().also { it[index] = updated }
    }

    private fun capParts(parts: List<MessageContentPart>): List<MessageContentPart> =
        if (parts.size <= MAX_PARTS) parts else parts.takeLast(MAX_PARTS)

    private fun String.toJsonOrNull(): JsonElement? =
        if (isBlank()) {
            null
        } else {
            runCatching { json.parseToJsonElement(this) }.getOrNull()
        }

    private companion object {
        /** Upper bound on accumulated parts per subagent trace (growth guard). */
        const val MAX_PARTS = 200
    }
}
