package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ContextProjectionHandle
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.FeatureGatesState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.garfiec.librechat.feature.chat.viewmodel.ModelSelectionState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Guards the gauge-refresh behavior of [ContextProjectionDelegate]: the projection re-runs once per
 * completed turn (tail advance) so the gauge tracks the growing conversation on backends that don't
 * stream `on_context_usage` — without re-projecting mid-stream (the live SSE owns the gauge then).
 *
 * The `maxContextTokens` override on the model params supplies the projection denominator directly,
 * so the token-config network path is not exercised; the (non-agents) endpoint means the agent-model
 * resolution path is skipped too. That keeps each test to the one behavior under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextProjectionDelegateTest {

    private val endpointTokenRepository = mockk<EndpointTokenRepository>(relaxed = true)
    private val agentRepository = mockk<AgentRepository>(relaxed = true)

    private fun state(tailId: String, streaming: Boolean = false, usage: ContextUsage? = null) =
        ChatUiState(
            gates = FeatureGatesState(contextUsageEnabled = true),
            conversation = ConversationMetaState(conversationId = "c1"),
            selection = ModelSelectionState(
                selectedEndpoint = "openai",
                selectedModel = "gpt-4o",
                modelParameters = ModelParameters.DEFAULT.copy(maxContextTokens = 8000),
            ),
            content = MessagesState(
                displayMessages = listOf(node(tailId)),
                isStreaming = streaming,
                contextUsage = usage,
            ),
        )

    private fun node(id: String) = MessageNode(
        message = Message(messageId = id, conversationId = "c1"),
        children = emptyList(),
        siblingIndex = 0,
        siblingCount = 1,
    )

    @Test
    fun `re-projects when the tail advances even over an existing gauge`() =
        runTest(UnconfinedTestDispatcher()) {
            val usage = ContextUsage()
            coEvery { endpointTokenRepository.getContextProjection(any()) } returns Result.Success(usage)
            val flow = MutableStateFlow(state(tailId = "m1"))
            val handle = ChatStateHandle(flow, backgroundScope)
            ContextProjectionDelegate(
                ContextProjectionHandle(handle),
                agentRepository,
                endpointTokenRepository,
            ).start()
            advanceUntilIdle()

            // Initial load seeds the gauge.
            assertThat(flow.value.contextUsage).isEqualTo(usage)
            coVerify(exactly = 1) { endpointTokenRepository.getContextProjection(any()) }

            // A turn completes: the tail advances in the same window and the gauge is already set.
            // The `contextUsage != null` guard would freeze it; the forced refresh re-projects anyway.
            flow.value = state(tailId = "m2", usage = usage)
            advanceUntilIdle()
            coVerify(exactly = 2) { endpointTokenRepository.getContextProjection(any()) }
        }

    @Test
    fun `does not re-project when a live SSE reading already refreshed the gauge`() =
        runTest(UnconfinedTestDispatcher()) {
            val projected = ContextUsage(remainingContextTokens = 100)
            coEvery { endpointTokenRepository.getContextProjection(any()) } returns
                Result.Success(projected)
            val flow = MutableStateFlow(state(tailId = "m1"))
            val handle = ChatStateHandle(flow, backgroundScope)
            ContextProjectionDelegate(
                ContextProjectionHandle(handle),
                agentRepository,
                endpointTokenRepository,
            ).start()
            advanceUntilIdle()
            assertThat(flow.value.contextUsage).isEqualTo(projected)
            coVerify(exactly = 1) { endpointTokenRepository.getContextProjection(any()) }

            // The just-completed stream delivered an exact `on_context_usage` reading, so the gauge
            // now differs from what we projected. The tail advance must NOT overwrite that exact
            // reading with an estimate, nor fire a redundant projection.
            val sseReading = ContextUsage(remainingContextTokens = 200)
            flow.value = state(tailId = "m2", usage = sseReading)
            advanceUntilIdle()
            coVerify(exactly = 1) { endpointTokenRepository.getContextProjection(any()) }
            assertThat(flow.value.contextUsage).isEqualTo(sseReading)
        }

    @Test
    fun `does not re-project while streaming`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { endpointTokenRepository.getContextProjection(any()) } returns
            Result.Success(ContextUsage())
        val flow = MutableStateFlow(state(tailId = "m1"))
        val handle = ChatStateHandle(flow, backgroundScope)
        ContextProjectionDelegate(
            ContextProjectionHandle(handle),
            agentRepository,
            endpointTokenRepository,
        ).start()
        advanceUntilIdle()
        coVerify(exactly = 1) { endpointTokenRepository.getContextProjection(any()) }

        // Tail advances while a stream is in flight (the live SSE owns the gauge): no projection.
        flow.value = state(tailId = "m2", streaming = true, usage = ContextUsage())
        advanceUntilIdle()
        coVerify(exactly = 1) { endpointTokenRepository.getContextProjection(any()) }
    }
}
