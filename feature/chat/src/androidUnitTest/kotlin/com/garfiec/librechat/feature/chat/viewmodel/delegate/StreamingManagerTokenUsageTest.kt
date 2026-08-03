package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.garfiec.librechat.feature.chat.viewmodel.StreamingHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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
 * `on_token_usage` handling. The handler is last-write-wins, so a bucketed event — a summary pass,
 * a subagent run, a hidden sequential call, or an activity-label header — must be dropped rather
 * than allowed to overwrite the turn's own figures in the breakdown sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingManagerTokenUsageTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)

    private fun delegateWith(
        scope: TestScope,
    ): Pair<StreamingManagerDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(
            ChatUiState(
                conversation = ConversationMetaState(conversationId = "conv-1"),
                content = MessagesState(
                    messages = listOf(
                        Message(messageId = "u1", conversationId = "conv-1", isCreatedByUser = true),
                    ),
                    isStreaming = true,
                ),
            ),
        )
        val root = ChatStateHandle(flow, scope)
        val connectivity = mockk<ConnectivityObserver>(relaxed = true)
        every { connectivity.isConnected } returns flowOf(true)
        val delegate = StreamingManagerDelegate(
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
            pendingActionDelegate = mockk(relaxed = true),
            steeringDelegate = mockk(relaxed = true),
            emitUserKeyError = {},
            reloadConversation = {},
            restoreUnsentInput = {},
            isNewConversation = { false },
            isHandedOffNewChat = { false },
        )
        return delegate to flow
    }

    @Test
    fun `a primary usage event lands in state`() = runTest(StandardTestDispatcher()) {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val (delegate, flow) = delegateWith(this)
        delegate.launchStream(events.receiveAsFlow())

        events.send(
            StreamEvent.TokenUsageUpdate(
                TokenUsage(inputTokens = 4000, outputTokens = 900, model = "claude-opus-5"),
            ),
        )
        runCurrent()

        assertThat(flow.value.tokenUsage?.inputTokens).isEqualTo(4000)
        assertThat(flow.value.tokenUsage?.outputTokens).isEqualTo(900)
        events.close()
        advanceUntilIdle()
    }

    /**
     * An activity-label header runs on a cheap fast model and emits its own usage once per tool
     * batch, so without the exclusion the sheet ends up showing a two-digit count for a turn that
     * spent thousands.
     */
    @Test
    fun `a bucketed usage event does not overwrite the primary figures`() =
        runTest(StandardTestDispatcher()) {
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, flow) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())

            events.send(
                StreamEvent.TokenUsageUpdate(
                    TokenUsage(inputTokens = 4000, outputTokens = 900, model = "claude-opus-5"),
                ),
            )
            runCurrent()

            for (bucket in listOf("activity-label", "summarization", "subagent", "sequential")) {
                events.send(
                    StreamEvent.TokenUsageUpdate(
                        TokenUsage(
                            inputTokens = 30,
                            outputTokens = 8,
                            model = "claude-haiku-4-5",
                            usageType = bucket,
                        ),
                    ),
                )
                runCurrent()
                assertThat(flow.value.tokenUsage?.inputTokens).isEqualTo(4000)
                assertThat(flow.value.tokenUsage?.outputTokens).isEqualTo(900)
            }

            events.close()
            advanceUntilIdle()
        }

    /** A bucket upstream adds later is unknown, non-null, and therefore excluded like the rest. */
    @Test
    fun `an unrecognized bucket is excluded rather than failing open`() =
        runTest(StandardTestDispatcher()) {
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val (delegate, flow) = delegateWith(this)
            delegate.launchStream(events.receiveAsFlow())

            events.send(
                StreamEvent.TokenUsageUpdate(TokenUsage(inputTokens = 4000, outputTokens = 900)),
            )
            runCurrent()
            events.send(
                StreamEvent.TokenUsageUpdate(
                    TokenUsage(inputTokens = 1, outputTokens = 1, usageType = "some-future-bucket"),
                ),
            )
            runCurrent()

            assertThat(flow.value.tokenUsage?.inputTokens).isEqualTo(4000)
            events.close()
            advanceUntilIdle()
        }
}
