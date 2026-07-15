package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.getOrNull
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.model.request.ContextProjectionRequest
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ContextProjectionHandle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the context-usage gauge's projection path. Watches the viewed branch tail + model/endpoint
 * and asks the server to project the next call's context window so the gauge can show on a loaded
 * or snapshot-less branch (v0.8.7). Read-only over chat state — it only sets
 * [ChatUiState.contextUsage]; it never touches messages/branches/Room, so it can't disturb the
 * streaming-anchor invariant. The live `on_context_usage` SSE event owns the gauge during a stream,
 * so [refreshContextProjection] no-ops while [ChatUiState.isStreaming].
 */
class ContextProjectionDelegate(
    private val handle: ContextProjectionHandle,
    private val agentRepository: AgentRepository,
    private val endpointTokenRepository: EndpointTokenRepository,
) {

    /** Last seen projection key, to detect a real window change vs. a tail-only change. */
    private var previousProjectionKey: ProjectionKey? = null

    /**
     * The last value this delegate itself wrote to the gauge. Lets the tail-advance path tell a
     * stale projection (ours) from a fresh live `on_context_usage` reading (someone else's): if the
     * gauge no longer equals what we projected, the just-completed stream delivered an exact reading
     * and we must not overwrite it with an estimate.
     */
    private var lastProjectedUsage: ContextUsage? = null

    /**
     * Resolved (provider, model) per agentId, cached for the session so the per-window projection
     * refresh doesn't re-hit the network each time. The list that fills [ChatUiState.agents] comes
     * from `GET /api/agents`, which strips `provider`/`model` (both come back null) — so the agent's
     * real model is only obtainable from the per-agent detail endpoint.
     */
    private val resolvedAgentModels = mutableMapOf<String, Pair<String, String?>>()

    /**
     * Starts the projection observer. Launches a `Main.immediate` collector on the state flow,
     * whose current value replays synchronously — on the agents endpoint that first emission reaches
     * [resolveProjectionModel], so [resolvedAgentModels] is already initialized by then.
     */
    fun start() {
        handle.scope.launch {
            handle.stateFlow
                .map {
                    ProjectionKey(
                        conversationId = it.conversationId,
                        tailMessageId = it.displayMessages.lastOrNull()?.message?.messageId,
                        endpoint = it.selectedEndpoint,
                        model = it.selectedModel,
                        enabled = it.contextUsageEnabled,
                    )
                }
                .distinctUntilChanged()
                .collect { key ->
                    val prev = previousProjectionKey
                    // A window change (conversation/endpoint/model) makes the old gauge irrelevant, so
                    // clear it — the previous window's value must not linger while the new projection
                    // loads. Gated off mid-stream: the live SSE owns the gauge then and clearing would
                    // kill the moving readout (begin-stream lands the new tail with isStreaming already
                    // true, so this is skipped until the Final emission).
                    if (prev != null && !prev.sameWindowAs(key) && !handle.state.isStreaming) {
                        handle.update { contextUsage = null }
                    }
                    // Force a re-projection when the tail advances (a turn completed) so the gauge
                    // tracks the growing conversation: without this the `contextUsage != null` guard
                    // freezes it at its load-time value on backends that don't stream `on_context_usage`.
                    // Forcing overwrites in place on success and never blanks, so the gauge refreshes
                    // without flickering between turns. contextUsage is not part of [ProjectionKey], so
                    // the write below can't re-trigger this collector.
                    //
                    // But only force when the gauge is still our own stale projection. Backends that
                    // DO stream `on_context_usage` set an exact reading during the stream, so at Final
                    // the gauge no longer equals what we last projected — forcing there would replace
                    // that exact reading with an estimate AND fire a redundant projection every turn.
                    // (contextUsage == null means it was just cleared for a new window; force is a
                    // no-op over null, so the condition still lets the fresh window project.)
                    val tailAdvanced = prev != null && prev.tailMessageId != key.tailMessageId
                    val gaugeIsStaleProjection =
                        handle.state.contextUsage == null || handle.state.contextUsage == lastProjectedUsage
                    previousProjectionKey = key
                    refreshContextProjection(force = tailAdvanced && gaugeIsStaleProjection)
                }
        }
    }

    private data class ProjectionKey(
        val conversationId: String?,
        val tailMessageId: String?,
        val endpoint: String,
        val model: String?,
        // Not read directly — it exists only so the generated `equals` re-fires the collector when
        // loadFlags flips `contextUsageEnabled` false->true after construction (the gate is enforced
        // in refreshContextProjection). Keep it in the key; dropping it silently loses that emission.
        val enabled: Boolean,
    ) {
        /** True when the projected window (denominator: conversation/endpoint/model, not the
         *  streaming tail) is unchanged — a tail-only move keeps the same window. */
        fun sameWindowAs(other: ProjectionKey) =
            conversationId == other.conversationId &&
                endpoint == other.endpoint &&
                model == other.model
    }

    /**
     * Resolves the (endpoint, model) used both to look up the context window and to address the
     * projection. For the agents endpoint [ChatUiState.selectedModel] is the agentId — not a
     * token-config key — so substitute the selected agent's real provider/model (web does the same
     * via useTokenLimits). Everything else passes through unchanged.
     *
     * The list-backed [ChatUiState.agents] entry has a null model (the list endpoint strips it), so
     * fall back to the agent detail (`/api/agents/:id/expanded`, same AGENTS.USE gate as using the
     * agent) to get the real model, cached per agentId.
     */
    private suspend fun resolveProjectionModel(state: ChatUiState): Pair<String, String?> {
        if (state.selectedEndpoint != EndpointConstants.AGENTS) {
            return state.selectedEndpoint to state.selectedModel
        }
        val agentId = state.selectedModel ?: return state.selectedEndpoint to null
        // Honor a fully-populated list entry if one ever has a model; otherwise use the cache.
        state.agents.firstOrNull { it.id == agentId && it.model != null }?.let {
            return (it.provider ?: state.selectedEndpoint) to it.model
        }
        resolvedAgentModels[agentId]?.let { return it }
        // getAgent short-circuits to the stripped list cache, so fetch the detail explicitly.
        val detail = (agentRepository.getAgentForEditing(agentId) as? Result.Success)?.data
        val resolved = (detail?.provider ?: state.selectedEndpoint) to detail?.model
        // Only cache a real resolution; a transient failure should be retryable next window.
        if (detail?.model != null) resolvedAgentModels[agentId] = resolved
        return resolved
    }

    /**
     * Resolves the model's context window from token-config (the gauge's denominator).
     * token-config is memoized on the singleton [endpointTokenRepository], so this fetches
     * over the network only once per session even though each chat gets its own ViewModel.
     */
    private suspend fun resolveMaxContextTokens(endpoint: String, model: String?): Int? {
        if (model == null) return null
        val config = endpointTokenRepository.getTokenConfig().getOrNull() ?: return null
        return config[endpoint]?.get(model)?.context
    }

    private suspend fun refreshContextProjection(force: Boolean = false) {
        val state = handle.state
        if (!state.contextUsageEnabled) return
        // The live SSE snapshot owns the gauge mid-stream; don't fight it with a projection.
        if (state.isStreaming) return
        // Normally never re-project over an existing snapshot: a populated gauge already reflects the
        // live `on_context_usage` SSE (or a prior projection) for this branch, and re-projecting would
        // replace an actual reading with an estimate. A forced refresh (the tail advanced) overrides
        // that — the snapshot is stale for the new tail — and still only overwrites on a successful
        // projection below, so it never blanks the gauge. Mirrors the web client's `branchSnapshot ==
        // null` gate (useTokenUsage.ts), extended to refresh once per completed turn.
        if (!force && state.contextUsage != null) return
        val conversationId = state.conversationId ?: return
        val messageId = state.displayMessages.lastOrNull()?.message?.messageId ?: return
        // For the agents endpoint, selectedModel is the agentId — resolve the agent's real
        // provider/model so the token-config lookup and the projection target a real model.
        val (lookupEndpoint, lookupModel) = resolveProjectionModel(state)
        // The window is required: upstream `resolveContextProjection` returns null when
        // `maxContextTokens <= 0`, so without it the projection always no-ops. Resolve it from
        // token-config (web does the same), preferring any explicit per-conversation override.
        val maxContextTokens = state.modelParameters.maxContextTokens?.takeIf { it > 0 }
            ?: resolveMaxContextTokens(lookupEndpoint, lookupModel)
            ?: run {
                // No known context window (model absent from token-config, e.g. a custom/proxy/
                // self-hosted model) ⇒ no denominator ⇒ no ratio, so the gauge stays hidden. This
                // is intentional, but log it so the silent no-render is diagnosable.
                Logger.d {
                    "Context gauge skipped: no known context window for " +
                        "endpoint=$lookupEndpoint model=$lookupModel"
                }
                return
            }
        // For agents, address the projection by agentId (the server resolves the agent's config)
        // and send the resolved real model; otherwise pass the selected model directly.
        val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
        val result = endpointTokenRepository.getContextProjection(
            ContextProjectionRequest(
                conversationId = conversationId,
                messageId = messageId,
                endpoint = state.selectedEndpoint,
                model = lookupModel,
                agentId = if (isAgent) state.selectedModel else null,
                maxContextTokens = maxContextTokens,
            ),
        )
        // Only seed when the server actually returned a snapshot. On null/error, leave the gauge
        // as-is; the next turn's SSE refreshes it.
        val usage = (result as? Result.Success)?.data ?: return
        // Re-check after the network round-trip: a stream may have started while we were awaiting
        // the projection. Once streaming, the live `on_context_usage` SSE owns the gauge, so a
        // now-stale projection (especially a forced one) must not clobber the fresh reading.
        if (handle.state.isStreaming) return
        lastProjectedUsage = usage
        handle.update { contextUsage = usage }
    }
}
