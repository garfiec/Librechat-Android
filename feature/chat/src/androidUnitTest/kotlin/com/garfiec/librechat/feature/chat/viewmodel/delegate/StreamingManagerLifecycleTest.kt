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
import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Stop tapped DURING the resume reattach window. onPause detaches the collector but leaves
     * isStreaming true, so a Stop here POSTs an abort with nothing left to receive the aborted
     * final. The onResume path must NOT run ResumeExpired (which wipes streamingContent and
     * reloads) — a pending abort owns teardown, and its AbortFallback preserves the partial.
     * Regression guard for the reintroduced vanishing-partial bug.
     */
    @Test
    fun `a stop during the resume reattach window preserves the partial`() =
        runTest(StandardTestDispatcher()) {
            coEvery { chatRepository.abortChat("conv-1") } returns Result.Success(Unit)
            val statusGate = CompletableDeferred<ChatStatusResponse>()
            coEvery { chatRepository.checkStreamStatus("conv-1") } coAnswers { statusGate.await() }
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, flow) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())

            events.send(StreamEvent.ContentDelta(chunk = "partial answer"))
            runCurrent()
            // Background (detaches the collector; isStreaming stays true), then foreground: the
            // reattach check parks on the gate.
            delegate.onPause()
            delegate.onResume()
            runCurrent()
            // Stop lands while the status check is still in flight.
            delegate.stopGeneration()
            runCurrent()
            // Server says the job is already gone — the old code would ResumeExpired-wipe here.
            statusGate.complete(ChatStatusResponse(active = false))
            runCurrent()

            // The wipe/reload must NOT have happened; the pending abort owns teardown.
            verify(exactly = 0) { reloadConversation(any()) }

            // The abort's watchdog fires and finalizes locally with the partial intact.
            advanceUntilIdle()
            assertThat(flow.value.isStreaming).isFalse()
            assertThat(flow.value.streamingContent).isEqualTo("partial answer")
            events.close()
            advanceUntilIdle()
        }

    /**
     * Overlapping pause/resume: a resume parks on the status check while a newer stream starts
     * (bumping the session). The stale resume's ResumeExpired must bail on the advanced session
     * instead of wiping the now-current stream's content — the unpinned-session defect (1a).
     */
    @Test
    fun `an overlapping resume does not wipe a newer session`() = runTest(StandardTestDispatcher()) {
        val statusGate = CompletableDeferred<ChatStatusResponse>()
        coEvery { chatRepository.checkStreamStatus("conv-1") } coAnswers { statusGate.await() }
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())
        runCurrent()

        // Park a resume on the status check (pins the current, soon-to-be-stale session).
        delegate.onPause()
        delegate.onResume()
        runCurrent()

        // A newer stream starts while the resume is parked — this advances the session.
        val events2 = Channel<StreamEvent>(Channel.UNLIMITED)
        delegate.beginStreaming(isEdit = false)
        delegate.launchStream(events2.receiveAsFlow())
        events2.send(StreamEvent.ContentDelta(chunk = "newer session content"))
        runCurrent()

        // The stale resume finally sees an expired status: it must bail, not wipe/reload the
        // newer live session.
        statusGate.complete(ChatStatusResponse(active = false))
        runCurrent()

        assertThat(flow.value.isStreaming).isTrue()
        verify(exactly = 0) { reloadConversation(any()) }
        events.close()
        events2.close()
        delegate.reset()
        advanceUntilIdle()
    }

    /**
     * A delayed abort ack from an old session must not cancel a newer session's watchdog. Without
     * the guard, the stale ack clobbers the live watchdog and binds a new one to the dead session,
     * so the newer stream — whose final never arrives — stays wedged (isStreaming stuck true).
     */
    @Test
    fun `a stale abort ack does not cancel the current session's watchdog`() =
        runTest(StandardTestDispatcher()) {
            val staleAck = CompletableDeferred<Result<Unit>>()
            var abortCalls = 0
            coEvery { chatRepository.abortChat("conv-1") } coAnswers {
                abortCalls++
                if (abortCalls == 1) staleAck.await() else Result.Success(Unit)
            }
            val events1 = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, flow) = delegateWith(this)
            delegate.beginStreaming(isEdit = false)
            delegate.launchStream(events1.receiveAsFlow())
            runCurrent()

            // Stop the first stream; its ack is delayed (parks on staleAck).
            delegate.stopGeneration()
            runCurrent()

            // A newer stream starts and is itself stopped — its ack is immediate, arming its watchdog.
            val events2 = Channel<StreamEvent>(Channel.UNLIMITED)
            delegate.beginStreaming(isEdit = false)
            delegate.launchStream(events2.receiveAsFlow())
            events2.send(StreamEvent.ContentDelta(chunk = "second partial"))
            runCurrent()
            delegate.stopGeneration()
            runCurrent()

            // The first stream's delayed ack lands now — it must NOT cancel the newer watchdog.
            staleAck.complete(Result.Success(Unit))
            runCurrent()

            // The newer session's watchdog still fires and finalizes it with its partial intact.
            advanceUntilIdle()
            assertThat(flow.value.isStreaming).isFalse()
            assertThat(flow.value.streamingContent).isEqualTo("second partial")
            events1.close()
            events2.close()
            advanceUntilIdle()
        }

    /**
     * A transient checkStreamStatus failure on foreground (a network blip, not an actual expiry)
     * must NOT be treated as ResumeExpired — that would wipe the in-progress reply and reload,
     * racing the server's persist. Preserve the partial and do not reload.
     */
    @Test
    fun `a transient resume-check failure preserves the partial instead of wiping`() =
        runTest(StandardTestDispatcher()) {
            coEvery { chatRepository.checkStreamStatus("conv-1") } throws RuntimeException("network blip")
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, flow) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())
            events.send(StreamEvent.ContentDelta(chunk = "half a reply"))
            runCurrent()

            delegate.onPause() // detaches the collector; isStreaming stays true
            delegate.onResume() // checkStreamStatus throws
            advanceUntilIdle()

            assertThat(flow.value.isStreaming).isFalse()
            assertThat(flow.value.streamingContent).isEqualTo("half a reply")
            verify(exactly = 0) { reloadConversation(any()) }
            events.close()
            advanceUntilIdle()
        }

    /**
     * resumeActiveStreamIfNeeded (conversation-open sibling of onResume) must defer while a Stop
     * is pending — otherwise it can restart a stream the user just stopped.
     */
    @Test
    fun `resumeActiveStreamIfNeeded defers while a stop is pending`() =
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Result<Unit>>()
            coEvery { chatRepository.abortChat("conv-1") } coAnswers { gate.await() }
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, _) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())
            runCurrent()
            delegate.stopGeneration() // abortRequested = true, ack still gated
            runCurrent()

            delegate.resumeActiveStreamIfNeeded("conv-1")
            runCurrent()

            coVerify(exactly = 0) { chatRepository.checkStreamStatus(any()) }
            gate.complete(Result.Success(Unit))
            events.close()
            advanceUntilIdle()
        }
}
