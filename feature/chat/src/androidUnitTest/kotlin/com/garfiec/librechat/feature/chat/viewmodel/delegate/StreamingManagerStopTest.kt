package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingManagerStopTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val comparisonDelegate = mockk<ComparisonModeDelegate>(relaxed = true)
    private val completionDelegate = mockk<SendCompletionDelegate>(relaxed = true)
    private val queueDelegate = mockk<MessageQueueDelegate>(relaxed = true)
    private val reloadConversation = mockk<(String) -> Unit>(relaxed = true)

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
            emitUserKeyError = {},
            reloadConversation = reloadConversation,
            isNewConversation = { false },
            isHandedOffNewChat = { false },
        )
        return delegate to flow
    }

    private fun abortedFinal() = StreamEvent.Final(
        responseMessage = message("a1", parentId = "u1"),
        aborted = true,
    )

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
            advanceUntilIdle()

            delegate.stopGeneration()
            advanceUntilIdle()

            // Still live: the abort was requested but the turn has not ended yet.
            coVerify(exactly = 1) { chatRepository.abortChat("conv-1") }
            verify(exactly = 0) { completionDelegate.onFinal(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

            // The server now ends the run over the same stream.
            events.send(abortedFinal())
            advanceUntilIdle()

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
            advanceUntilIdle()
            gate.complete(Result.Success(Unit))
            advanceUntilIdle()

            coVerify(exactly = 1) { chatRepository.abortChat("conv-1") }
        }

    /**
     * When the abort request fails there is no frame coming, so the stream would hang in its
     * streaming state. The local fallback ends it — preserving the partial and re-reading the
     * server for whatever it managed to persist.
     */
    @Test
    fun `a failed abort stops the stream locally and reloads`() = runTest(StandardTestDispatcher()) {
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
        verify(exactly = 1) { reloadConversation("conv-1") }
        verify { comparisonDelegate.endStreaming(clearContent = true) }
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

    @Test
    fun `stop is a no-op with no conversation`() = runTest(StandardTestDispatcher()) {
        val (delegate, _) = delegateWith(
            this,
            streamingState().let { it.copy(conversation = it.conversation.copy(conversationId = null)) },
        )

        delegate.stopGeneration()
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.abortChat(any()) }
        verify(exactly = 0) { reloadConversation(any()) }
    }
}
