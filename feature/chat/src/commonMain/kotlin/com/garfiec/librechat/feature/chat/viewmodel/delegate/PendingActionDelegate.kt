package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.request.ToolApprovalResolution
import com.garfiec.librechat.feature.chat.viewmodel.ChatRequestBuilder
import com.garfiec.librechat.feature.chat.viewmodel.PendingActionHandle
import kotlinx.coroutines.launch

/**
 * Owns the human-in-the-loop pause (v0.8.8): holding the live [PendingAction] and resolving it
 * through `POST /api/agents/chat/resume`.
 *
 * A paused run is still the stream's owner — the SSE connection stays open and no `final` frame
 * arrives until the pause resolves — so this delegate never starts or ends a stream. It only
 * flips the pause fields; the resumed continuation flows back through the events
 * [StreamingManagerDelegate] is already collecting.
 */
class PendingActionDelegate(
    private val handle: PendingActionHandle,
    private val chatRepository: ChatRepository,
    private val requestBuilder: ChatRequestBuilder,
    /** Surfaces a resume rejection as user-facing copy; the raw server message is not localized. */
    private val resumeFailureMessage: (String?) -> String,
) {

    /**
     * The turn config pinned when the pause ARRIVED, not when the user decides.
     *
     * The resume route recomputes the request fingerprint (endpoint / endpointType / agent_id /
     * model / spec / promptPrefix / ephemeralAgent) and 403s a mismatch against the one captured
     * at pause time. A pause can sit for minutes while the user reads it, and the model picker
     * stays live throughout — resolving against the CURRENT selection would then be rejected by a
     * server that is, correctly, refusing to resume someone else's agent. Capturing on arrival
     * closes all but the sub-second window between the model switch and the pause frame.
     */
    private var pinnedTurn: PinnedTurnConfig? = null

    private data class PinnedTurnConfig(
        val endpoint: String,
        val endpointType: String?,
        val agentId: String?,
        val model: String?,
        val ephemeralAgent: EphemeralAgent?,
        val isTemporary: Boolean,
    )

    /** Records a newly-announced pause and pins the config needed to resume it. */
    fun onPendingAction(pendingAction: PendingAction) {
        pinnedTurn = captureTurnConfig()
        handle.update {
            this.pendingAction = pendingAction
            isResolvingPendingAction = false
        }
    }

    /**
     * Drops any pause without resolving it. Called at every stream end: whatever ended the run
     * (final frame, error, abort, expiry) has already made the pause unresolvable, and leaving
     * the card up would offer controls that can only 409.
     */
    fun clear() {
        pinnedTurn = null
        if (handle.state.pendingAction == null && !handle.state.isResolvingPendingAction) return
        handle.update {
            pendingAction = null
            isResolvingPendingAction = false
        }
    }

    /** Resolves a `tool_approval` pause with one decision per paused tool call. */
    fun submitToolDecisions(decisions: List<ToolApprovalResolution>) {
        submit { request -> request.copy(decisions = decisions) }
    }

    /** Resolves an `ask_user_question` pause with the user's reply. */
    fun submitAnswer(answer: String) {
        submit { request -> request.copy(answer = answer) }
    }

    /**
     * Posts the decision and clears the pause on success.
     *
     * The pause is cleared only after the server accepts it. Clearing optimistically would, on a
     * 409 (someone else resolved it, or it expired), leave the run with no controls and no way
     * back to them — the card is the only route to a resume.
     */
    private fun submit(withDecision: (ChatResumeRequest) -> ChatResumeRequest) {
        val state = handle.state
        val action = state.pendingAction ?: return
        val actionId = action.actionId ?: return
        val conversationId = state.conversationId ?: action.conversationId ?: action.streamId ?: return
        if (state.isResolvingPendingAction) return
        val turn = pinnedTurn ?: captureTurnConfig()

        handle.update { isResolvingPendingAction = true }
        handle.scope.launch {
            val request = withDecision(
                ChatResumeRequest(
                    conversationId = conversationId,
                    actionId = actionId,
                    endpoint = turn.endpoint,
                    endpointType = turn.endpointType,
                    agentId = turn.agentId,
                    model = turn.model,
                    ephemeralAgent = turn.ephemeralAgent,
                    isTemporary = turn.isTemporary.takeIf { it },
                ),
            )
            when (val result = chatRepository.resumeChat(request)) {
                is Result.Success -> {
                    pinnedTurn = null
                    handle.update {
                        pendingAction = null
                        isResolvingPendingAction = false
                    }
                }
                is Result.Error -> {
                    Logger.w(result.exception) { "Failed to resume paused run: ${result.message}" }
                    handle.update {
                        isResolvingPendingAction = false
                        error = resumeFailureMessage(result.message)
                    }
                }
                is Result.Loading -> handle.update { isResolvingPendingAction = false }
            }
        }
    }

    private fun captureTurnConfig(): PinnedTurnConfig {
        val state = handle.state
        val dispatch = requestBuilder.currentDispatch()
        val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
        return PinnedTurnConfig(
            endpoint = state.selectedEndpoint,
            endpointType = dispatch.endpointType,
            agentId = if (isAgent) state.selectedModel else null,
            model = state.selectedModel,
            ephemeralAgent = requestBuilder.buildEphemeralAgent(),
            isTemporary = state.isTemporaryChat,
        )
    }
}
