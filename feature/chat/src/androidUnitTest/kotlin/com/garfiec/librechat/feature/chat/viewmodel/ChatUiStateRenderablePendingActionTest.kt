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
    // ── Composer routing while a run is paused ────────────────────────────

    private fun pausedState(action: PendingAction?, resolving: Boolean = false) = ChatUiState(
        conversation = ConversationMetaState(conversationId = "conv-1"),
        content = MessagesState(pendingAction = action, isResolvingPendingAction = resolving),
    )

    /**
     * The composer is the input the user can see — the pause card carries its own field but sits
     * at the tail of the thread. Sending here must ANSWER the pause; queueing it silently left
     * the run unresolved and delivered the text as a non-sequitur after the pause expired.
     */
    @Test
    fun `a live ask-user-question pause takes the composer's send`() {
        val target = pausedState(askUserQuestion("act_1")).duringRunSendTarget
        assertThat(target).isEqualTo(DuringRunSendTarget.ANSWER_PAUSE)
    }

    /** Tool approvals take decisions, not prose, so free text there is a genuine follow-up. */
    @Test
    fun `a tool-approval pause leaves the composer queueing`() {
        val target = pausedState(toolApproval("act_1")).duringRunSendTarget
        assertThat(target).isEqualTo(DuringRunSendTarget.QUEUE)
    }

    /** A submit already in flight must not be able to fire a second resume. */
    @Test
    fun `an in-flight resolution does not take the composer's send`() {
        val target = pausedState(askUserQuestion("act_1"), resolving = true).duringRunSendTarget
        assertThat(target).isEqualTo(DuringRunSendTarget.QUEUE)
    }

    /** An unresolvable pause is not rendered, so it must not capture the send either. */
    @Test
    fun `a pause with no actionId leaves the composer queueing`() {
        val target = pausedState(askUserQuestion(null)).duringRunSendTarget
        assertThat(target).isEqualTo(DuringRunSendTarget.QUEUE)
    }

    @Test
    fun `with no pause the composer keeps its ordinary during-run behaviour`() {
        assertThat(pausedState(null).duringRunSendTarget).isEqualTo(DuringRunSendTarget.QUEUE)
    }
}
