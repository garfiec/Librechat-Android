package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.AbortFrameFixtures
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.garfiec.librechat.feature.chat.viewmodel.StreamingHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers [StreamingManagerDelegate.stopGeneration] — the Stop button's flow.
 *
 * The load-bearing behavior, and the whole point of the design: **Stop does not cancel the
 * stream.** The abort POST only acks; the server ends the turn by emitting an ordinary `final`
 * frame flagged `aborted` over the same SSE stream, and that frame is what carries the partial.
 * Cancelling the collector (the original bug) threw that frame away, which is why the stopped
 * reply vanished. The tests below pin that, plus the stop-specific handling keyed off the flag
 * and the local fallback for when the abort request itself fails.
 *
 * Virtual-time note: an acked abort arms the 15s watchdog, so tests inside the abort window use
 * [runCurrent] — `advanceUntilIdle` would fast-forward the delay and fire the watchdog mid-test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingManagerStopTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val comparisonDelegate = mockk<ComparisonModeDelegate>(relaxed = true)
    private val completionDelegate = mockk<SendCompletionDelegate>(relaxed = true)
    private val queueDelegate = mockk<MessageQueueDelegate>(relaxed = true)
    private val reloadConversation = mockk<(String) -> Unit>(relaxed = true)
    private val treeDelegate = mockk<MessageTreeDelegate>(relaxed = true)
    private val restoreUnsentInput = mockk<(String) -> Unit>(relaxed = true)

    private fun message(id: String, parentId: String? = null, isUser: Boolean = false) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = parentId,
        text = "text-$id",
        isCreatedByUser = isUser,
    )

    private fun streamingState() = ChatUiState(
        conversation = ConversationMetaState(conversationId = "conv-1"),
        content = MessagesState(messages = listOf(message("u1", isUser = true)), isStreaming = true),
    )

    private fun delegateWith(
        scope: TestScope,
        state: ChatUiState = streamingState(),
    ): Pair<StreamingManagerDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val root = ChatStateHandle(flow, scope)
        val connectivity = mockk<ConnectivityObserver>(relaxed = true)
        every { connectivity.isConnected } returns flowOf(true)
        val delegate = StreamingManagerDelegate(
            handle = StreamingHandle(root),
            chatRepository = chatRepository,
            activeAccountProvider = mockk<ActiveAccountProvider>(relaxed = true),
            connectivityObserver = connectivity,
            comparisonDelegate = comparisonDelegate,
            subagentTraceDelegate = mockk(relaxed = true),
            officePreviewDelegate = mockk(relaxed = true),
            completionDelegate = completionDelegate,
            queueDelegate = queueDelegate,
            treeDelegate = treeDelegate,
            emitUserKeyError = {},
            reloadConversation = reloadConversation,
            restoreUnsentInput = restoreUnsentInput,
            isNewConversation = { false },
            isHandedOffNewChat = { false },
        )
        return delegate to flow
    }

    /** The realistic wire shape: content parts present, no text. See AbortFrameFixtures. */
    private fun abortedFinal() = AbortFrameFixtures.persistedAbortFrame()

    /**
     * The regression test for the original bug. The stream must still be collecting after Stop,
     * so the aborted final — and the partial it carries — actually arrives.
     */
    @Test
    fun `stop leaves the stream collecting so the aborted final still lands`() =
        runTest(StandardTestDispatcher()) {
            // Explicit, not relaxed: a relaxed ChatRepository returns a mock that isn't a
            // Result.Success, which would send stopGeneration down the failed-abort path and
            // cancel the very stream this test is about.
            coEvery { chatRepository.abortChat("conv-1") } returns Result.Success(Unit)
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, _) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())

            events.send(StreamEvent.ContentDelta(chunk = "partial answer"))
            runCurrent()

            delegate.stopGeneration()
            runCurrent()

            // Still live: the abort was requested but the turn has not ended yet.
            coVerify(exactly = 1) { chatRepository.abortChat("conv-1") }
            verify(exactly = 0) { completionDelegate.onFinal(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

            // The server now ends the run over the same stream.
            events.send(abortedFinal())
            runCurrent()

            // Finalized through the normal completion path, carrying the partial that would have
            // been discarded had Stop cancelled the collector.
            val text = slot<String>()
            verify {
                completionDelegate.onFinal(
                    event = any(),
                    conversationId = "conv-1",
                    completedResponseText = capture(text),
                    shouldAutoRead = false,
                    isNewConversation = any(),
                    isHandedOffNewChat = any(),
                    isComparison = false,
                    originAccount = any(),
                    aborted = true,
                )
            }
            assertThat(text.captured).isEqualTo("partial answer")
            // A stopped turn holds the queue rather than firing the next item.
            verify(exactly = 0) { queueDelegate.drainNext(any()) }
            verify(atLeast = 1) { queueDelegate.pause() }
            // No refetch — the frame is authoritative, so nothing races the server's persistence.
            verify(exactly = 0) { reloadConversation(any()) }
            events.close()
            advanceUntilIdle()
        }

    /** The flag is read off the frame, so a normal completion is unaffected by any of this. */
    @Test
    fun `an unflagged final still auto-reads and drains`() = runTest(StandardTestDispatcher()) {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, _) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(StreamEvent.Final(responseMessage = message("a1", parentId = "u1")))
        advanceUntilIdle()

        verify {
            completionDelegate.onFinal(
                event = any(),
                conversationId = any(),
                completedResponseText = any(),
                shouldAutoRead = true,
                isNewConversation = any(),
                isHandedOffNewChat = any(),
                isComparison = any(),
                originAccount = any(),
                aborted = false,
            )
        }
        verify(exactly = 1) { queueDelegate.drainNext(any()) }
        events.close()
        advanceUntilIdle()
    }

    /**
     * An abort flagged by the server but never requested locally — e.g. Stop pressed on another
     * device against the same conversation — takes the identical path.
     */
    @Test
    fun `an abort we never requested is still treated as a stop`() = runTest(StandardTestDispatcher()) {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, _) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(abortedFinal())
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.abortChat(any()) }
        verify {
            completionDelegate.onFinal(any(), any(), any(), false, any(), any(), any(), any(), true)
        }
        verify(exactly = 0) { queueDelegate.drainNext(any()) }
        events.close()
        advanceUntilIdle()
    }

    @Test
    fun `a second stop before the final arrives does not fire a second abort`() =
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Result<Unit>>()
            coEvery { chatRepository.abortChat("conv-1") } coAnswers { gate.await() }
            val (delegate, _) = delegateWith(this)

            delegate.stopGeneration()
            delegate.stopGeneration() // double-tap: the stream is still live, isStreaming still true
            runCurrent()
            gate.complete(Result.Success(Unit))
            advanceUntilIdle()

            coVerify(exactly = 1) { chatRepository.abortChat("conv-1") }
        }

    /**
     * When the abort request fails there is no frame coming, so the stream would hang in its
     * streaming state. The local fallback ends it — preserving the partial. It must NOT refetch:
     * the server may not have persisted anything yet (it saves only after emitting the frame),
     * and the optimistic user message was never written to Room, so a reload here can lose both.
     */
    @Test
    fun `a failed abort stops the stream locally without reloading`() = runTest(StandardTestDispatcher()) {
        coEvery { chatRepository.abortChat("conv-1") } returns Result.Error(message = "Job not found")
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(StreamEvent.ContentDelta(chunk = "half an answer"))
        advanceUntilIdle()
        delegate.stopGeneration()
        advanceUntilIdle()

        assertThat(flow.value.isStreaming).isFalse()
        assertThat(flow.value.streamingContent).isEqualTo("half an answer")
        verify(exactly = 0) { reloadConversation(any()) }
        // The partial panes survive too — same intent as preserving streamingContent.
        verify { comparisonDelegate.endStreaming(clearContent = false) }
        // The hold is re-asserted so a follow-up queued mid-round-trip keeps its affordance.
        verify(atLeast = 1) { queueDelegate.pause() }
        events.close()
        advanceUntilIdle()
    }

    /** A failed abort must not leave the guard armed and deaden Stop on the next stream. */
    @Test
    fun `stop works again after a failed abort`() = runTest(StandardTestDispatcher()) {
        coEvery { chatRepository.abortChat("conv-1") } returns Result.Error(message = "Job not found")
        val (delegate, flow) = delegateWith(this)

        delegate.stopGeneration()
        advanceUntilIdle()

        // A new stream begins and the user stops that one too.
        flow.value = streamingState()
        delegate.beginStreaming(isEdit = false)
        delegate.stopGeneration()
        advanceUntilIdle()

        coVerify(exactly = 2) { chatRepository.abortChat("conv-1") }
    }

    /**
     * Stop before the `created` milestone has assigned a conversation id. This test asserts only
     * the client behavior: the abort still goes out with a null key instead of silently doing
     * nothing while the reply keeps generating. It does NOT verify the server-side fallback's job
     * selection — that resolves to the caller's *oldest* active job and can hit the wrong one when
     * several are live (see stopGeneration's KDoc); that behavior is not testable at this layer.
     */
    @Test
    fun `stop before the conversation exists still posts a null-key abort`() =
        runTest(StandardTestDispatcher()) {
            coEvery { chatRepository.abortChat(null) } returns Result.Success(Unit)
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, _) = delegateWith(
                this,
                streamingState().let { it.copy(conversation = it.conversation.copy(conversationId = null)) },
            )
            delegate.launchStream(events.receiveAsFlow())

            delegate.stopGeneration()
            runCurrent()

            coVerify(exactly = 1) { chatRepository.abortChat(null) }
            events.close()
            advanceUntilIdle()
        }

    /**
     * Early abort: the Stop landed before the server's `created` milestone, so NOTHING was
     * persisted — not even the user message. The turn is un-sent (optimistic bubble removed,
     * text handed back to the composer) instead of finalized; the next sync would have silently
     * dropped any bubble kept here.
     */
    @Test
    fun `an early abort un-sends the optimistic turn and restores the draft`() =
        runTest(StandardTestDispatcher()) {
            coEvery { chatRepository.abortChat("conv-1") } returns Result.Success(Unit)
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, _) = delegateWith(this)
            delegate.beginStreaming(isEdit = false, optimisticUserMessageId = "u1")
            delegate.launchStream(events.receiveAsFlow())

            delegate.stopGeneration()
            runCurrent()
            events.send(AbortFrameFixtures.earlyAbortFrame())
            runCurrent()

            verify(exactly = 1) { treeDelegate.unsendOptimisticTurn("u1") }
            // The optimistic message's own text (from state), not the frame's.
            verify(exactly = 1) { restoreUnsentInput("text-u1") }
            // Nothing was saved server-side: no completion work at all — no conversation save,
            // no cacheTurn, no title refresh, no TTS.
            verify(exactly = 0) {
                completionDelegate.onFinal(any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            verify(atLeast = 1) { queueDelegate.pause() }
            events.close()
            advanceUntilIdle()
        }

    /**
     * Regenerate/continue/edit-AI resubmit a PERSISTED user message — an early abort on those
     * turns must not remove it (there is no minted optimistic id).
     */
    @Test
    fun `an early abort on a regenerate removes no message`() = runTest(StandardTestDispatcher()) {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, _) = delegateWith(this)
        delegate.beginStreaming(isEdit = true) // no optimistic id: resubmit of a persisted turn
        delegate.launchStream(events.receiveAsFlow())

        events.send(AbortFrameFixtures.earlyAbortFrame())
        runCurrent()

        verify(exactly = 1) { treeDelegate.unsendOptimisticTurn(null) }
        verify(exactly = 0) { restoreUnsentInput(any()) }
        events.close()
        advanceUntilIdle()
    }

    /** A dead screen's Stop must not abort-by-fallback some other conversation's job. */
    @Test
    fun `stop is a no-op when nothing is streaming`() = runTest(StandardTestDispatcher()) {
        val (delegate, _) = delegateWith(
            this,
            streamingState().let { it.copy(content = it.content.copy(isStreaming = false)) },
        )

        delegate.stopGeneration()
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.abortChat(any()) }
        verify(exactly = 0) { reloadConversation(any()) }
    }
}
