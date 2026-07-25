package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.AskUserQuestionRequest
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingActionPayload
import com.garfiec.librechat.core.model.PendingActionTypes
import com.garfiec.librechat.core.model.ToolApprovalRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [ChatUiState.renderablePendingAction].
 *
 * The load-bearing property is that it does NOT consult any backend-version gate: a pause is
 * self-proving (only a HITL-capable server can announce one), and a version/date gate would fail
 * closed on servers built past the pinned upstream commit — the exact servers that pause. A hidden
 * card there strands the user on a stream that stays `isStreaming` forever. See VERSION_GATES.md.
 *
 * Pure-function tests — no ViewModel instantiation.
 */
class ChatUiStateRenderablePendingActionTest {

    private fun stateWith(action: PendingAction?) =
        ChatUiState(content = MessagesState(pendingAction = action))

    private fun toolApproval(actionId: String?) = PendingAction(
        actionId = actionId,
        payload = PendingActionPayload(
            type = PendingActionTypes.TOOL_APPROVAL,
            actionRequests = listOf(ToolApprovalRequest(name = "run_code", toolCallId = "call_1")),
        ),
    )

    private fun askUserQuestion(actionId: String?) = PendingAction(
        actionId = actionId,
        payload = PendingActionPayload(
            type = PendingActionTypes.ASK_USER_QUESTION,
            question = AskUserQuestionRequest(question = "Which one?"),
        ),
    )

    @Test
    fun `tool approval renders on the default gate state`() {
        val action = toolApproval("act_1")
        assertThat(stateWith(action).renderablePendingAction).isEqualTo(action)
    }

    @Test
    fun `ask user question renders on the default gate state`() {
        val action = askUserQuestion("act_1")
        assertThat(stateWith(action).renderablePendingAction).isEqualTo(action)
    }

    @Test
    fun `null pending action renders nothing`() {
        assertThat(stateWith(null).renderablePendingAction).isNull()
    }

    @Test
    fun `action without an actionId is dropped`() {
        assertThat(stateWith(toolApproval(null)).renderablePendingAction).isNull()
        assertThat(stateWith(toolApproval("")).renderablePendingAction).isNull()
        assertThat(stateWith(toolApproval("  ")).renderablePendingAction).isNull()
    }

    @Test
    fun `unknown future payload type is dropped rather than shown as an empty card`() {
        val action = PendingAction(
            actionId = "act_1",
            payload = PendingActionPayload(type = "some_future_interrupt"),
        )
        assertThat(stateWith(action).renderablePendingAction).isNull()
    }

    @Test
    fun `action with no payload at all is dropped`() {
        assertThat(stateWith(PendingAction(actionId = "act_1")).renderablePendingAction).isNull()
    }
}
