package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.AbortFrameFixtures
import com.garfiec.librechat.core.model.response.ChatStatusResponse
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
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers [StreamingManagerDelegate.onPause]/[StreamingManagerDelegate.onResume] around the
 * stop/abort window. The headline regression: Stop then background is a very common pairing,
 * and the pause handler must NOT cancel the collector that is carrying the aborted final —
 * that frame holds the partial the whole stop design exists to preserve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingManagerLifecycleTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val comparisonDelegate = mockk<ComparisonModeDelegate>(relaxed = true)
    private val completionDelegate = mockk<SendCompletionDelegate>(relaxed = true)
    private val queueDelegate = mockk<MessageQueueDelegate>(relaxed = true)
    private val reloadConversation = mockk<(String) -> Unit>(relaxed = true)
    private val treeDelegate = mockk<MessageTreeDelegate>(relaxed = true)
    private val restoreUnsentInput = mockk<(String) -> Unit>(relaxed = true)

    private fun message(id: String, isUser: Boolean = false) = Message(
        messageId = id,
        conversationId = "conv-1",
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
     * THE regression test: stop, background the app, and the aborted final lands while
     * backgrounded. onPause must leave the collector alive (it is carrying the partial), and
     * onResume must recognize the session already ended and touch nothing — the old code wiped
     * streamingContent here, which is exactly the vanishing-partial bug.
     */
    @Test
    fun `stop then background still delivers the partial and resume touches nothing`() =
        runTest(StandardTestDispatcher()) {
            coEvery { chatRepository.abortChat("conv-1") } returns Result.Success(Unit)
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, _) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())

            events.send(StreamEvent.ContentDelta(chunk = "partial answer"))
            runCurrent()
            delegate.stopGeneration()
            runCurrent()

            // User switches apps while the abort is pending.
            delegate.onPause()

            // The frame arrives while backgrounded — the collector must still be there for it.
            events.send(abortedFinal())
            runCurrent()
            verify(exactly = 1) {
                completionDelegate.onFinal(any(), any(), "partial answer", any(), any(), any(), any(), any(), true)
            }

            // Back to the foreground: the session already ended, so resume is a pure no-op —
            // no status check, no state write, no reload.
            delegate.onResume()
            advanceUntilIdle()
            coVerify(exactly = 0) { chatRepository.checkStreamStatus(any()) }
            verify(exactly = 0) { reloadConversation(any()) }
            events.close()
            advanceUntilIdle()
        }

    /** Without a pending stop, backgrounding detaches the collector as before. */
    @Test
    fun `backgrounding without a stop cancels the collector`() = runTest(StandardTestDispatcher()) {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, _) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())
        runCurrent()

        delegate.onPause()
        runCurrent()

        // Events after the detach go nowhere: the collector is gone.
        events.send(StreamEvent.Final(responseMessage = message("a1")))
        runCurrent()
        verify(exactly = 0) {
            completionDelegate.onFinal(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        events.close()
        advanceUntilIdle()
    }

    @Test
    fun `resume with an active server stream reattaches`() = runTest(StandardTestDispatcher()) {
        coEvery { chatRepository.checkStreamStatus("conv-1") } returns ChatStatusResponse(active = true)
        coEvery { chatRepository.resumeStream("conv-1") } returns emptyFlow()
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())
        runCurrent()

        delegate.onPause()
        delegate.onResume()
        // runCurrent, not advanceUntilIdle: resumeStream restarts the throttled updater loop,
        // which never idles until reset() cancels it below.
        runCurrent()

        assertThat(flow.value.isStreaming).isTrue()
        coVerify(exactly = 1) { chatRepository.resumeStream("conv-1") }
        // resumeStream started a fresh session; end it so the updater doesn't leak.
        delegate.reset()
        events.close()
        advanceUntilIdle()
    }

    /**
     * The stream expired while backgrounded (job completed and was cleaned up server-side):
     * wipe the streaming bubble, hold the queue, and reload — safe here because "expired"
     * means the turn ended in the past, well clear of the emit-then-persist race window.
     */
    @Test
    fun `resume with an expired stream wipes and reloads`() = runTest(StandardTestDispatcher()) {
        coEvery { chatRepository.checkStreamStatus("conv-1") } returns ChatStatusResponse(active = false)
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(StreamEvent.ContentDelta(chunk = "stale partial"))
        runCurrent()
        delegate.onPause()
        delegate.onResume()
        advanceUntilIdle()

        assertThat(flow.value.isStreaming).isFalse()
        assertThat(flow.value.streamingContent).isEmpty()
        verify(exactly = 1) { reloadConversation("conv-1") }
        verify(atLeast = 1) { queueDelegate.pause() }
        events.close()
        advanceUntilIdle()
    }
}
