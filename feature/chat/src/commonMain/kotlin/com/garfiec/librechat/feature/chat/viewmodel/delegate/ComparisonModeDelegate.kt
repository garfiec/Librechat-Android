package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ComparisonState
import com.garfiec.librechat.feature.chat.viewmodel.resolveEndpointDispatch
import kotlinx.coroutines.launch

/**
 * Owns model-comparison mode end to end: selecting the secondary model, building its
 * [AddedConversation] request, and routing the dual SSE streams into the primary/secondary
 * panes of [ComparisonState]. Keeps the comparison's 3-way streaming fan-out out of
 * `ChatViewModel.handleStreamEvent` — callers funnel streaming events through [routeEvent],
 * which returns `true` once it has consumed an event for comparison mode.
 *
 * The two [StringBuilder] buffers accumulate each pane's text independently because the
 * server interleaves both agents' deltas on one stream (distinguished via [isSecondaryEvent]).
 */
class ComparisonModeDelegate(
    private val stateHandle: ChatStateHandle,
    private val messageRepository: MessageRepository,
    /** Reloads the conversation from the server after branching (VM-owned Room observer). */
    private val reloadConversation: (String) -> Unit,
) {

    private val primaryBuffer = StringBuilder()
    private val secondaryBuffer = StringBuilder()

    /** Accumulated primary-pane text — used as the auto-read source on Final. */
    fun primaryContent(): String = primaryBuffer.toString()

    // ── Selection ────────────────────────────────────────────────────

    /** Toggles comparison mode on/off, inheriting the primary endpoint/model when enabling. */
    fun toggleComparison() {
        val current = stateHandle.state.comparisonState
        if (current.isEnabled) {
            primaryBuffer.clear()
            secondaryBuffer.clear()
            stateHandle.update { copy(comparisonState = ComparisonState()) }
        } else {
            stateHandle.update {
                copy(
                    comparisonState = ComparisonState(
                        isEnabled = true,
                        secondaryEndpoint = selectedEndpoint,
                        secondaryModel = selectedModel,
                    ),
                    // When enabling on LANDING, switch to ACTIVE so comparison tabs render
                    screenState = if (screenState == ChatScreenState.LANDING) ChatScreenState.ACTIVE else screenState,
                )
            }
        }
    }

    /** Updates the secondary model selection for comparison mode. */
    fun setSecondaryModel(endpoint: String, model: String) {
        val comparison = stateHandle.state.comparisonState
        if (!comparison.isEnabled) return
        stateHandle.update {
            copy(comparisonState = comparison.copy(secondaryEndpoint = endpoint, secondaryModel = model))
        }
    }

    /** Resolves a display-friendly name for the secondary model. */
    fun getSecondaryModelDisplayName(): String? {
        val comparison = stateHandle.state.comparisonState
        val endpoint = comparison.secondaryEndpoint ?: return null
        val model = comparison.secondaryModel ?: return null
        return if (endpoint == EndpointConstants.AGENTS) {
            stateHandle.state.agents.find { it.id == model }?.name ?: model
        } else {
            model
        }
    }

    /**
     * Builds an [AddedConversation] for the secondary agent/model in comparison mode.
     * Returns null if comparison is not enabled or secondary selection is incomplete.
     *
     * Reads the per-endpoint user-provided-key state out of `ChatUiState.endpointKeyStates`
     * (populated by `EndpointKeyStatusDelegate`) instead of issuing a per-call
     * `getKeyExpiry` GET — keeps the chat-send hot path off the network.
     */
    fun buildAddedConvo(parentMessageId: String? = null): AddedConversation? {
        val state = stateHandle.state
        val comparison = state.comparisonState
        if (!comparison.isEnabled) return null
        val endpoint = comparison.secondaryEndpoint ?: return null
        val model = comparison.secondaryModel ?: return null
        val isAgent = endpoint == EndpointConstants.AGENTS
        val dispatch = resolveEndpointDispatch(
            endpointName = endpoint,
            endpointConfigs = state.endpointConfigs,
            endpointKeyStates = state.endpointKeyStates,
        )
        return AddedConversation(
            conversationId = state.conversationId,
            parentMessageId = parentMessageId,
            endpoint = endpoint,
            endpointType = dispatch.endpointType,
            modelDisplayLabel = dispatch.modelDisplayLabel,
            key = dispatch.key,
            agentId = if (isAgent) model else null,
            model = if (isAgent) null else model,
        )
    }

    // ── Streaming lifecycle ──────────────────────────────────────────

    /**
     * Resets both panes' buffers and streaming state at the start of a comparison send.
     * No-ops when comparison is disabled, so callers can invoke it unconditionally.
     */
    fun onSendStart() {
        if (!stateHandle.state.comparisonState.isEnabled) return
        primaryBuffer.clear()
        secondaryBuffer.clear()
        stateHandle.update {
            copy(
                comparisonState = comparisonState.copy(
                    primaryIsStreaming = true,
                    secondaryIsStreaming = true,
                    primaryStreamingContent = "",
                    secondaryStreamingContent = "",
                    primaryActiveToolCalls = emptyList(),
                    secondaryActiveToolCalls = emptyList(),
                    primaryAgentId = null,
                    secondaryAgentId = null,
                    parallelMessageId = null,
                    primaryFinalContent = null,
                    secondaryFinalContent = null,
                ),
            )
        }
    }

    /**
     * Routes a streaming event into the comparison panes. Returns `true` if the event
     * was consumed for comparison mode (so the caller skips its normal handling), `false`
     * otherwise — including when comparison is disabled, leaving the standard path intact.
     */
    fun routeEvent(event: StreamEvent): Boolean {
        if (!stateHandle.state.comparisonState.isEnabled) return false
        return when (event) {
            // Thinking and content deltas both feed the same pane buffer in comparison mode.
            is StreamEvent.ContentDelta -> { routeTextDelta(event.agentId, event.chunk); true }
            is StreamEvent.ThinkingDelta -> { routeTextDelta(event.agentId, event.chunk); true }
            is StreamEvent.ToolCallStart -> { routeToolCallStart(event); true }
            is StreamEvent.ToolCallComplete -> { routeToolCallComplete(event); true }
            else -> false
        }
    }

    private fun routeTextDelta(agentId: String?, chunk: String) {
        if (isSecondaryEvent(agentId)) {
            secondaryBuffer.append(chunk)
            stateHandle.update {
                copy(
                    comparisonState = comparisonState.copy(
                        secondaryStreamingContent = secondaryBuffer.toString(),
                        secondaryIsStreaming = true,
                        secondaryAgentId = comparisonState.secondaryAgentId ?: agentId,
                    ),
                )
            }
        } else {
            primaryBuffer.append(chunk)
            stateHandle.update {
                copy(
                    comparisonState = comparisonState.copy(
                        primaryStreamingContent = primaryBuffer.toString(),
                        primaryIsStreaming = true,
                        primaryAgentId = comparisonState.primaryAgentId ?: agentId,
                    ),
                    streamingContent = primaryBuffer.toString(),
                )
            }
        }
    }

    private fun routeToolCallStart(event: StreamEvent.ToolCallStart) {
        val newToolCall = ActiveToolCall(id = event.toolCallId, name = event.toolName, input = event.input)
        if (isSecondaryEvent(event.agentId)) {
            stateHandle.update {
                copy(
                    comparisonState = comparisonState.copy(
                        secondaryActiveToolCalls = comparisonState.secondaryActiveToolCalls + newToolCall,
                    ),
                )
            }
        } else {
            stateHandle.update {
                copy(
                    comparisonState = comparisonState.copy(
                        primaryActiveToolCalls = comparisonState.primaryActiveToolCalls + newToolCall,
                    ),
                )
            }
        }
    }

    private fun routeToolCallComplete(event: StreamEvent.ToolCallComplete) {
        if (isSecondaryEvent(event.agentId)) {
            stateHandle.update {
                val updated = comparisonState.secondaryActiveToolCalls.map { tc ->
                    if (tc.id == event.toolCallId) tc.copy(isComplete = true, output = event.output) else tc
                }
                copy(comparisonState = comparisonState.copy(secondaryActiveToolCalls = updated))
            }
        } else {
            stateHandle.update {
                val updated = comparisonState.primaryActiveToolCalls.map { tc ->
                    if (tc.id == event.toolCallId) tc.copy(isComplete = true, output = event.output) else tc
                }
                copy(comparisonState = comparisonState.copy(primaryActiveToolCalls = updated))
            }
        }
    }

    /**
     * Clears both panes' streaming flags and active tool calls. No-ops when comparison
     * is disabled. Pass [clearContent] = true to also wipe the in-progress pane text
     * (stream stop / abort); the default preserves it so an errored stream's partial
     * panes stay readable.
     */
    fun endStreaming(clearContent: Boolean = false) {
        if (!stateHandle.state.comparisonState.isEnabled) return
        stateHandle.update {
            copy(
                comparisonState = comparisonState.copy(
                    primaryIsStreaming = false,
                    secondaryIsStreaming = false,
                    primaryActiveToolCalls = emptyList(),
                    secondaryActiveToolCalls = emptyList(),
                    primaryStreamingContent = if (clearContent) "" else comparisonState.primaryStreamingContent,
                    secondaryStreamingContent = if (clearContent) "" else comparisonState.secondaryStreamingContent,
                ),
            )
        }
    }

    /**
     * Finalizes both panes on a successful Final: freezes each buffer as the pane's final
     * content and records the parallel response message id for branch-from-comparison.
     */
    fun onFinal(parallelMessageId: String?) {
        stateHandle.update {
            copy(
                comparisonState = comparisonState.copy(
                    primaryIsStreaming = false,
                    secondaryIsStreaming = false,
                    primaryStreamingContent = "",
                    secondaryStreamingContent = "",
                    primaryActiveToolCalls = emptyList(),
                    secondaryActiveToolCalls = emptyList(),
                    parallelMessageId = parallelMessageId,
                    primaryFinalContent = primaryBuffer.toString(),
                    secondaryFinalContent = secondaryBuffer.toString(),
                ),
            )
        }
    }

    /**
     * Branches the conversation onto the chosen agent's response, disables comparison,
     * and reloads so the branched message surfaces. Triggers deferred navigation for new
     * chats that skipped it during comparison.
     */
    fun branchFromComparison(agentId: String) {
        val messageId = stateHandle.state.comparisonState.parallelMessageId ?: return
        val conversationId = stateHandle.state.conversationId ?: return
        stateHandle.scope.launch {
            try {
                messageRepository.branchMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    agentId = agentId,
                )
                stateHandle.update { copy(comparisonState = ComparisonState()) }
                val cid = stateHandle.state.conversationId
                if (cid != null && stateHandle.state.pendingNavigationConversationId == null) {
                    stateHandle.update { copy(pendingNavigationConversationId = cid) }
                }
                reloadConversation(conversationId)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to branch comparison message" }
                stateHandle.update { copy(error = "Failed to continue with selected response") }
            }
        }
    }

    /**
     * Determines whether a stream event belongs to the secondary (added) agent.
     *
     * The server gives both agents the same `groupId` (they share a parallel
     * execution group), so groupId alone cannot distinguish them. Instead, the
     * server suffixes added-agent IDs with `"____N"` (e.g. `openAI__gpt-5.2____1`).
     * The primary never has this suffix.
     */
    fun isSecondaryEvent(agentId: String?): Boolean {
        if (agentId == null) return false
        val comparison = stateHandle.state.comparisonState
        // If we've already resolved the secondary agentId from earlier SSE events, use that
        if (comparison.secondaryAgentId != null) {
            return agentId == comparison.secondaryAgentId
        }
        // The "____N" suffix identifies the addedConvo (added/secondary) agent.
        return agentId.contains("____")
    }
}
