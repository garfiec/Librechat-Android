package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.model.request.EphemeralAgent

/**
 * Builds the per-send request pieces shared by every chat-send path (new message, edit,
 * regenerate, continue): the endpoint dispatch for the active selection and the optional
 * [EphemeralAgent] derived from the current ephemeral tool/MCP selections.
 *
 * Pure reads over [ChatStateHandle.state] — no coroutines, no repositories — so it can be
 * unit-tested directly. Extracted so both `ChatViewModel`'s send path and
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.MessageEditingDelegate] share one
 * implementation instead of duplicating it.
 */
class ChatRequestBuilder(
    private val stateHandle: ChatStateHandle,
) {

    /**
     * Resolves the chat-send dispatch for the currently-selected endpoint by snapshotting
     * state once. Callers that target a different endpoint (e.g. the comparison-mode
     * secondary) must call [resolveEndpointDispatch] directly with that endpoint name.
     */
    fun currentDispatch(): EndpointDispatch {
        val state = stateHandle.state
        return resolveEndpointDispatch(
            endpointName = state.selectedEndpoint,
            endpointConfigs = state.endpointConfigs,
            endpointKeyStates = state.endpointKeyStates,
        )
    }

    /**
     * Builds an [EphemeralAgent] from the current UI state (selected MCP servers and
     * enabled tools). Returns null when there is nothing to send.
     */
    fun buildEphemeralAgent(): EphemeralAgent? {
        val state = stateHandle.state
        // A saved agent uses its own configured tools; the backend ignores client-supplied
        // ephemeral tools for agent runs. Mirror web's `showEphemeralBadges` (ChatForm.tsx)
        // and never serialize leftover UI selections on the agents endpoint.
        if (!state.showEphemeralTools) return null
        val mcpServers = state.selectedMcpServerNames.toList().ifEmpty { null }
        val enabledTools = state.enabledTools
        val webSearchEnabled = state.modelParameters.webSearch

        val hasAnything = mcpServers != null || enabledTools.isNotEmpty() || webSearchEnabled
        if (!hasAnything) return null

        return EphemeralAgent(
            mcp = mcpServers,
            webSearch = if (webSearchEnabled) true else null,
            fileSearch = if (ToolConstants.FILE_SEARCH in enabledTools) true else null,
            executeCode = if (ToolConstants.CODE_INTERPRETER in enabledTools || ToolConstants.EXECUTE_CODE in enabledTools) true else null,
        )
    }
}
