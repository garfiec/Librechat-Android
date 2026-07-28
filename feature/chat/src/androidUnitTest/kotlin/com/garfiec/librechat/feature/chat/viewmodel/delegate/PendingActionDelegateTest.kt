package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ResumePinStore
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingActionPayload
import com.garfiec.librechat.core.model.PendingActionTypes
import com.garfiec.librechat.core.model.ToolApprovalDecisions
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.request.ToolApprovalResolution
import com.garfiec.librechat.core.model.response.ChatResumeResponse
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.viewmodel.ChatRequestBuilder
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.ModelSelectionState
import com.garfiec.librechat.feature.chat.viewmodel.PendingActionHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingActionDelegateTest {

    private val chatRepository = mockk<ChatRepository>()

    private fun pausedState() = ChatUiState(
        conversation = ConversationMetaState(conversationId = "conv-1"),
        selection = ModelSelectionState(selectedEndpoint = "agents", selectedModel = "agent_abc"),
    )

    private val pinStore = ResumePinStore()

    private fun queuedSpec(endpoint: String, model: String?, agentId: String?) = QueuedMessage(
        localId = "q-1",
        text = "hi",
        endpoint = endpoint,
        model = model,
        agentId = agentId,
        dispatch = EndpointDispatch(endpointType = endpoint, key = null, modelDisplayLabel = null),
    )

    private fun delegateWith(
        scope: TestScope,
        state: ChatUiState = pausedState(),
        store: ResumePinStore = pinStore,
    ): Pair<PendingActionDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val root = ChatStateHandle(flow, scope)
        val delegate = PendingActionDelegate(
            handle = PendingActionHandle(root),
            chatRepository = chatRepository,
            requestBuilder = ChatRequestBuilder { flow.value },
            resumeFailureMessage = { it ?: "failed" },
            fingerprintRejectedMessage = { FINGERPRINT_REJECTED },
            restoreAnswer = { restored += it },
            resumePinStore = store,
        )
        return delegate to flow
    }

    /** Answers handed back to the composer because the resume did not land. */
    private val restored = mutableListOf<String>()

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

    @Test
    fun `resume replays the promptPrefix the send spread at the top level of its body`() =
        runTest(UnconfinedTestDispatcher()) {
            // Custom Instructions ride in the model-param payload, which the send spreads at the
            // TOP LEVEL of the chat body — and that raw field is one of the seven the server
            // fingerprints. Omitting it on the resume hashes null against the sent string: 403.
            val (delegate, _) = delegateWith(this)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns Result.Success(ChatResumeResponse())

            delegate.onTurnStarted(
                QueuedMessage(
                    localId = "q-1",
                    text = "hi",
                    endpoint = "openAI",
                    model = "gpt-4o",
                    agentId = null,
                    modelParamsPayload = buildJsonObject {
                        put("promptPrefix", "be terse")
                        put("temperature", 0.4)
                    },
                    dispatch = EndpointDispatch(endpointType = null, key = null, modelDisplayLabel = null),
                ),
            )
            delegate.onPendingAction(toolApproval())
            delegate.submitAnswer("yes")

            assertThat(request.captured.promptPrefix).isEqualTo("be terse")
        }

    @Test
    fun `a resume with no pinned spec reads promptPrefix from the same builder the send used`() =
        runTest(UnconfinedTestDispatcher()) {
            // Edit / regenerate / continue pin nothing, so the pause falls back to the live config.
            // It must read the model params through the SAME builder the send path serialized, or
            // the two bodies disagree on a field the fingerprint covers.
            val state = ChatUiState(
                conversation = ConversationMetaState(conversationId = "conv-1"),
                selection = ModelSelectionState(
                    selectedEndpoint = "openAI",
                    selectedModel = "gpt-4o",
                    modelParameters = ModelParameters.DEFAULT.copy(customInstructions = "answer in French"),
                ),
            )
            val (delegate, flow) = delegateWith(this, state)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns Result.Success(ChatResumeResponse())

            delegate.onPendingAction(toolApproval())
            delegate.submitAnswer("yes")

            val sentParams = ChatRequestBuilder { flow.value }.buildModelParams()
            assertThat(request.captured.promptPrefix)
                .isEqualTo((sentParams?.get("promptPrefix") as JsonPrimitive).content)
            assertThat(request.captured.promptPrefix).isEqualTo("answer in French")
        }

    @Test
    fun `an untouched parameter sheet resumes with no promptPrefix`() =
        runTest(UnconfinedTestDispatcher()) {
            // The send omits the key entirely, so the resume must too: the server hashes an absent
            // field as null, and echoing "" would be a mismatch.
            val (delegate, _) = delegateWith(this)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns Result.Success(ChatResumeResponse())

            delegate.onPendingAction(toolApproval())
            delegate.submitAnswer("yes")

            assertThat(request.captured.promptPrefix).isNull()
        }

    // ── Cross-ViewModel resume (a handed-off new chat, or reopening a conversation) ─────────

    /**
     * The ViewModel that resolves a pause is often not the one that started the run: a chat
     * started from the landing page hands off to a fresh `Chat(id)` ViewModel, and the first tool
     * approval of a brand-new chat is the most common way to meet a pause at all. The conversation
     * record cannot rebuild the config — it has no enabled tools, MCP servers or custom
     * instructions — so a re-derived guess 403s on the fingerprint.
     */
    @Test
    fun `a fresh view model resumes against the pin the run was started with`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.resumeChat(any()) } returns Result.Success(ChatResumeResponse())
            // The ViewModel that started the run publishes its pin once the id is minted.
            val (starter, _) = delegateWith(this)
            starter.onTurnStarted(
                queuedSpec(endpoint = "openAI", model = "gpt-4o", agentId = null),
            )
            starter.onConversationIdResolved("conv-1")

            // A different ViewModel, with a DIFFERENT live selection, resolves the pause.
            val (resumer, _) = delegateWith(
                this,
                state = ChatUiState(
                    conversation = ConversationMetaState(conversationId = "conv-1"),
                    selection = ModelSelectionState(selectedEndpoint = "agents", selectedModel = null),
                ),
            )
            resumer.onPendingAction(toolApproval())
            resumer.submitAnswer("yes")

            val sent = slot<ChatResumeRequest>()
            coVerify { chatRepository.resumeChat(capture(sent)) }
            assertThat(sent.captured.endpoint).isEqualTo("openAI")
            assertThat(sent.captured.model).isEqualTo("gpt-4o")
        }

    /**
     * The same pause is re-announced constantly — the ~120s SSE stall reconnects and replays it,
     * and a foreground resume re-reads it off /chat/status. Re-arming the controls under a submit
     * in flight makes the user submit again and collect a 409 for a decision that succeeded.
     */
    @Test
    fun `a re-announcement during an in-flight submit does not re-arm the controls`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Result<ChatResumeResponse>>()
            coEvery { chatRepository.resumeChat(any()) } coAnswers { gate.await() }
            val (delegate, flow) = delegateWith(this)
            delegate.onPendingAction(toolApproval())

            delegate.submitAnswer("yes")
            assertThat(flow.value.isResolvingPendingAction).isTrue()

            // The stall/reconnect replays the identical pause.
            delegate.onPendingAction(toolApproval())

            assertThat(flow.value.isResolvingPendingAction).isTrue()
            gate.complete(Result.Success(ChatResumeResponse()))
            runCurrent()
        }

    /** A DIFFERENT pause is real news and must re-arm, even if a stale submit is outstanding. */
    @Test
    fun `a different pause re-arms the controls`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Result<ChatResumeResponse>>()
        coEvery { chatRepository.resumeChat(any()) } coAnswers { gate.await() }
        val (delegate, flow) = delegateWith(this)
        delegate.onPendingAction(toolApproval("act-1"))
        delegate.submitAnswer("yes")

        delegate.onPendingAction(toolApproval("act-2"))

        assertThat(flow.value.isResolvingPendingAction).isFalse()
        gate.complete(Result.Success(ChatResumeResponse()))
        runCurrent()
    }

    /**
     * The resume POST outlives the run. Returning after the pause was cleared — and a NEWER run
     * announced its own — it must not wipe that one and strand it with no controls.
     */
    @Test
    fun `a resume answering after its pause was cleared leaves a newer pause alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Result<ChatResumeResponse>>()
            coEvery { chatRepository.resumeChat(any()) } coAnswers { gate.await() }
            val (delegate, flow) = delegateWith(this)
            delegate.onPendingAction(toolApproval("act-1"))
            delegate.submitAnswer("yes")

            // Run A ends; run B starts and pauses on its own action.
            delegate.clear()
            val newer = toolApproval("act-2")
            delegate.onPendingAction(newer)

            gate.complete(Result.Success(ChatResumeResponse()))
            runCurrent()

            assertThat(flow.value.pendingAction).isEqualTo(newer)
        }

    /**
     * An `ask_user_question` answer is the user's own prose, and the composer is emptied to send
     * it — so a rejection with no restore loses words the user cannot get back. Same invariant the
     * steering paths enforce; the pause path did not implement it.
     */
    @Test
    fun `a rejected answer goes back to the composer`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { chatRepository.resumeChat(any()) } returns Result.Error(message = "boom")
        val (delegate, flow) = delegateWith(this)

        delegate.onPendingAction(askQuestion())
        delegate.submitAnswer("the blue one")

        assertThat(restored).containsExactly("the blue one")
        // The card stays up: the failure may be transient and it is the only route to a resume.
        assertThat(flow.value.pendingAction).isNotNull()
        assertThat(flow.value.isResolvingPendingAction).isFalse()
    }

    /** A 403 is the fingerprint mismatch, which retrying cannot fix — say so rather than relaying
     *  the server's "Forbidden". */
    @Test
    fun `a fingerprint rejection is reported as its own failure`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { chatRepository.resumeChat(any()) } returns
            Result.Error(ApiException(statusCode = 403, message = "Forbidden"))
        val (delegate, flow) = delegateWith(this)

        delegate.onPendingAction(askQuestion())
        delegate.submitAnswer("the blue one")

        assertThat(flow.value.error).isEqualTo(FINGERPRINT_REJECTED)
        assertThat(restored).containsExactly("the blue one")
    }

    /**
     * The stream can end while the answer is still in flight — `endStream` clears the pause, and
     * the continuation is then forbidden from touching the shared fields. The words are not the
     * pause, though, and this is the path that silently ate them.
     */
    @Test
    fun `an answer whose pause is cleared mid-flight is not lost`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Result<ChatResumeResponse>>()
        coEvery { chatRepository.resumeChat(any()) } coAnswers { gate.await() }
        val (delegate, _) = delegateWith(this)

        delegate.onPendingAction(askQuestion())
        delegate.submitAnswer("the blue one")
        delegate.clear() // the run ended under the POST

        gate.complete(Result.Error(message = "gone"))
        runCurrent()

        assertThat(restored).containsExactly("the blue one")
    }

    /** ...but a POST that actually landed must NOT come back: the run already has the answer, and
     *  restoring would invite the user to send it a second time. */
    @Test
    fun `an answer accepted after its pause was cleared is not restored`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Result<ChatResumeResponse>>()
            coEvery { chatRepository.resumeChat(any()) } coAnswers { gate.await() }
            val (delegate, _) = delegateWith(this)

            delegate.onPendingAction(askQuestion())
            delegate.submitAnswer("the blue one")
            delegate.clear()

            gate.complete(Result.Success(ChatResumeResponse()))
            runCurrent()

            assertThat(restored).isEmpty()
        }

    /** Tool decisions carry no prose, so there is nothing to hand back. */
    @Test
    fun `a rejected tool decision restores nothing`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { chatRepository.resumeChat(any()) } returns Result.Error(message = "boom")
        val (delegate, _) = delegateWith(this)

        delegate.onPendingAction(toolApproval())
        delegate.submitToolDecisions(
            listOf(ToolApprovalResolution(toolCallId = "call-1", decision = ToolApprovalDecisions.APPROVE)),
        )

        assertThat(restored).isEmpty()
    }

    private fun askQuestion(actionId: String = "act-1") = PendingAction(
        actionId = actionId,
        conversationId = "conv-1",
        payload = PendingActionPayload(type = PendingActionTypes.ASK_USER_QUESTION),
    )

    private companion object {
        const val FINGERPRINT_REJECTED = "started with a different setup"
    }
}
