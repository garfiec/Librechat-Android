package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ResumePinStore
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingActionPayload
import com.garfiec.librechat.core.model.PendingActionTypes
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.response.ChatResumeResponse
import com.garfiec.librechat.feature.chat.viewmodel.ChatRequestBuilder
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.garfiec.librechat.feature.chat.viewmodel.ModelSelectionState
import com.garfiec.librechat.feature.chat.viewmodel.PendingActionHandle
import com.garfiec.librechat.feature.chat.viewmodel.StreamingHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
 * Composition coverage for the HITL resume fence (v0.8.8-rc1 `generationCreatedAt`), driven
 * through [StreamingManagerDelegate] with a REAL [PendingActionDelegate] wired in.
 *
 * The wire order this replays is what every fresh send produces: ChatRepositoryImpl emits a
 * synthetic [StreamEvent.Created] carrying the start POST envelope's epoch, then the server's own
 * SSE `created` frame arrives — which never carries one. A delegate-only test cannot see that
 * second frame at all, and a build that lets it null the epoch sends every live-run resume
 * unfenced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingManagerGenerationEpochTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)

    private fun state() = ChatUiState(
        conversation = ConversationMetaState(conversationId = "conv-1"),
        selection = ModelSelectionState(selectedEndpoint = "agents", selectedModel = "agent_abc"),
        content = MessagesState(isStreaming = true),
    )

    private fun delegatesWith(
        scope: TestScope,
    ): Pair<StreamingManagerDelegate, PendingActionDelegate> {
        val flow = MutableStateFlow(state())
        val root = ChatStateHandle(flow, scope)
        val pendingActionDelegate = PendingActionDelegate(
            handle = PendingActionHandle(root),
            chatRepository = chatRepository,
            requestBuilder = ChatRequestBuilder { flow.value },
            resumeFailureMessage = { it ?: "failed" },
            fingerprintRejectedMessage = { "rejected" },
            restoreAnswer = {},
            resumePinStore = ResumePinStore(),
            pauseExpiredMessage = { "expired" },
            nowMillis = { 0L },
        )
        val connectivity = mockk<ConnectivityObserver>(relaxed = true)
        every { connectivity.isConnected } returns flowOf(true)
        val streamingDelegate = StreamingManagerDelegate(
            handle = StreamingHandle(root),
            chatRepository = chatRepository,
            activeAccountProvider = mockk<ActiveAccountProvider>(relaxed = true),
            connectivityObserver = connectivity,
            comparisonDelegate = mockk(relaxed = true),
            subagentTraceDelegate = mockk(relaxed = true),
            officePreviewDelegate = mockk(relaxed = true),
            completionDelegate = mockk(relaxed = true),
            queueDelegate = mockk(relaxed = true),
            treeDelegate = mockk(relaxed = true),
            pendingActionDelegate = pendingActionDelegate,
            steeringDelegate = mockk(relaxed = true),
            emitUserKeyError = {},
            reloadConversation = {},
            restoreUnsentInput = { _, _ -> },
            isNewConversation = { false },
            isHandedOffNewChat = { false },
        )
        return streamingDelegate to pendingActionDelegate
    }

    private fun created(generationCreatedAt: Long?) = StreamEvent.Created(
        conversationId = "conv-1",
        messageId = "m1",
        parentMessageId = "u1",
        generationCreatedAt = generationCreatedAt,
    )

    @Test
    fun `SSE created frame without an epoch does not wipe the recorded fence`() =
        runTest(StandardTestDispatcher()) {
            val (streamingDelegate, pendingActionDelegate) = delegatesWith(this)
            val request = slot<ChatResumeRequest>()
            coEvery { chatRepository.resumeChat(capture(request)) } returns
                Result.Success(ChatResumeResponse(status = "resuming"))

            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            streamingDelegate.launchStream(events.receiveAsFlow())
            // Synthetic Created from the start POST envelope — the only carrier of the epoch...
            events.send(created(generationCreatedAt = 1755400000123L))
            // ...then the server's own SSE `created` frame, which upstream always emits and
            // which never carries one.
            events.send(created(generationCreatedAt = null))
            runCurrent()

            pendingActionDelegate.onPendingAction(
                PendingAction(
                    actionId = "act-1",
                    conversationId = "conv-1",
                    payload = PendingActionPayload(type = PendingActionTypes.TOOL_APPROVAL),
                ),
            )
            pendingActionDelegate.submitAnswer("yes")
            runCurrent()

            assertThat(request.captured.generationCreatedAt).isEqualTo(1755400000123L)
            events.close()
            advanceUntilIdle()
        }
}
