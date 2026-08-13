package com.garfiec.librechat.feature.chat.viewmodel

import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.BackendBuildClass
import com.garfiec.librechat.core.common.DetectedBackend
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.datastore.UploadRoutingMode
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.PendingSteer
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.SteerRequest
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import com.garfiec.librechat.core.model.response.SteerResponse
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PickedFile
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformFileHandler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for the during-run send preference actually reaching the send decision.
 *
 * The defect this locks down: `ChatPrefsState` is merged into the state by the `uiState` combine,
 * i.e. onto the EXPOSED flow only, while [ChatViewModel.sendDuringRun] decides from the private
 * backing state — where the slice still held `ChatPrefsState()`'s `QUEUE` default. Every during-run
 * send queued, and steering was unreachable from the composer no matter what the user chose.
 *
 * It failed *invisibly*, which is why nothing caught it: the send button takes its icon from the
 * exposed state, so it rendered itself "Steer this reply" and then queued. Asserting on
 * `uiState.value` cannot reproduce that — the combine populates the copy a test would read. Only
 * driving the real send path does, so this test builds the whole ViewModel and calls
 * [ChatViewModel.sendDuringRun], the exact entry point the composer's send button routes to.
 *
 * ### Why the stream is opened via resume rather than a send
 * `steerMessage` requires `isStreaming`. Resuming (`/chat/status` reports active) reaches that in
 * one hop, without the send-readiness gates, optimistic-message plumbing and upload wait a real
 * `sendMessage` drags in — none of which this behaviour depends on.
 *
 * ### Why every case runs through [duringRunTest]
 * A live stream arms `StreamingManagerDelegate`'s UI flush, a `while (isActive) { delay(50) }` loop
 * that re-posts itself forever. `runTest` ends a test body by advancing the scheduler to idle, and
 * with that loop parked on the scheduler "idle" is unreachable: the run does not fail, it spins
 * forever, and `runTest`'s own timeout cannot preempt it because the timeout never gets scheduled.
 * An `@After` hook is too late — the drain happens first. So the helper cancels the ViewModel scope
 * inside the body, in a `finally` so a failed assertion still reports instead of hanging, and the
 * tests drive with [runCurrent] (what is due *now*, leaving the `delay(50)` parked) rather than
 * `advanceUntilIdle()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDuringRunSendTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fixture = ChatViewModelTestFixture()
    private val agentRepository get() = fixture.agentRepository
    private val chatRepository get() = fixture.chatRepository
    private val messageRepository get() = fixture.messageRepository
    private val configRepository get() = fixture.configRepository
    private val conversationRepository get() = fixture.conversationRepository
    private val favoritesRepository get() = fixture.favoritesRepository
    private val keyRepository get() = fixture.keyRepository
    private val roleRepository get() = fixture.roleRepository
    private val serverDataStore get() = fixture.serverDataStore
    private val settingsDataStore get() = fixture.settingsDataStore
    private val platformDelegateFactory get() = fixture.platformDelegateFactory
    private val serverFileSelectionHandoff get() = fixture.serverFileSelectionHandoff
    private val selectionHandoff get() = fixture.selectionHandoff
    private val fileHandler = mockk<PlatformFileHandler>(relaxed = true)

    /** The resumed run's SSE events. Hot, so a test can end the run by emitting an error. */
    private val resumedStream = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fixture.stubDefaults()

        // Open the steering gate: 0.8.8-rc1 clears the version comparison outright, so the test
        // does not depend on the dev-build date gate or on the generated commit map.
        every { configRepository.detectedBackend } returns
            MutableStateFlow(DetectedBackend("0.8.8-rc1", BackendBuildClass.RC))
        every { configRepository.detectedBackendVersion } returns MutableStateFlow("0.8.8-rc1")

        // Collection-typed flows read by init-time delegates: relaxed mockk hands back a bare
        // Object for the erased element type, which those delegates cast to Map/List.
        // A real endpoint + model, because a queue drain goes through `runWhenSendReady`, which
        // REFUSES (and re-queues) when nothing is selected — so without these the second turn in
        // the turn-identity test below would never start.
        every { configRepository.endpointConfigs } returns MutableStateFlow(mapOf(ENDPOINT to EndpointConfig()))
        every { configRepository.availableModels } returns MutableStateFlow(mapOf(ENDPOINT to listOf(MODEL)))
        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())

        // Every source of the `uiState` combine. A relaxed mock hands back a flow that never
        // emits, and one silent source stalls the whole combine — `uiState.value` would then sit
        // on `stateIn`'s `ChatUiState()` placeholder forever and every assertion below would read
        // defaults rather than this ViewModel's state.
        every { serverDataStore.currentUrlFlow } returns flowOf("https://example.test")
        every { settingsDataStore.chatFontSize } returns flowOf(ChatFontSize.MEDIUM)
        every { settingsDataStore.starredModelsDisplay } returns flowOf(StarredModelsDisplay.OFF)
        every { settingsDataStore.chatHeaderContent } returns flowOf(ChatHeaderContent.TITLE)
        every { settingsDataStore.chatHeaderAlignment } returns flowOf(ChatHeaderAlignment.LEFT)
        every { settingsDataStore.contextBarPlacement } returns flowOf(ContextBarPlacement.OPTIONS_SHEET)
        every { settingsDataStore.contextGaugeExpanded } returns flowOf(false)

        // buildSendSpec reads the attachment list; the relaxed factory would hand back an
        // untyped stub that fails the List cast.
        every { platformDelegateFactory.createFileHandler(any()) } returns fileHandler
        every { fileHandler.attachedFiles } returns MutableStateFlow(emptyList())

        coEvery { conversationRepository.getConversation(any(), any()) } returns Result.Error(message = "test")
        coEvery { chatRepository.steerChat(any()) } returns
            Result.Success(SteerResponse(status = "queued", steerId = "steer-1"))

        // The conversation opens onto a live run: `resumeActiveStreamIfNeeded` sees active=true and
        // flips `isStreaming` on. `resumedStream` is a hot flow the test drives: left alone the run
        // stays open for the whole test, and emitting an error ends it.
        coEvery { chatRepository.checkStreamStatus(eq(CONVERSATION_ID), any()) } returns
            ChatStatusResponse(active = true)
        every { chatRepository.resumeStream(any()) } returns resumedStream
        // The next turn's stream (a queue drain sends through `startChat`). Never emits, so the
        // second run just stays open.
        every {
            chatRepository.startChat(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns MutableSharedFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send during a run steers when the preference is steer`() =
        duringRunTest(DuringRunAction.STEER) { vm ->
            // The preference has to reach `_uiState`, not just the exposed copy: `sendDuringRun`
            // decides from the former while the button takes its face from the latter.
            assertThat(vm.uiState.value.isStreaming).isTrue()

            vm.onInputChanged(TEXT)
            vm.sendDuringRun()
            runCurrent()

            coVerify(exactly = 1) { chatRepository.steerChat(SteerRequest(CONVERSATION_ID, TEXT)) }
            assertThat(vm.uiState.value.messageQueue).isEmpty()
        }

    @Test
    fun `a steer does not destroy a staged attachment routing batch`() =
        duringRunTest(DuringRunAction.STEER) { vm ->
            assertThat(vm.uiState.value.isStreaming).isTrue()
            every { settingsDataStore.uploadRoutingMode } returns flowOf(UploadRoutingMode.MANUAL)
            val picked = listOf(
                PickedFile(ref = "report.pdf", name = "report.pdf", mimeType = "application/pdf"),
            )
            every { fileHandler.describe(any()) } returns picked
            vm.onFilesSelected(picked.map { it.ref })
            runCurrent()
            assertThat(vm.uiState.value.composer.pendingUploadRouting).isNotNull()

            vm.onInputChanged(TEXT)
            vm.steerMessage()
            runCurrent()

            // A staged batch is deliberately not in `attachedFiles` — nothing is uploaded until the
            // user answers — so `steerMessage`'s attachment check waves it through, and the
            // `clearComposer()` that follows would wipe the batch with no upload and no error.
            // `withUploadGate` guards the other two send paths but this one bypasses it entirely.
            coVerify(exactly = 0) { chatRepository.steerChat(any()) }
            assertThat(vm.uiState.value.composer.pendingUploadRouting).isNotNull()
            assertThat(vm.uiState.value.inputText).isEqualTo(TEXT)
        }

    @Test
    fun `cancelling a queued edit discards a pick made during it rather than re-homing it`() {
        val preference = MutableSharedFlow<UploadRoutingMode>()
        return duringRunTest(
            preference = DuringRunAction.QUEUE,
            arrange = { every { settingsDataStore.uploadRoutingMode } returns preference },
        ) { vm ->
            vm.onInputChanged("the next turn")
            vm.queueMessage()
            runCurrent()
            val queued = vm.uiState.value.messageQueue.single()

            vm.editQueued(queued.localId)
            runCurrent()
            assertThat(vm.uiState.value.isEditingQueued).isTrue()

            val picked = listOf(
                PickedFile(ref = "report.pdf", name = "report.pdf", mimeType = "application/pdf"),
            )
            every { fileHandler.describe(any()) } returns picked
            vm.onFilesSelected(picked.map { it.ref })
            runCurrent()

            // Cancel restores the stashed new-message draft over the whole tray. The queued item
            // goes back unchanged — correctly, since cancel discards composer changes and a file
            // picked during the edit is one of them.
            vm.cancelQueuedEdit()
            runCurrent()

            preference.emit(UploadRoutingMode.AUTO)
            runCurrent()

            // Landing it now would attach it to a draft it was not picked for, while the message
            // it WAS picked for is already back in the queue without it.
            verify(exactly = 0) { fileHandler.onFilesSelected(any()) }
            assertThat(vm.uiState.value.messageQueue.map { it.localId }).containsExactly(queued.localId)
        }
    }

    @Test
    fun `a queued edit does not steal a pick that has not settled yet`() {
        // Held open on the preference read, standing in for the whole asynchronous intake window.
        val preference = MutableSharedFlow<UploadRoutingMode>()
        return duringRunTest(
            preference = DuringRunAction.QUEUE,
            arrange = { every { settingsDataStore.uploadRoutingMode } returns preference },
        ) { vm ->
            vm.onInputChanged("the next turn")
            vm.queueMessage()
            runCurrent()
            val queued = vm.uiState.value.messageQueue.single()

            val picked = listOf(
                PickedFile(ref = "report.pdf", name = "report.pdf", mimeType = "application/pdf"),
            )
            every { fileHandler.describe(any()) } returns picked
            vm.onFilesSelected(picked.map { it.ref })
            runCurrent()

            vm.editQueued(queued.localId)
            runCurrent()

            // The ghost bubble stays tappable through the intake window — there is no sheet or
            // scrim yet. `editQueued` swaps the whole composer, and `captureComposer` cannot stash
            // a file that has not reached the tray, so the pick would land on the queued item
            // instead: attached to a message the user did not pick it for, and gone from the one
            // they did as soon as the stashed draft came back.
            assertThat(vm.uiState.value.isEditingQueued).isFalse()
            assertThat(vm.uiState.value.messageQueue.map { it.localId }).containsExactly(queued.localId)
        }
    }

    @Test
    fun `send during a run queues when the preference is queue`() =
        duringRunTest(DuringRunAction.QUEUE) { vm ->
            // The negative control. Without it the test above would also pass on a build that
            // steers unconditionally, ignoring the preference in the other direction.
            assertThat(vm.uiState.value.isStreaming).isTrue()

            vm.onInputChanged(TEXT)
            vm.sendDuringRun()
            runCurrent()

            coVerify(exactly = 0) { chatRepository.steerChat(any()) }
            assertThat(vm.uiState.value.messageQueue.map { it.text }).containsExactly(TEXT)
        }

    @Test
    fun `a steer preference degrades to queueing on a server without the steer route`() =
        duringRunTest(
            preference = DuringRunAction.STEER,
            // No detected backend at all: the gate fails closed, which is what makes the
            // preference safe to honour without re-checking at the send site.
            arrange = { every { configRepository.detectedBackend } returns MutableStateFlow(null) },
        ) { vm ->
            vm.onInputChanged(TEXT)
            vm.sendDuringRun()
            runCurrent()

            coVerify(exactly = 0) { chatRepository.steerChat(any()) }
            assertThat(vm.uiState.value.messageQueue.map { it.text }).containsExactly(TEXT)
        }

    /**
     * Turn identity, end to end through the ViewModel.
     *
     * `SteeringDelegateTest` covers the same rule by calling `onTurnBoundary()` on the delegate
     * directly, which proves the logic but not the WIRING — it would still pass if
     * `beginStreaming` stopped calling it. This drives a genuine second turn instead: a queued
     * follow-up drains when the first run dies, and the drain is what re-arms `isStreaming`.
     *
     * That is the interleaving `SteerRecord.turnEpoch` exists for. The ack carries no run id and
     * `isStreaming` is global, so without the epoch a slow 202 from the finished turn is
     * indistinguishable from one belonging to the turn now running — and it would mint a chip
     * against a run that never accepted it, which no later event can ever retire.
     */
    @Test
    fun `an ack that outlives its turn is re-homed rather than attached to the next run`() {
        val ack = CompletableDeferred<Result<SteerResponse>>()
        return duringRunTest(
            preference = DuringRunAction.STEER,
            arrange = { coEvery { chatRepository.steerChat(any()) } coAnswers { ack.await() } },
        ) { vm ->
            // A follow-up is queued first: draining it is what starts the second turn below.
            vm.onInputChanged("the next turn")
            vm.queueMessage()
            runCurrent()

            vm.onInputChanged(TEXT)
            vm.sendDuringRun()
            runCurrent()
            // In flight: the POST has gone out but the 202 has not come back.
            assertThat(vm.uiState.value.pendingSteers.map { it.text }).containsExactly(TEXT)

            // Run A dies. A stream error deliberately HOLDS the queue rather than auto-firing it,
            // so the second turn starts where the user starts it: "Send queued".
            resumedStream.emit(StreamEvent.Error("connection reset"))
            runCurrent()
            assertThat(vm.uiState.value.isQueuePaused).isTrue()

            vm.sendQueuedNow()
            runCurrent()
            assertThat(vm.uiState.value.isStreaming).isTrue()

            // Only now does run A's ack arrive, while run B is streaming.
            ack.complete(Result.Success(SteerResponse(status = "queued", steerId = "steer-late")))
            runCurrent()

            // Re-homed as a follow-up, not rendered as a pending steer against run B.
            assertThat(vm.uiState.value.pendingSteers).isEmpty()
            assertThat(vm.uiState.value.messageQueue.map { it.text }).containsExactly(TEXT)
        }
    }

    /**
     * A steer the server PARKED is held for the user on conversation open, not fired.
     *
     * `SteeringDelegateTest` asserts the same thing, but through fakes that cannot express the
     * failure: its `enqueueFollowUp` only appends to a list (the real one self-drains) and its
     * `pauseQueue` sets a flag unconditionally (the real one ignores an empty queue). Both halves
     * of the actual bug were therefore invisible to it, and a parked steer auto-sent itself on
     * open — found on device.
     *
     * This runs the real wiring: `/chat/status` reports the parked steer on open, and the
     * assertion is that no send went out and the text is sitting in a HELD queue.
     */
    @Test
    fun `a parked steer is held on conversation open instead of firing`() =
        duringRunTest(
            preference = DuringRunAction.STEER,
            arrange = {
                // The conversation opens onto a finished run whose un-injected steer the server
                // parked, and hands it over claim-on-read exactly as the live server does.
                coEvery { chatRepository.checkStreamStatus(eq(CONVERSATION_ID), any()) } coAnswers {
                    val claim = arg<(List<PendingSteer>) -> Unit>(1)
                    claim(listOf(PendingSteer(steerId = "parked-1", text = TEXT, createdAt = 1L)))
                    ChatStatusResponse(active = false)
                }
            },
        ) { vm ->
            assertThat(vm.uiState.value.isStreaming).isFalse()

            // Held, not sent: the whole point is that the user last saw this parked behind a Stop.
            assertThat(vm.uiState.value.messageQueue.map { it.text }).containsExactly(TEXT)
            assertThat(vm.uiState.value.isQueuePaused).isTrue()
            coVerify(exactly = 0) {
                chatRepository.startChat(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            }
        }

    /**
     * Builds a ViewModel already attached to a live run, runs [body] against it, and cancels its
     * scope before `runTest` drains the scheduler. The `finally` matters: without it a failed
     * assertion would leave the streaming flush loop parked and the run would hang instead of
     * reporting the failure. See the class KDoc.
     */
    /**
     * A clean finish with a non-empty queue must not erase the id of the reply that just settled.
     *
     * This is the only place the interleaving is visible. `drainNext` runs inside `endStream`, and
     * nothing between there and `beginStreaming` suspends — `awaitReplySettled`'s predicate was
     * already made true by the finalize, and `viewModelScope.launch` on an already-Main dispatcher
     * runs inline — so the whole chain completes in the same dispatch as the finalize, before
     * Compose gets a frame. A clear at the turn boundary therefore erased the flag before anything
     * could read it, and the activity groups of the reply just finishing folded at the same instant
     * the live tool cards vanished. Delegate-level tests cannot see this: the drain is what closes
     * the loop, and only the assembled ViewModel has one.
     */
    @Test
    fun `a queue drain does not erase the reply that just settled`() =
        duringRunTest(DuringRunAction.QUEUE) { vm ->
            // Queued while the first run streams, so the clean finish below drains it.
            vm.onInputChanged("the next turn")
            vm.queueMessage()
            runCurrent()
            assertThat(vm.uiState.value.messageQueue).hasSize(1)

            resumedStream.emit(
                StreamEvent.Final(
                    requestMessage = null,
                    responseMessage = com.garfiec.librechat.core.model.Message(
                        messageId = SETTLED_ID,
                        conversationId = CONVERSATION_ID,
                        text = "done",
                        isCreatedByUser = false,
                    ),
                    conversation = null,
                ),
            )
            runCurrent()

            // The drain really ran — without this the assertion below could pass simply because
            // no second turn ever started.
            assertThat(vm.uiState.value.messageQueue).isEmpty()
            assertThat(vm.uiState.value.isStreaming).isTrue()

            assertThat(vm.uiState.value.justSettledMessageId).isEqualTo(SETTLED_ID)
        }

    private fun duringRunTest(
        preference: DuringRunAction,
        arrange: () -> Unit = {},
        body: suspend TestScope.(ChatViewModel) -> Unit,
    ) = runTest(testDispatcher) {
        arrange()
        every { settingsDataStore.duringRunAction } returns flowOf(preference)
        // Stages the (endpoint, model) `init` applies, so the conversation opens with a real
        // selection rather than waiting on the model pipeline.
        selectionHandoff.put(conversationId = CONVERSATION_ID, endpoint = ENDPOINT, model = MODEL)
        val vm = newViewModel()
        runCurrent()
        try {
            body(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun newViewModel(): ChatViewModel =
        fixture.build(
            defaultDispatcher = testDispatcher,
            initialConversationId = CONVERSATION_ID,
        )

    private companion object {
        const val CONVERSATION_ID = "conv-1"
        const val ENDPOINT = "anthropic"
        const val MODEL = "claude-haiku-4-5"
        const val TEXT = "Answer only in French."
        const val SETTLED_ID = "assistant-1"
    }
}
