package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.PendingSteer
import com.garfiec.librechat.core.model.request.SteerCancelRequest
import com.garfiec.librechat.core.model.request.SteerRequest
import com.garfiec.librechat.core.model.response.SteerCancelResponse
import com.garfiec.librechat.core.model.response.SteerResponse
import com.garfiec.librechat.core.model.steer.SteerRejectionCodes
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.garfiec.librechat.feature.chat.viewmodel.SteerChipStatus
import com.garfiec.librechat.feature.chat.viewmodel.SteeringHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteeringDelegateTest {

    private val chatRepository = mockk<ChatRepository>()
    private val enqueued = mutableListOf<QueuedMessage>()
    private val sentNow = mutableListOf<QueuedMessage>()

    private fun spec(text: String) = QueuedMessage(
        localId = "spec-$text",
        text = text,
        endpoint = "agents",
        model = "agent_abc",
        agentId = "agent_abc",
        dispatch = EndpointDispatch(endpointType = "agents", key = null, modelDisplayLabel = null),
    )

    private fun delegateWith(
        scope: TestScope,
        isStreaming: Boolean = true,
    ): Pair<SteeringDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(
            ChatUiState(conversation = ConversationMetaState(conversationId = "conv-1")),
        )
        val root = ChatStateHandle(flow, scope)
        val delegate = SteeringDelegate(
            handle = SteeringHandle(root),
            chatRepository = chatRepository,
            buildFollowUp = { text -> spec(text) },
            enqueueFollowUp = { enqueued += it },
            sendAsNewTurn = { sentNow += it },
            isStreaming = { isStreaming },
        )
        return delegate to flow
    }

    private fun rejection(code: String) = Result.Error(
        ApiException(statusCode = 409, message = "rejected", body = """{"code":"$code"}"""),
    )

    @Test
    fun `an accepted steer swaps its placeholder chip for the server id`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                Result.Success(SteerResponse(status = "queued", steerId = "st-1"))
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))

            val chips = flow.value.pendingSteers
            assertThat(chips).hasSize(1)
            assertThat(chips[0].steerId).isEqualTo("st-1")
            assertThat(chips[0].status).isEqualTo(SteerChipStatus.PENDING)
            assertThat(enqueued).isEmpty()
        }

    @Test
    fun `a paused run queues the text instead of dropping it`() = runTest(UnconfinedTestDispatcher()) {
        // RUN_PAUSED means the run is alive but unreachable, so the words wait for it to finish.
        coEvery { chatRepository.steerChat(any()) } returns rejection(SteerRejectionCodes.RUN_PAUSED)
        val (delegate, flow) = delegateWith(this)

        delegate.steer("conv-1", spec("be brief"))

        assertThat(flow.value.pendingSteers).isEmpty()
        assertThat(enqueued.map { it.text }).containsExactly("be brief")
        assertThat(sentNow).isEmpty()
    }

    @Test
    fun `an unrecognized rejection still keeps the text`() = runTest(UnconfinedTestDispatcher()) {
        // A pre-0.8.8 server 404s the route with no code at all. Nothing about that justifies
        // discarding what the user typed.
        coEvery { chatRepository.steerChat(any()) } returns
            Result.Error(ApiException(statusCode = 404, message = "Not Found", body = "<html/>"))
        val (delegate, _) = delegateWith(this)

        delegate.steer("conv-1", spec("be brief"))

        assertThat(enqueued.map { it.text }).containsExactly("be brief")
    }

    @Test
    fun `NO_ACTIVE_RUN sends as a new turn once the client agrees the run stopped`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                rejection(SteerRejectionCodes.NO_ACTIVE_RUN)
            val (delegate, _) = delegateWith(this, isStreaming = false)

            delegate.steer("conv-1", spec("be brief"))

            assertThat(sentNow.map { it.text }).containsExactly("be brief")
            assertThat(enqueued).isEmpty()
        }

    @Test
    fun `NO_ACTIVE_RUN queues while the client still believes a run is live`() =
        runTest(UnconfinedTestDispatcher()) {
            // The final frame is usually still settling; a direct send there would hit the send
            // path's in-flight guard and be dropped, so the queue's drain has to fire it.
            coEvery { chatRepository.steerChat(any()) } returns
                rejection(SteerRejectionCodes.NO_ACTIVE_RUN)
            val (delegate, _) = delegateWith(this, isStreaming = true)

            delegate.steer("conv-1", spec("be brief"))

            assertThat(enqueued.map { it.text }).containsExactly("be brief")
            assertThat(sentNow).isEmpty()
        }

    @Test
    fun `an applied event that beats the ack leaves no stranded chip`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))
            // The SSE wins the race, naming an id this client has not learned yet.
            delegate.onSteerApplied("st-1")
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(enqueued).isEmpty()
        }

    @Test
    fun `a run that ends while the ack is in flight re-homes the steer`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            var streaming = true
            val flow = MutableStateFlow(
                ChatUiState(conversation = ConversationMetaState(conversationId = "conv-1")),
            )
            val delegate = SteeringDelegate(
                handle = SteeringHandle(ChatStateHandle(flow, this)),
                chatRepository = chatRepository,
                buildFollowUp = { spec(it) },
                enqueueFollowUp = { enqueued += it },
                sendAsNewTurn = { sentNow += it },
                isStreaming = { streaming },
            )

            delegate.steer("conv-1", spec("be brief"))
            // Exactly what endStream does: convert the settled chips, then wipe session state —
            // all of it while this steer's POST is still in flight.
            streaming = false
            delegate.reclaimLocalChips()
            delegate.clear()
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            // No injection is coming and no event will ever retire the chip.
            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(enqueued.map { it.text }).containsExactly("be brief")
        }

    @Test
    fun `a rejection that lands after the session was cleared still re-homes the steer`() =
        runTest(UnconfinedTestDispatcher()) {
            // The Stop case: abort acks, the run tears down, and only then does the steer POST
            // come back 404. clear() must not have taken the spec the callback needs.
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            val (delegate, flow) = delegateWith(this, isStreaming = false)

            delegate.steer("conv-1", spec("be brief"))
            delegate.reclaimLocalChips()
            delegate.clear()
            ack.complete(rejection(SteerRejectionCodes.NO_ACTIVE_RUN))
            runCurrent()

            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(sentNow.map { it.text }).containsExactly("be brief")
            assertThat(enqueued).isEmpty()
        }

    @Test
    fun `a cancel asked for before the ack survives the session being cleared`() =
        runTest(UnconfinedTestDispatcher()) {
            // The withdrawal must still reach the server, and the withdrawn words must NOT come
            // back as a follow-up.
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            coEvery { chatRepository.cancelSteer(any()) } returns
                Result.Success(SteerCancelResponse(removed = true))
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))
            delegate.cancel(flow.value.pendingSteers.single().steerId)
            delegate.clear()
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            assertThat(enqueued).isEmpty()
            assertThat(sentNow).isEmpty()
            coVerify { chatRepository.cancelSteer(SteerCancelRequest("conv-1", "st-1")) }
        }

    @Test
    fun `cancelling before the ack cancels the real steer and never requeues it`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            coEvery { chatRepository.cancelSteer(any()) } returns
                Result.Success(SteerCancelResponse(removed = true))
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))
            val placeholder = flow.value.pendingSteers.single().steerId
            delegate.cancel(placeholder)
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(enqueued).isEmpty()
            coVerify { chatRepository.cancelSteer(SteerCancelRequest("conv-1", "st-1")) }
        }

    @Test
    fun `a sync snapshot replaces the chips but keeps in-flight ones`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("still sending"))
            delegate.onPendingSteersSynced(
                listOf(PendingSteer(steerId = "st-7", text = "from the server", createdAt = 1)),
            )

            val chips = flow.value.pendingSteers
            assertThat(chips.map { it.text }).containsExactly("from the server", "still sending")

            // Settle the in-flight POST so runTest isn't left with an active child job.
            ack.complete(Result.Success(SteerResponse(steerId = "st-8")))
            runCurrent()
        }

    @Test
    fun `reclaimed steers become queued follow-ups in the order they were sent`() =
        runTest(UnconfinedTestDispatcher()) {
            val (delegate, flow) = delegateWith(this)

            delegate.reclaim(
                listOf(
                    PendingSteer(steerId = "st-2", text = "second", createdAt = 2),
                    PendingSteer(steerId = "st-1", text = "first", createdAt = 1),
                ),
            )

            assertThat(enqueued.map { it.text }).containsExactly("first", "second").inOrder()
            assertThat(flow.value.pendingSteers).isEmpty()
        }

    @Test
    fun `a stream that dies with no report converts its accepted chips locally`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                Result.Success(SteerResponse(steerId = "st-1"))
            val (delegate, flow) = delegateWith(this)
            delegate.steer("conv-1", spec("be brief"))

            delegate.reclaimLocalChips()

            assertThat(enqueued.map { it.text }).containsExactly("be brief")
            assertThat(flow.value.pendingSteers).isEmpty()
        }

    @Test
    fun `a local conversion leaves in-flight chips to their own POST callback`() =
        runTest(UnconfinedTestDispatcher()) {
            // Taking a SENDING chip here as well would send the same message twice: its own
            // rejection path already re-homes the text.
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            val (delegate, flow) = delegateWith(this)
            delegate.steer("conv-1", spec("in flight"))

            delegate.reclaimLocalChips()

            assertThat(enqueued).isEmpty()
            assertThat(flow.value.pendingSteers).hasSize(1)

            ack.complete(Result.Success(SteerResponse(steerId = "st-9")))
            runCurrent()
        }

    @Test
    fun `a steer posts only its text and conversation`() = runTest(UnconfinedTestDispatcher()) {
        // The server injects into the ORIGINATING run and re-derives its identity from job
        // metadata, so an agent selection sent here would be ignored at best.
        coEvery { chatRepository.steerChat(any()) } returns Result.Success(SteerResponse(steerId = "s"))
        val (delegate, _) = delegateWith(this)

        delegate.steer("conv-1", spec("  be brief  "))

        coVerify { chatRepository.steerChat(SteerRequest("conv-1", "be brief")) }
    }
}
