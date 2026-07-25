package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingActionPayload
import com.garfiec.librechat.core.model.PendingActionTypes
import com.garfiec.librechat.core.model.ToolApprovalDecisions
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.request.ToolApprovalResolution
import com.garfiec.librechat.core.model.response.ChatResumeResponse
import com.garfiec.librechat.feature.chat.viewmodel.ChatRequestBuilder
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.ModelSelectionState
import com.garfiec.librechat.feature.chat.viewmodel.PendingActionHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingActionDelegateTest {

    private val chatRepository = mockk<ChatRepository>()

    private fun pausedState() = ChatUiState(
        conversation = ConversationMetaState(conversationId = "conv-1"),
        selection = ModelSelectionState(selectedEndpoint = "agents", selectedModel = "agent_abc"),
    )

    private fun delegateWith(
        scope: TestScope,
        state: ChatUiState = pausedState(),
    ): Pair<PendingActionDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val root = ChatStateHandle(flow, scope)
        val delegate = PendingActionDelegate(
            handle = PendingActionHandle(root),
            chatRepository = chatRepository,
            requestBuilder = ChatRequestBuilder { flow.value },
            resumeFailureMessage = { it ?: "failed" },
        )
        return delegate to flow
    }

    private fun toolApproval(actionId: String = "act-1") = PendingAction(
        actionId = actionId,
        conversationId = "conv-1",
        payload = PendingActionPayload(type = PendingActionTypes.TOOL_APPROVAL),
    )

    @Test
    fun `resume replays the paused turn's agent selection so the fingerprint matches`() =
        runTest(UnconfinedTestDispatcher()) {
            val (delegate, _) = delegateWith(this)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns
                Result.Success(ChatResumeResponse(status = "resuming"))

            delegate.onPendingAction(toolApproval())
            delegate.submitToolDecisions(
                listOf(ToolApprovalResolution(toolCallId = "call-1", decision = ToolApprovalDecisions.APPROVE)),
            )

            assertThat(request.captured.conversationId).isEqualTo("conv-1")
            assertThat(request.captured.actionId).isEqualTo("act-1")
            assertThat(request.captured.endpoint).isEqualTo("agents")
            // On the agents endpoint the selection IS the agent, and the server's fingerprint
            // covers both fields — sending only one would 403.
            assertThat(request.captured.agentId).isEqualTo("agent_abc")
            assertThat(request.captured.model).isEqualTo("agent_abc")
            assertThat(request.captured.decisions).hasSize(1)
        }

    @Test
    fun `an accepted decision clears the pause`() = runTest(UnconfinedTestDispatcher()) {
        val (delegate, flow) = delegateWith(this)
        coEvery { chatRepository.resumeChat(any()) } returns Result.Success(ChatResumeResponse())

        delegate.onPendingAction(toolApproval())
        assertThat(flow.value.pendingAction).isNotNull()

        delegate.submitAnswer("yes")

        assertThat(flow.value.pendingAction).isNull()
        assertThat(flow.value.isResolvingPendingAction).isFalse()
    }

    @Test
    fun `a rejected decision keeps the pause so the user can retry`() = runTest(UnconfinedTestDispatcher()) {
        // The card is the only route back to a resume: clearing it on failure would strand the run
        // with no controls at all.
        val (delegate, flow) = delegateWith(this)
        coEvery { chatRepository.resumeChat(any()) } returns
            Result.Error(RuntimeException("stale"), "This decision targets a stale action")

        delegate.onPendingAction(toolApproval())
        delegate.submitAnswer("yes")

        assertThat(flow.value.pendingAction).isNotNull()
        assertThat(flow.value.isResolvingPendingAction).isFalse()
        assertThat(flow.value.error).isEqualTo("This decision targets a stale action")
    }

    @Test
    fun `clear drops the pause without posting anything`() = runTest(UnconfinedTestDispatcher()) {
        val (delegate, flow) = delegateWith(this)

        delegate.onPendingAction(toolApproval())
        delegate.clear()

        assertThat(flow.value.pendingAction).isNull()
        // No resumeChat stub is configured: a call would fail the test, which is the point —
        // stream teardown must never resolve a pause on the user's behalf.
    }

    @Test
    fun `resume pins the config captured when the pause arrived, not the live selection`() =
        runTest(UnconfinedTestDispatcher()) {
            val (delegate, flow) = delegateWith(this)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns Result.Success(ChatResumeResponse())

            delegate.onPendingAction(toolApproval())
            // The picker stays live while the card is up; switching agents mid-pause must not
            // change what we resume as, or the server 403s the fingerprint mismatch.
            flow.value = flow.value.copy(
                selection = flow.value.selection.copy(selectedModel = "agent_other"),
            )
            delegate.submitAnswer("yes")

            assertThat(request.captured.agentId).isEqualTo("agent_abc")
        }

    @Test
    fun `resume replays the spec the run was started with, not the selection at pause time`() =
        runTest(UnconfinedTestDispatcher()) {
            // A queued follow-up drains with the spec it was composed with, so the live selection
            // can already differ when the run starts — and a tool toggle between send and the
            // pause frame moves it again. The server fingerprints what was SENT.
            val (delegate, flow) = delegateWith(this)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns Result.Success(ChatResumeResponse())

            delegate.onTurnStarted(
                QueuedMessage(
                    localId = "q-1",
                    text = "hi",
                    endpoint = "agents",
                    model = "agent_queued",
                    agentId = "agent_queued",
                    dispatch = EndpointDispatch(endpointType = "agents", key = null, modelDisplayLabel = null),
                ),
            )
            flow.value = flow.value.copy(
                selection = flow.value.selection.copy(selectedModel = "agent_other"),
            )
            delegate.onPendingAction(toolApproval())
            delegate.submitAnswer("yes")

            assertThat(request.captured.agentId).isEqualTo("agent_queued")
            assertThat(request.captured.model).isEqualTo("agent_queued")
        }
}
