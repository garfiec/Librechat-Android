package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.EndpointConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [ChatUiState.isSendReady], the pre-send gate used by [ChatViewModel]'s
 * `runWhenSendReady` helper to avoid the cold-start race where endpoint/config hasn't
 * arrived yet and firing startChat would produce a mislabeled 403.
 *
 * Pure-function tests — no ViewModel instantiation, no mockk overhead.
 */
class ChatUiStateSendReadyTest {

    private val anthropic = "anthropic"
    private val haiku = "claude-haiku-4-5-20251001"

    @Test
    fun `not ready when availableModels is empty`() {
        val state = ChatUiState(
            selection = ModelSelectionState(
                selectedEndpoint = anthropic,
                selectedModel = haiku,
                availableModels = emptyMap(),
            ),
            gates = FeatureGatesState(agentsEnabled = true),
        )
        assertThat(state.isSendReady).isFalse()
    }

    @Test
    fun `ready when non-agents endpoint selected and models available`() {
        val state = ChatUiState(
            selection = ModelSelectionState(
                selectedEndpoint = anthropic,
                selectedModel = haiku,
                availableModels = mapOf(anthropic to listOf(haiku)),
            ),
            gates = FeatureGatesState(agentsEnabled = true),
        )
        assertThat(state.isSendReady).isTrue()
    }

    @Test
    fun `not ready when agents endpoint selected but AGENTS USE denied`() {
        // This is the reporter's scenario: cold start with agents as default
        // selection, but role has loaded and denied AGENTS.USE. A send right now
        // would hit /api/agents/chat/agents and 403.
        val state = ChatUiState(
            selection = ModelSelectionState(
                selectedEndpoint = EndpointConstants.AGENTS,
                availableModels = mapOf(anthropic to listOf(haiku)),
            ),
            gates = FeatureGatesState(agentsEnabled = false),
        )
        assertThat(state.isSendReady).isFalse()
    }

    @Test
    fun `ready when agents endpoint selected and AGENTS USE granted`() {
        val state = ChatUiState(
            selection = ModelSelectionState(
                selectedEndpoint = EndpointConstants.AGENTS,
                availableModels = mapOf(
                    EndpointConstants.AGENTS to listOf("someAgentId"),
                    anthropic to listOf(haiku),
                ),
            ),
            gates = FeatureGatesState(agentsEnabled = true),
        )
        assertThat(state.isSendReady).isTrue()
    }

    @Test
    fun `not ready when only agents endpoint is offered by server but AGENTS USE denied`() {
        // Edge: server exposes only the agents endpoint in availableModels, role denies.
        // No viable send path — stays not-ready. runWhenSendReady will time out and toast.
        val state = ChatUiState(
            selection = ModelSelectionState(
                selectedEndpoint = EndpointConstants.AGENTS,
                availableModels = mapOf(EndpointConstants.AGENTS to listOf("someAgentId")),
            ),
            gates = FeatureGatesState(agentsEnabled = false),
        )
        assertThat(state.isSendReady).isFalse()
    }

    @Test
    fun `ready when non-agents endpoint selected and model list for that endpoint is empty`() {
        // isSendReady only cares that availableModels is non-empty and the selected
        // endpoint isn't a known-denied one. An empty model list on the current endpoint
        // is the user's concern (pick a model), not the race guard's.
        val state = ChatUiState(
            selection = ModelSelectionState(
                selectedEndpoint = anthropic,
                selectedModel = null,
                availableModels = mapOf(
                    EndpointConstants.AGENTS to listOf("someAgentId"),
                    anthropic to emptyList(),
                ),
            ),
            gates = FeatureGatesState(agentsEnabled = true),
        )
        assertThat(state.isSendReady).isTrue()
    }
}
