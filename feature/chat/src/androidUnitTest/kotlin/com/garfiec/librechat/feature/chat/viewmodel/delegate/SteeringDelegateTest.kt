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

    /**
     * Both enqueue callbacks land here. These fakes cover the delegate's DECISIONS — which steer
     * is re-homed, once, and which is suppressed — and deliberately not the queue's drain policy:
     * the real `enqueueFollowUp` self-drains and the real `pauseQueue` ignores an empty queue,
     * and modelling either here would turn every assertion below into a queue test.
     *
     * The consequence is that the ordering between them is NOT observable at this level — a
     * parked steer auto-sending on conversation open passed right through here. That guarantee
     * belongs to `ChatViewModelDuringRunSendTest`, which runs the real queue.
     */
    private val enqueued = mutableListOf<QueuedMessage>()
    private var queuePaused = false

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
            enqueueParked = { enqueued += it },
            pauseQueue = { queuePaused = true },
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
    fun `NO_ACTIVE_RUN queues the text once the client agrees the run stopped`() =
        runTest(UnconfinedTestDispatcher()) {
            // Queued rather than sent directly: the queue drains itself the moment the run is
            // over, and unlike the live-send gate it cannot REFUSE the message (no model
            // selected, readiness timeout) and leave the user with nothing.
            coEvery { chatRepository.steerChat(any()) } returns
                rejection(SteerRejectionCodes.NO_ACTIVE_RUN)
            val (delegate, _) = delegateWith(this, isStreaming = false)

            delegate.steer("conv-1", spec("be brief"))

            assertThat(enqueued.map { it.text }).containsExactly("be brief")
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
                enqueueParked = { enqueued += it },
                pauseQueue = { queuePaused = true },
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
            assertThat(enqueued.map { it.text }).containsExactly("be brief")
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
    fun `a steer reported by both the abort ack and the aborted final is queued once`() =
        runTest(UnconfinedTestDispatcher()) {
            // The server builds ONE list of un-injected steers per abort and puts it on both the
            // ack and the aborted `final` frame — and a Stop leaves the collector running, so
            // both land. Re-homing the same steer twice sends the user's words twice.
            val (delegate, _) = delegateWith(this)
            val report = listOf(PendingSteer(steerId = "st-1", text = "be brief", createdAt = 1))

            delegate.reclaim(report)
            delegate.reclaim(report)
            // The same steer parked for a later /chat/status read is the third copy.
            delegate.reclaim(report)

            assertThat(enqueued.map { it.text }).containsExactly("be brief")
        }

    @Test
    fun `a locally converted chip is not re-queued by a later server report`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                Result.Success(SteerResponse(steerId = "st-1"))
            val (delegate, _) = delegateWith(this)
            delegate.steer("conv-1", spec("be brief"))
            delegate.reclaimLocalChips()

            delegate.reclaim(listOf(PendingSteer(steerId = "st-1", text = "be brief", createdAt = 1)))

            assertThat(enqueued.map { it.text }).containsExactly("be brief")
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

    // ── Session boundaries ────────────────────────────────────────────────
    // clear() runs on EVERY stream session boundary, including the one a mid-run reconnect
    // opens (resumeStream → startStreamSession). These cover what it must not throw away.

    /**
     * A reconnect must not cost the steer the config it was composed with. clear() clears the
     * rendered chips; the record behind each one — and its spec — has to survive, so the sync
     * frame's re-seed rejoins it rather than producing a spec-less chip that later gets rebuilt
     * from whatever the composer holds by then.
     */
    @Test
    fun `a steer re-seeded after a reconnect keeps the spec it was composed with`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                Result.Success(SteerResponse(steerId = "st-1"))
            val flow = MutableStateFlow(
                ChatUiState(conversation = ConversationMetaState(conversationId = "conv-1")),
            )
            val delegate = SteeringDelegate(
                handle = SteeringHandle(ChatStateHandle(flow, this)),
                chatRepository = chatRepository,
                // A rebuilt spec is distinguishable from the one minted at send time.
                buildFollowUp = { text -> spec(text).copy(model = "model-at-failure-time") },
                enqueueFollowUp = { enqueued += it },
                enqueueParked = { enqueued += it },
                pauseQueue = { queuePaused = true },
                isStreaming = { true },
            )

            delegate.steer("conv-1", spec("be brief").copy(model = "model-at-send-time"))
            // The app is backgrounded and foregrounded: resumeStream opens a new session…
            delegate.clear()
            assertThat(flow.value.pendingSteers).isEmpty()
            // …and the sync frame replays the server's still-queued steers.
            delegate.onPendingSteersSynced(
                listOf(PendingSteer(steerId = "st-1", text = "be brief", createdAt = 1L)),
            )
            assertThat(flow.value.pendingSteers).hasSize(1)
            // The run then dies without injecting it.
            delegate.reclaimLocalChips()

            assertThat(enqueued.single().model).isEqualTo("model-at-send-time")
        }

    /**
     * A cancel is optimistic and the server's next sync can still list the steer. Re-seeding it
     * would let the run's end re-home text the user explicitly withdrew.
     */
    @Test
    fun `a cancelled steer is not resurrected by a stale sync frame`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                Result.Success(SteerResponse(steerId = "st-1"))
            coEvery { chatRepository.cancelSteer(any()) } returns
                Result.Success(SteerCancelResponse(removed = true))
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))
            delegate.cancel("st-1")
            assertThat(flow.value.pendingSteers).isEmpty()

            // The server had not processed the cancel when it built this frame.
            delegate.onPendingSteersSynced(
                listOf(PendingSteer(steerId = "st-1", text = "be brief", createdAt = 1L)),
            )
            assertThat(flow.value.pendingSteers).isEmpty()

            // A stream error ends the run with no server report.
            delegate.reclaimLocalChips()
            delegate.clear()

            assertThat(enqueued).isEmpty()
        }

    /**
     * `on_steer_applied` regularly beats the steer's own 202. If the run then ends before the ack
     * lands, the record that says "already injected" is the only thing stopping the late ack from
     * re-homing text that is already in the reply.
     */
    @Test
    fun `a late ack for an already-applied steer does not re-send it`() =
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
                enqueueParked = { enqueued += it },
                pauseQueue = { queuePaused = true },
                isStreaming = { streaming },
            )

            delegate.steer("conv-1", spec("be brief"))
            // The SSE event wins the race, naming an id this client has not learned yet.
            delegate.onSteerApplied("st-1")
            // The run finishes and the session is torn down…
            streaming = false
            delegate.clear()
            // …and only now does the 202 arrive.
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            assertThat(enqueued).isEmpty()
            assertThat(flow.value.pendingSteers).isEmpty()
        }

    /**
     * Cancelling an in-flight steer must record the withdrawal even when there is no conversation
     * id to post the cancel against — otherwise the ack treats it as a live steer and re-mints
     * the chip the user just dismissed.
     */
    @Test
    fun `cancelling with no conversation id still suppresses the ack`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            coEvery { chatRepository.cancelSteer(any()) } returns
                Result.Success(SteerCancelResponse(removed = true))
            // The conversation was switched out from under the in-flight steer.
            val flow = MutableStateFlow(ChatUiState())
            val delegate = SteeringDelegate(
                handle = SteeringHandle(ChatStateHandle(flow, this)),
                chatRepository = chatRepository,
                buildFollowUp = { spec(it) },
                enqueueFollowUp = { enqueued += it },
                enqueueParked = { enqueued += it },
                pauseQueue = { queuePaused = true },
                isStreaming = { true },
            )

            delegate.steer("conv-1", spec("be brief"))
            delegate.cancel(flow.value.pendingSteers.single().steerId)
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(enqueued).isEmpty()
        }

    /**
     * Two steers whose acks settle out of order, with a session boundary between them: neither
     * may be lost, and neither may be re-homed twice.
     */
    @Test
    fun `interleaved acks across a session boundary lose nothing and duplicate nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = CompletableDeferred<Result<SteerResponse>>()
            val second = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(SteerRequest("conv-1", "one")) } coAnswers { first.await() }
            coEvery { chatRepository.steerChat(SteerRequest("conv-1", "two")) } coAnswers { second.await() }
            var streaming = true
            val flow = MutableStateFlow(
                ChatUiState(conversation = ConversationMetaState(conversationId = "conv-1")),
            )
            val delegate = SteeringDelegate(
                handle = SteeringHandle(ChatStateHandle(flow, this)),
                chatRepository = chatRepository,
                buildFollowUp = { spec(it) },
                enqueueFollowUp = { enqueued += it },
                enqueueParked = { enqueued += it },
                pauseQueue = { queuePaused = true },
                isStreaming = { streaming },
            )

            delegate.steer("conv-1", spec("one"))
            delegate.steer("conv-1", spec("two"))
            // The second ack lands first, then the run ends, then the first ack arrives.
            second.complete(Result.Success(SteerResponse(steerId = "st-2")))
            runCurrent()
            streaming = false
            delegate.reclaimLocalChips()
            delegate.clear()
            first.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            assertThat(enqueued.map { it.text }).containsExactly("one", "two")
            assertThat(flow.value.pendingSteers).isEmpty()
        }

    // ── Turn identity ─────────────────────────────────────────────────────
    // A steer carries no run id and `isStreaming` is global, so a slow ack from a finished turn
    // is otherwise indistinguishable from one belonging to the turn now streaming.

    /**
     * A queued follow-up draining into a NEW run makes `isStreaming` true again. Without a turn
     * stamp the old turn's late 202 attaches its chip to that new run, where no injection and no
     * report will ever retire it.
     */
    @Test
    fun `a late ack from a finished turn does not attach to the next one`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            val (delegate, flow) = delegateWith(this, isStreaming = true)

            delegate.steer("conv-1", spec("be brief"))
            // Run A ends and run B starts (a drained follow-up); isStreaming is true again.
            delegate.onTurnBoundary()
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            // Re-homed, not rendered against run B.
            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(enqueued.map { it.text }).containsExactly("be brief")
        }

    /**
     * A turn boundary SETTLES the previous turn's accepted steers rather than re-homing them:
     * on a clean Finalized the frame's list is authoritative, so an unreported chip was injected
     * and converting it would double-send.
     */
    @Test
    fun `a turn boundary settles leftover chips without re-sending them`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { chatRepository.steerChat(any()) } returns
                Result.Success(SteerResponse(steerId = "st-1"))
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))
            assertThat(flow.value.pendingSteers).hasSize(1)

            delegate.onTurnBoundary()

            assertThat(flow.value.pendingSteers).isEmpty()
            assertThat(enqueued).isEmpty()
        }

    /**
     * Steers the server PARKED for a run that ended unattended are handed over on conversation
     * OPEN, into a fresh ViewModel whose queue is empty and unpaused. Auto-draining there would
     * send, unprompted, a message the user last saw parked behind a Stop.
     */
    @Test
    fun `parked steers are held for the user instead of firing on open`() =
        runTest(UnconfinedTestDispatcher()) {
            val (delegate, _) = delegateWith(this, isStreaming = false)

            delegate.reclaimParked(
                listOf(PendingSteer(steerId = "st-1", text = "use metric units", createdAt = 1L)),
            )

            assertThat(enqueued.map { it.text }).containsExactly("use metric units")
            assertThat(queuePaused).isTrue()
        }

    /** Nothing to claim must not pause a queue the user is actively draining. */
    @Test
    fun `an empty parked claim does not pause the queue`() = runTest(UnconfinedTestDispatcher()) {
        val (delegate, _) = delegateWith(this, isStreaming = false)

        delegate.reclaimParked(emptyList())

        assertThat(enqueued).isEmpty()
        assertThat(queuePaused).isFalse()
    }

    /**
     * Settled steers are retained to suppress late acks and repeat reports, so the retention has
     * to be bounded — but bounding it must never evict a steer that is still owed an ack.
     */
    @Test
    fun `tombstone retention never evicts a steer whose POST is still in flight`() =
        runTest(UnconfinedTestDispatcher()) {
            val ack = CompletableDeferred<Result<SteerResponse>>()
            coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() }
            val (delegate, flow) = delegateWith(this)

            delegate.steer("conv-1", spec("be brief"))
            // Far more settled steers than the retention bound.
            repeat(80) { delegate.onSteerApplied("applied-$it") }

            assertThat(flow.value.pendingSteers).hasSize(1)
            ack.complete(Result.Success(SteerResponse(steerId = "st-1")))
            runCurrent()

            // The in-flight record survived, so its ack settled onto the server id rather than
            // being treated as an unknown steer.
            assertThat(flow.value.pendingSteers.single().steerId).isEqualTo("st-1")
            assertThat(flow.value.pendingSteers.single().status).isEqualTo(SteerChipStatus.PENDING)
            assertThat(enqueued).isEmpty()
        }
}
