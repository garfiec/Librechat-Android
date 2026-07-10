package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.SubagentHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Behavior tests for [SubagentTraceDelegate]'s correlation + folding logic:
 * explicit `parentToolCallId`, the oldest-unclaimed fallback, pre-tool-call
 * buffering with replay, content folding, and buffer reset on a new run.
 */
class SubagentTraceDelegateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(
        initial: ChatUiState = ChatUiState(),
    ): Pair<SubagentTraceDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(initial)
        val handle = ChatStateHandle(flow, CoroutineScope(TestScope().coroutineContext))
        return SubagentTraceDelegate(SubagentHandle(handle), json) to flow
    }

    private fun messageDelta(
        parentToolCallId: String?,
        runId: String?,
        chunk: String,
        subagentType: String? = null,
        label: String? = null,
    ) = StreamEvent.SubagentUpdate(
        phase = "message_delta",
        parentToolCallId = parentToolCallId,
        subagentRunId = runId,
        subagentType = subagentType,
        label = label,
        inner = StreamEvent.ContentDelta(chunk = chunk),
    )

    @Test
    fun `explicit parentToolCallId keys the trace and folds text`() {
        val (delegate, flow) = fixture()

        delegate.onUpdate(messageDelta("call_1", "run_1", "Hello ", subagentType = "researcher"))
        delegate.onUpdate(messageDelta("call_1", "run_1", "world"))

        val trace = flow.value.subagentProgress["call_1"]
        assertThat(trace).isNotNull()
        assertThat(trace!!.subagentType).isEqualTo("researcher")
        assertThat(trace.parts).hasSize(1)
        assertThat(trace.parts[0].type).isEqualTo(ContentType.TEXT)
        assertThat(trace.parts[0].text).isEqualTo("Hello world")
    }

    @Test
    fun `falls back to oldest unclaimed subagent tool_call when parentToolCallId absent`() {
        val (delegate, flow) = fixture(
            ChatUiState(
                content = MessagesState(
                    activeToolCalls = listOf(
                        ActiveToolCall(id = "call_old", name = "subagent"),
                        ActiveToolCall(id = "call_new", name = "subagent"),
                    ),
                ),
            ),
        )

        // No parentToolCallId → claims the oldest unclaimed subagent tool_call.
        delegate.onUpdate(messageDelta(null, "run_A", "from A"))
        // A second run with no parentToolCallId claims the next unclaimed one.
        delegate.onUpdate(messageDelta(null, "run_B", "from B"))

        assertThat(flow.value.subagentProgress["call_old"]?.parts?.get(0)?.text).isEqualTo("from A")
        assertThat(flow.value.subagentProgress["call_new"]?.parts?.get(0)?.text).isEqualTo("from B")
    }

    @Test
    fun `same run reuses the claimed tool_call across envelopes`() {
        val (delegate, flow) = fixture(
            ChatUiState(content = MessagesState(activeToolCalls = listOf(ActiveToolCall(id = "call_x", name = "subagent")))),
        )
        delegate.onUpdate(messageDelta(null, "run_1", "a"))
        delegate.onUpdate(messageDelta(null, "run_1", "b"))

        assertThat(flow.value.subagentProgress).hasSize(1)
        assertThat(flow.value.subagentProgress["call_x"]?.parts?.get(0)?.text).isEqualTo("ab")
    }

    @Test
    fun `buffers envelopes before a tool_call exists then replays in order`() {
        val (delegate, flow) = fixture()

        // Arrives before any correlation is possible → buffered, no state yet.
        delegate.onUpdate(messageDelta(null, "run_1", "early "))
        assertThat(flow.value.subagentProgress).isEmpty()

        // Now an explicit-parent envelope for the same run resolves + replays.
        delegate.onUpdate(messageDelta("call_1", "run_1", "late"))
        assertThat(flow.value.subagentProgress["call_1"]?.parts?.get(0)?.text).isEqualTo("early late")
    }

    @Test
    fun `folds tool calls and completes their output`() {
        val (delegate, flow) = fixture()
        delegate.onUpdate(
            StreamEvent.SubagentUpdate(
                phase = "run_step",
                parentToolCallId = "call_1",
                subagentRunId = "run_1",
                inner = StreamEvent.ToolCallStart(toolCallId = "c9", toolName = "search", input = ""),
            ),
        )
        delegate.onUpdate(
            StreamEvent.SubagentUpdate(
                phase = "run_step_completed",
                parentToolCallId = "call_1",
                subagentRunId = "run_1",
                inner = StreamEvent.ToolCallComplete(toolCallId = "c9", output = "results"),
            ),
        )
        val part = flow.value.subagentProgress["call_1"]?.parts?.single()
        assertThat(part?.type).isEqualTo(ContentType.TOOL_CALL)
        assertThat(part?.toolCall?.name).isEqualTo("search")
        assertThat(part?.toolCall?.output).isEqualTo("results")
    }

    @Test
    fun `onParentToolCallResolved marks trace complete and stops accumulation`() {
        val (delegate, flow) = fixture()
        delegate.onUpdate(messageDelta("call_1", "run_1", "before "))
        delegate.onParentToolCallResolved("call_1")
        assertThat(flow.value.subagentProgress["call_1"]?.isComplete).isTrue()

        // Further envelopes for a completed trace are ignored.
        delegate.onUpdate(messageDelta("call_1", "run_1", "after"))
        assertThat(flow.value.subagentProgress["call_1"]?.parts?.get(0)?.text).isEqualTo("before ")
    }

    @Test
    fun `reset clears state and correlation buffers across runs`() {
        val (delegate, flow) = fixture(
            ChatUiState(content = MessagesState(activeToolCalls = listOf(ActiveToolCall(id = "call_x", name = "subagent")))),
        )
        delegate.onUpdate(messageDelta(null, "run_1", "first"))
        assertThat(flow.value.subagentProgress).isNotEmpty()

        delegate.reset()
        assertThat(flow.value.subagentProgress).isEmpty()

        // After reset the same tool_call is unclaimed again for a fresh run.
        delegate.onUpdate(messageDelta(null, "run_2", "second"))
        assertThat(flow.value.subagentProgress["call_x"]?.parts?.get(0)?.text).isEqualTo("second")
    }
}
