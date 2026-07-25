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
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
     * The turn config the paused run was STARTED with, not the one selected when the user decides.
     *
     * The resume route recomputes the request fingerprint (endpoint / endpointType / agent_id /
     * model / spec / promptPrefix / ephemeralAgent) and 403s anything that does not match the run
     * it is resuming. So this has to be the config that was actually sent, which is why it is
     * pinned from the send spec at stream start ([onTurnStarted]) rather than read off the UI:
     * a queued follow-up drains with the spec it was composed with, and everything the composer
     * feeds the fingerprint — model, tools, MCP servers — stays live while the run generates and
     * while the pause card waits for a decision.
     */
    private var pinnedTurn: PinnedTurnConfig? = null

    private data class PinnedTurnConfig(
        val endpoint: String,
        val endpointType: String?,
        val agentId: String?,
        val model: String?,
        /**
         * The `promptPrefix` ("Custom Instructions") the send POST carried at the TOP LEVEL of its
         * body, spread there out of the model-parameter payload
         * ([com.garfiec.librechat.core.data.repository.ChatPayloadBuilder.toBody]). The fingerprint
         * hashes the RAW body field, so it has to be read back from that same payload — reading the
         * live composer instead would pin whatever the sheet holds when the user decides.
         *
         * Null whenever the send omitted the key: nothing customized, or a wire key that is not
         * `promptPrefix` (bedrock-anthropic sends `system`, which the fingerprint does not cover).
         *
         * `spec` is the fingerprint's one remaining field with no pin here, and cannot be sent by
         * this client: nothing populates [com.garfiec.librechat.core.model.request.ChatRequest.spec],
         * so both bodies hash it as null. Pin it here the moment a send path starts setting it.
         */
        val promptPrefix: String?,
        val ephemeralAgent: EphemeralAgent?,
        val isTemporary: Boolean,
    )

    /**
     * Pins the config a turn is being sent with, at stream start.
     *
     * [spec] is the send spec that turn dispatched (null for edit / regenerate / continue, which
     * build their request from the current selection at this same moment, and for a stream this
     * client only reconnected to — there the live selection, restored from the conversation, is
     * the best available guess).
     *
     * Must run AFTER the session boundary that calls [clear], or the new turn's pin is wiped.
     */
    fun onTurnStarted(spec: QueuedMessage?) {
        pinnedTurn = spec?.toPinnedTurn() ?: captureTurnConfig()
    }

    /** Records a newly-announced pause. Falls back to the live config if no turn pinned one. */
    fun onPendingAction(pendingAction: PendingAction) {
        if (pinnedTurn == null) pinnedTurn = captureTurnConfig()
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
                    promptPrefix = turn.promptPrefix,
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

    private fun QueuedMessage.toPinnedTurn() = PinnedTurnConfig(
        endpoint = endpoint,
        endpointType = dispatch.endpointType,
        agentId = agentId.takeIf { endpoint == EndpointConstants.AGENTS },
        model = model,
        promptPrefix = modelParamsPayload.promptPrefix(),
        ephemeralAgent = ephemeralAgent,
        isTemporary = isTemporary,
    )

    private fun captureTurnConfig(): PinnedTurnConfig {
        val state = handle.state
        val dispatch = requestBuilder.currentDispatch()
        val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
        return PinnedTurnConfig(
            endpoint = state.selectedEndpoint,
            endpointType = dispatch.endpointType,
            agentId = if (isAgent) state.selectedModel else null,
            model = state.selectedModel,
            promptPrefix = requestBuilder.buildModelParams().promptPrefix(),
            ephemeralAgent = requestBuilder.buildEphemeralAgent(),
            isTemporary = state.isTemporaryChat,
        )
    }

    /** Reads the fingerprinted `promptPrefix` back out of a model-parameter payload. */
    private fun JsonObject?.promptPrefix(): String? =
        (this?.get(PROMPT_PREFIX_KEY) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val PROMPT_PREFIX_KEY = "promptPrefix"
    }
}
