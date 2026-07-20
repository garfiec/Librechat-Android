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
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers the [StreamingManagerDelegate] termination chokepoint: every stream session ends
 * through exactly one `endStream(reason)`. Pins the session latch (a session cannot end twice,
 * and a stale end from stream N cannot touch stream N+1), the abort watchdog, and the two
 * legacy error paths now producing identical teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingManagerEndStreamTest {

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
     * The abort was acked but the SSE socket silently died: the frame never lands. Without the
     * watchdog, isStreaming stays true and abortRequested suppresses every further Stop until
     * the 120s SSE stall timeout — a wedged composer.
     */
    @Test
    fun `the watchdog stops the stream locally when the aborted final never arrives`() =
        runTest(StandardTestDispatcher()) {
            coEvery { chatRepository.abortChat("conv-1") } returns Result.Success(Unit)
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, flow) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())

            events.send(StreamEvent.ContentDelta(chunk = "half an answer"))
            runCurrent()
            delegate.stopGeneration()
            runCurrent()

            // Nothing yet: the watchdog window is still open.
            assertThat(flow.value.isStreaming).isTrue()

            advanceTimeBy(15_001)
            runCurrent()

            // Local stop: partial preserved, queue held, and — critically — no refetch that
            // could race the server's post-frame persistence.
            assertThat(flow.value.isStreaming).isFalse()
            assertThat(flow.value.streamingContent).isEqualTo("half an answer")
            verify(exactly = 0) { reloadConversation(any()) }
            verify { comparisonDelegate.endStreaming(clearContent = false) }
            verify(atLeast = 1) { queueDelegate.pause() }
            events.close()
            advanceUntilIdle()
        }

    @Test
    fun `the aborted final disarms the watchdog`() = runTest(StandardTestDispatcher()) {
        coEvery { chatRepository.abortChat("conv-1") } returns Result.Success(Unit)
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, _) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        delegate.stopGeneration()
        runCurrent()
        events.send(abortedFinal())
        runCurrent()

        // Well past the watchdog window: had it survived the final, its AbortFallback teardown
        // would have run (visible as an endStreaming(clearContent = false) call).
        advanceTimeBy(30_000)
        runCurrent()

        verify(exactly = 1) {
            completionDelegate.onFinal(any(), any(), any(), any(), any(), any(), any(), any(), true)
        }
        verify(exactly = 0) { comparisonDelegate.endStreaming(clearContent = false) }
        verify(exactly = 0) { reloadConversation(any()) }
        events.close()
        advanceUntilIdle()
    }

    /**
     * A session can only end once. The abort POST fails *after* the aborted final already
     * finalized the turn (its HTTP response timed out while the frame won the race): the late
     * AbortFallback must be a structural no-op, not a second teardown that wipes the finalized
     * state or fires a racing reload.
     */
    @Test
    fun `a late abort failure after the final is a no-op`() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { chatRepository.abortChat("conv-1") } coAnswers { gate.await() }
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(StreamEvent.ContentDelta(chunk = "half an answer"))
        runCurrent()
        delegate.stopGeneration()
        runCurrent()
        // The frame lands first...
        events.send(abortedFinal())
        runCurrent()
        // ...then the abort POST comes back failed.
        gate.complete(Result.Error(message = "timeout"))
        runCurrent()

        // The Finalized path cleared streamingContent; an AbortFallback teardown would have
        // rewritten it with the buffered partial. It must not have.
        assertThat(flow.value.streamingContent).isEmpty()
        verify(exactly = 0) { comparisonDelegate.endStreaming(clearContent = false) }
        verify(exactly = 0) { reloadConversation(any()) }
        events.close()
        advanceUntilIdle()
    }

    /**
     * The session counter, not just the watchdog-job cancel, protects a new stream: a delayed
     * abort failure from stream N landing after stream N+1 started must not tear down N+1.
     */
    @Test
    fun `a stale end from a previous stream cannot touch the new one`() =
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Result<Unit>>()
            coEvery { chatRepository.abortChat("conv-1") } coAnswers { gate.await() }
            val (delegate, flow) = delegateWith(this)

            delegate.stopGeneration()
            runCurrent()

            // A new stream starts before the abort POST resolves.
            flow.value = streamingState()
            delegate.beginStreaming(isEdit = false)
            runCurrent()

            // Stream N's abort failure finally lands.
            gate.complete(Result.Error(message = "Job not found"))
            runCurrent()

            // Stream N+1 untouched: no teardown state write, no queue pause beyond stream N's.
            assertThat(flow.value.isStreaming).isTrue()
            verify(exactly = 0) { comparisonDelegate.endStreaming(any()) }
            verify(exactly = 0) { reloadConversation(any()) }
            // beginStreaming started the throttled updater; end it so the scope can settle.
            delegate.reset()
            advanceUntilIdle()
        }

    /** The two legacy error paths — in-band Error event and flow exception — now tear down identically. */
    @Test
    fun `an error event preserves the partial and reloads`() = runTest(StandardTestDispatcher()) {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(StreamEvent.ContentDelta(chunk = "half an answer"))
        events.send(StreamEvent.Error(message = "boom"))
        advanceUntilIdle()

        assertThat(flow.value.isStreaming).isFalse()
        assertThat(flow.value.streamingContent).isEqualTo("half an answer")
        assertThat(flow.value.error).isEqualTo("boom")
        verify(atLeast = 1) { queueDelegate.pause() }
        verify(exactly = 0) { queueDelegate.drainNext(any()) }
        verify(exactly = 1) { reloadConversation("conv-1") }
        events.close()
        advanceUntilIdle()
    }

    @Test
    fun `a flow exception preserves the partial and reloads`() = runTest(StandardTestDispatcher()) {
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(
            flow {
                emit(StreamEvent.ContentDelta(chunk = "half an answer"))
                throw RuntimeException("boom")
            },
        )
        advanceUntilIdle()

        assertThat(flow.value.isStreaming).isFalse()
        assertThat(flow.value.streamingContent).isEqualTo("half an answer")
        assertThat(flow.value.error).isEqualTo("boom")
        verify(atLeast = 1) { queueDelegate.pause() }
        verify(exactly = 0) { queueDelegate.drainNext(any()) }
        verify(exactly = 1) { reloadConversation("conv-1") }
    }
}
