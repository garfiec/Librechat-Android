package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData

/**
 * Endpoint/model selection, per-request tool + MCP config, model parameters, and the
 * model-selector sheet's open state. Written by [ChatViewModel], ModelSelectionDelegate,
 * PresetPromptDelegate and EndpointKeyStatusDelegate.
 */
@Immutable
data class ModelSelectionState(
    val selectedModel: String? = null,
    val selectedEndpoint: String = EndpointConstants.AGENTS,
    val endpointConfigs: Map<String, EndpointConfig> = emptyMap(),
    /**
     * Per-endpoint user-provided-key state for endpoints with `userProvide=true`
     * (or `userProvideURL=true`). Drives the model selector's greyed-out group +
     * "Set API Key" CTA. Endpoints absent from this map are implicitly "doesn't
     * need a key" (built-ins) — [ModelSelectorSheet] fail-opens on `null` and
     * on [KeyState.Loading] so the cold-start window doesn't flash to greyed.
     */
    val endpointKeyStates: Map<String, KeyState> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),
    val agents: List<Agent> = emptyList(),
    /**
     * The selected agent's LLM provider, resolved by [ModelSelectionDelegate] from
     * `GET /api/agents/:id` whenever the agent selection changes. Null on every non-agents
     * endpoint, and null while the fetch is in flight or after it fails.
     *
     * It cannot be read off [agents]: the list projection omits `provider`. Anything routing on
     * it must treat null as "unknown" and fall back to whatever it did before agents had a
     * provider — never to a behaviour that only makes sense for a *known* provider.
     */
    val selectedAgentProvider: String? = null,
    val modelParameters: ModelParameters = ModelParameters.DEFAULT,
    /** Single source of truth for whether the *standalone* model-selector sheet is open — the
     *  top-bar chip, the comparison dual-pane, and send-block auto-opens. Preflight failures and
     *  readiness timeouts flip this to true so the user sees the sheet with a send-block banner.
     *  The chat options sheet shows the selector as an in-sheet page off its own local state, so
     *  it never sets this. */
    val showModelSheet: Boolean = false,
    val enabledTools: Set<String> = emptySet(),
    val mcpServers: List<McpServerDisplayData> = emptyList(),
    val selectedMcpServerNames: Set<String> = emptySet(),
    /**
     * Whether the detected backend is v0.8.5+ and therefore accepts the `xhigh`
     * and `max` values in the reasoning-effort and effort dropdowns. False on
     * older or unknown servers (per VERSION_GATES.md guideline #2).
     */
    val extendedEffortSupported: Boolean = false,
)
