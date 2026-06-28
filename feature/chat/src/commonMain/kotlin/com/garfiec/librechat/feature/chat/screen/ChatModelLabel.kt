package com.garfiec.librechat.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.chat_agent_fallback_label
import org.jetbrains.compose.resources.stringResource

/**
 * The resolved model labels for the chat header/composer.
 *
 * @property agentName the selected agent's display name, or null when the active endpoint
 *   isn't agents or the agent isn't resolved yet (list not loaded / stale id).
 * @property displayModel the label to render. Under the agents endpoint this is the agent
 *   name, falling back to a neutral "Agent" label when unresolved — NEVER a raw model
 *   string, which would mislabel an agent chat as e.g. "Message gpt-3.5-turbo".
 *   Non-agent endpoints show the model name.
 */
data class ChatModelLabel(
    val agentName: String?,
    val displayModel: String?,
)

/**
 * Derives the [ChatModelLabel] for the current selection. Shared by the Android and iOS
 * chat screens so the agent-vs-model label logic (and the "never a raw model string
 * under agents" rule) lives in one place rather than drifting between platforms.
 */
@Composable
fun rememberChatModelLabel(
    selectedEndpoint: String?,
    selectedModel: String?,
    agents: List<Agent>,
): ChatModelLabel {
    val agentFallbackLabel = stringResource(Res.string.chat_agent_fallback_label)
    return remember(selectedEndpoint, selectedModel, agents, agentFallbackLabel) {
        chatModelLabel(
            selectedEndpoint = selectedEndpoint,
            selectedModel = selectedModel,
            agents = agents,
            agentFallbackLabel = agentFallbackLabel,
        )
    }
}

/**
 * Pure (non-Composable) label derivation, split out so the agent-vs-model and F4
 * "never a raw model string under agents" rules are unit-testable without a Compose
 * runtime. [agentFallbackLabel] is the already-resolved neutral "Agent" string.
 */
internal fun chatModelLabel(
    selectedEndpoint: String?,
    selectedModel: String?,
    agents: List<Agent>,
    agentFallbackLabel: String,
): ChatModelLabel {
    val agentName = if (selectedEndpoint == EndpointConstants.AGENTS && selectedModel != null) {
        agents.find { it.id == selectedModel }?.name
    } else {
        null
    }
    val displayModel = if (selectedEndpoint == EndpointConstants.AGENTS) {
        agentName ?: agentFallbackLabel
    } else {
        selectedModel
    }
    return ChatModelLabel(agentName = agentName, displayModel = displayModel)
}
