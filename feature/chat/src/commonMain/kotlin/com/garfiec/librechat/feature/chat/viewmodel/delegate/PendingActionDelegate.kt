package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ResumePinStore
import com.garfiec.librechat.core.data.repository.ResumeTurnPin
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.request.ToolApprovalResolution
import com.garfiec.librechat.feature.chat.viewmodel.ChatRequestBuilder
import com.garfiec.librechat.feature.chat.viewmodel.PendingActionHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/**
 * Owns the human-in-the-loop pause (v0.8.8): holding the live [PendingAction] and resolving it
 * through `POST /api/agents/chat/resume`.
 *
 * A paused run is still the stream's owner — the SSE connection stays open and no `final` frame
 * arrives until the pause resolves — so this delegate never starts or ends a stream. It only
 * flips the pause fields; the resumed continuation flows back through the events
 * [StreamingManagerDelegate] is already collecting.
 *
 * **An `ask_user_question` answer is the user's own text, so it falls under the same rule as a
 * steer: no path may lose it.** [ChatViewModel.sendDuringRun] clears the composer before posting,
 * which means the words exist nowhere else while the resume is in flight — so every way this can
 * fail (rejection, transport error, or the pause being cleared out from under the POST) hands
 * them back through [restoreAnswer]. Tool decisions carry no prose and need none of this.
 */
class PendingActionDelegate(
    private val handle: PendingActionHandle,
    private val chatRepository: ChatRepository,
    private val requestBuilder: ChatRequestBuilder,
    /** Surfaces a resume rejection as user-facing copy; the raw server message is not localized. */
    private val resumeFailureMessage: (String?) -> String,
    /**
     * Copy for the one rejection the user cannot act on by retrying: the server recomputed the
     * request fingerprint and it did not match the run being resumed, so this client cannot
     * answer that pause at all. Distinct from [resumeFailureMessage] because the raw server text
     * ("Forbidden") tells the user nothing about what to do next.
     */
    private val fingerprintRejectedMessage: () -> String,
    /**
     * Puts an unresolved answer back in the composer. See the class KDoc: the composer is cleared
     * before the POST, so this is the only thing standing between a failed resume and lost words.
     */
    private val restoreAnswer: (String) -> Unit,
    /**
     * Carries the started-with config across ViewModel boundaries. The ViewModel that resolves a
     * pause is often not the one that started the run — a handed-off new chat, or reopening a
     * conversation — and neither can rebuild the config from the conversation record.
     */
    private val resumePinStore: ResumePinStore,
    /**
     * Copy for a pause that expired before it was answered ([PendingAction.expiresAt] passed).
     * Distinct from [resumeFailureMessage]: the server has finalized the run, so "try again" is
     * exactly the wrong advice — there is nothing left to answer.
     */
    private val pauseExpiredMessage: () -> String = {
        "This request for input expired, so the response was finalized without it."
    },
    /** Injectable clock for the expiry check; production uses the system clock. */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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

    /** The live run's generation epoch; see [onGenerationEpoch]. */
    private var generationCreatedAt: Long? = null

    /**
     * The action id whose resume POST is in flight.
     *
     * The POST outlives the run: it can return after the pause it resolves is gone and a NEWER
     * run has announced its own. Writing `pendingAction = null` then would wipe the new run's
     * live pause and strand it with no controls. This and the [epoch] the POST was issued under —
     * captured as a local, so a second POST cannot overwrite the first one's copy — are both
     * checked before the continuation touches shared state.
     */
    private var inFlightActionId: String? = null

    /** Bumped whenever the pause state is invalidated ([clear]), so a stale POST can tell. */
    private var epoch: Int = 0

    /** Dismisses the card when [PendingAction.expiresAt] passes; see [scheduleExpiry]. */
    private var expiryJob: Job? = null

    /**
     * Composer-driven batch answers accumulated so far, keyed by question id; see
     * [answerNextBatchQuestion]. Valid only for [batchDraftActionId] — a new pause never
     * inherits a previous batch's words.
     */
    private val batchDrafts = linkedMapOf<String, String>()
    private var batchDraftActionId: String? = null

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
        persistPin()
    }

    /**
     * Publishes the current pin under [conversationId] so a later ViewModel can resume against it.
     *
     * Called again when the id is minted: a brand-new chat has no conversation id at turn start,
     * and that is exactly the case that hands off to a different ViewModel before the first pause
     * arrives.
     */
    fun onConversationIdResolved(conversationId: String) {
        persistPin(conversationId)
    }

    /**
     * Records the run's generation epoch, echoed back as `generationCreatedAt` on the resume so
     * the server can fence it against a newer run reusing the same stream id (v0.8.8-rc1,
     * 409 RUN_REPLACED on mismatch). Sourced from the start POST's envelope
     * ([com.garfiec.librechat.core.model.StreamEvent.Created]) and from `GET /chat/status`'s
     * `createdAt` on the reconnect paths — the same two places web feeds its
     * `activeGenerationCreatedAtByConvoId` atom. Null (older server / SSE-only created frame)
     * sends the resume unfenced, which stays legal.
     */
    fun onGenerationEpoch(createdAt: Long?) {
        generationCreatedAt = createdAt
    }

    private fun persistPin(conversationId: String? = handle.state.conversationId) {
        val pin = pinnedTurn ?: return
        val id = conversationId ?: return
        resumePinStore.put(id, pin.toStored())
    }

    /** Records a newly-announced pause. Falls back to the live config if no turn pinned one. */
    fun onPendingAction(pendingAction: PendingAction) {
        if (pinnedTurn == null) {
            // Store before guess: a ViewModel that did not start this run has nothing useful in
            // its live selection, and capturing one here would shadow the pin the starting
            // ViewModel published.
            val conversationId = handle.state.conversationId
                ?: pendingAction.conversationId
                ?: pendingAction.streamId
            pinnedTurn = resumePinStore.get(conversationId)?.toPinnedTurn() ?: captureTurnConfig()
        }
        // The SAME pause is announced repeatedly: the ~120s SSE stall reconnects and its sync
        // frame replays it, and a foreground resume re-reads it off /chat/status. If a submit is
        // in flight for that action, clearing the resolving flag re-arms the controls under the
        // user, who submits again and gets a 409 for a decision that actually succeeded.
        val isSameActionMidSubmit = handle.state.isResolvingPendingAction &&
            inFlightActionId != null &&
            inFlightActionId == pendingAction.actionId
        handle.update {
            this.pendingAction = pendingAction
            if (!isSameActionMidSubmit) isResolvingPendingAction = false
        }
        scheduleExpiry(pendingAction)
    }

    /**
     * Arms the expiry for a pause that carries [PendingAction.expiresAt] (v0.8.8: the server
     * treats the pause as stale past it and finalizes the run). When it passes, the card is
     * dismissed with the expiry copy instead of leaving controls up that can only 409 — the same
     * shape [clear] gives every other way a pause dies. A pause announced already-expired (a
     * stale status read) dismisses immediately. Re-announcements re-arm idempotently; a pause
     * without the field never expires client-side, exactly as before.
     */
    private fun scheduleExpiry(pendingAction: PendingAction) {
        expiryJob?.cancel()
        expiryJob = null
        val expiresAt = pendingAction.expiresAt ?: return
        val actionId = pendingAction.actionId ?: return
        expiryJob = handle.scope.launch {
            val wait = expiresAt - nowMillis()
            if (wait > 0) delay(wait)
            expireNow(actionId)
        }
    }

    /** Dismisses the pause as expired. Mirrors [clear]'s invalidation, plus the expiry copy. */
    private fun expireNow(actionId: String) {
        if (handle.state.pendingAction?.actionId != actionId) return
        // Also reached from the 409 mapping while the timer is still armed; cancelling from
        // inside the timer's own coroutine is safe (the remaining writes are non-suspending).
        expiryJob?.cancel()
        expiryJob = null
        reclaimBatchDrafts()
        pinnedTurn = null
        generationCreatedAt = null
        // Invalidate any in-flight resume: its continuation then restores the typed answer
        // (unless the POST actually landed) instead of re-arming a card that no longer exists.
        epoch++
        inFlightActionId = null
        handle.update {
            pendingAction = null
            isResolvingPendingAction = false
            error = pauseExpiredMessage()
        }
    }

    /**
     * Drops any pause without resolving it. Called at every stream end: whatever ended the run
     * (final frame, error, abort, expiry) has already made the pause unresolvable, and leaving
     * the card up would offer controls that can only 409.
     */
    fun clear() {
        expiryJob?.cancel()
        expiryJob = null
        reclaimBatchDrafts()
        pinnedTurn = null
        generationCreatedAt = null
        epoch++
        inFlightActionId = null
        if (handle.state.pendingAction == null && !handle.state.isResolvingPendingAction) return
        handle.update {
            pendingAction = null
            isResolvingPendingAction = false
        }
    }

    /** Resolves a `tool_approval` pause with one decision per paused tool call. */
    fun submitToolDecisions(decisions: List<ToolApprovalResolution>) {
        submit(answerText = null) { request -> request.copy(decisions = decisions) }
    }

    /** Resolves a single-question `ask_user_question` pause with the user's reply. */
    fun submitAnswer(answer: String) {
        submit(answerText = answer) { request -> request.copy(answer = answer) }
    }

    /**
     * Resolves a batched `ask_user_question` pause — one answer per question id.
     *
     * The route branches on the PAYLOAD, not on the body: a pause carrying `questions` rejects a
     * bare `answer` and requires this map to cover every id exactly. So the caller must key it
     * from the payload's own ids; anything the payload does not name is 400 "Answers contain an
     * unknown question id", and anything it names but this omits is 400 "Answers are required for
     * every question".
     *
     * Everything the user typed is joined into [answerText] for the restore-on-failure path, so a
     * rejected multi-question submit hands back all of it rather than the first field.
     */
    fun submitAnswers(answers: Map<String, String>) {
        if (answers.isEmpty()) return
        submit(answerText = answers.values.joinToString("\n\n")) { request ->
            request.copy(answers = answers)
        }
    }

    /**
     * The composer's send during a batched pause: answers the FIRST question that has no draft
     * yet, and submits the whole batch through [submitAnswers] once every question has one.
     *
     * There is deliberately no per-question resume: the route requires the answers map to cover
     * every id and 400s a partial submission, so a batch can only ever go up whole. Until it
     * does, the words live in [batchDrafts], and every way the pause can die without submitting —
     * stream end, expiry, a different pause replacing this one — hands them back through
     * [restoreAnswer] (see [reclaimBatchDrafts]); the same no-lost-words rule the single-answer
     * path follows.
     *
     * The card's per-question editors stay authoritative: its Send resolves the batch from its
     * own fields, and these drafts are then discarded by the success path clearing the pause.
     */
    fun answerNextBatchQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val state = handle.state
        if (state.isResolvingPendingAction) return
        val action = state.pendingAction ?: return
        val actionId = action.actionId ?: return
        val questions = action.payload?.questions ?: return
        if (questions.isEmpty() || questions.any { !it.isAnswerable }) return
        if (batchDraftActionId != actionId) {
            reclaimBatchDrafts()
            batchDraftActionId = actionId
        }
        val target = questions.firstOrNull { batchDrafts[it.id].isNullOrBlank() } ?: return
        batchDrafts[target.id] = trimmed
        if (questions.all { !batchDrafts[it.id].isNullOrBlank() }) {
            val complete = questions.associate { it.id to batchDrafts[it.id].orEmpty() }
            batchDrafts.clear()
            batchDraftActionId = null
            submitAnswers(complete)
        }
    }

    /** Hands unsubmitted batch drafts back to the composer; no-op when there are none. */
    private fun reclaimBatchDrafts() {
        if (batchDrafts.isNotEmpty()) {
            restoreAnswer(batchDrafts.values.joinToString("\n\n"))
        }
        batchDrafts.clear()
        batchDraftActionId = null
    }

    /**
     * Posts the decision and clears the pause on success.
     *
     * The pause is cleared only after the server accepts it. Clearing optimistically would, on a
     * 409 (someone else resolved it, or it expired), leave the run with no controls and no way
     * back to them — the card is the only route to a resume.
     *
     * [answerText] is the user's prose for an `ask_user_question` pause, null for tool decisions.
     * It is captured by the continuation rather than stored in a field precisely because [clear]
     * can run while this is in flight: the closure survives that, a field would have to be
     * cleaned up by the very code path that invalidates the pause.
     */
    private fun submit(
        answerText: String?,
        withDecision: (ChatResumeRequest) -> ChatResumeRequest,
    ) {
        val state = handle.state
        val action = state.pendingAction ?: return
        val actionId = action.actionId ?: return
        val conversationId = state.conversationId ?: action.conversationId ?: action.streamId ?: return
        if (state.isResolvingPendingAction) return
        // Order matters: this ViewModel's own pin, then one published by the ViewModel that
        // actually started the run, and only then a guess from the live selection. The guess
        // cannot reproduce ephemeralAgent or promptPrefix, so it 403s on any run started with a
        // tool, an MCP server or custom instructions.
        val pinned = pinnedTurn ?: resumePinStore.get(conversationId)?.toPinnedTurn()
        // No pin anywhere means the run was started by a process that is gone (ResumePinStore is
        // deliberately in-memory), so the fingerprint below is a GUESS off the live selection. It
        // matches for a plain run and cannot match one started with tools, an MCP server or custom
        // instructions — the server 403s those. Logged so a 403 in the field is attributable.
        if (pinned == null) Logger.d { "Resuming with a guessed fingerprint; no pin for $conversationId" }
        val turn = pinned ?: captureTurnConfig()

        handle.update { isResolvingPendingAction = true }
        inFlightActionId = actionId
        val issuedEpoch = epoch
        handle.scope.launch {
            val request = withDecision(
                ChatResumeRequest(
                    conversationId = conversationId,
                    actionId = actionId,
                    generationCreatedAt = generationCreatedAt,
                    endpoint = turn.endpoint,
                    endpointType = turn.endpointType,
                    agentId = turn.agentId,
                    model = turn.model,
                    promptPrefix = turn.promptPrefix,
                    ephemeralAgent = turn.ephemeralAgent,
                    isTemporary = turn.isTemporary.takeIf { it },
                ),
            )
            val result = chatRepository.resumeChat(request)
            // The pause this POST resolves is gone and something else owns the state now — a
            // newer run may have announced its own pause. Writing here would wipe it.
            if (issuedEpoch != epoch) {
                Logger.d { "Resume answered after its pause was cleared; dropping the result" }
                // The shared pause fields are off limits, but the words are not the pause. Give
                // them back UNLESS the POST actually landed, in which case the run already has
                // them and restoring would invite the user to send the same thing twice.
                if (result !is Result.Success) answerText?.let(restoreAnswer)
                return@launch
            }
            inFlightActionId = null
            when (result) {
                is Result.Success -> {
                    pinnedTurn = null
                    resumePinStore.remove(conversationId)
                    handle.update {
                        pendingAction = null
                        isResolvingPendingAction = false
                    }
                }
                is Result.Error -> {
                    Logger.w(result.exception) { "Failed to resume paused run: ${result.message}" }
                    // The card stays up so a transient failure can be retried — but the composer
                    // was emptied to send this, so the text has to come back either way.
                    answerText?.let(restoreAnswer)
                    val statusCode = (result.exception as? ApiException)?.statusCode
                    // A 409 arriving past the pause's own expiry is the expiry, not a retryable
                    // failure: the server has finalized the run (clock skew can land this before
                    // the local timer). Dismiss with the expiry copy — retrying can only 409.
                    val expiredNow = statusCode == HTTP_CONFLICT &&
                        action.expiresAt?.let { nowMillis() >= it } == true
                    if (expiredNow) {
                        expireNow(actionId)
                        return@launch
                    }
                    val fingerprintRejected = statusCode == HTTP_FORBIDDEN
                    handle.update {
                        isResolvingPendingAction = false
                        error = if (fingerprintRejected) {
                            fingerprintRejectedMessage()
                        } else {
                            resumeFailureMessage(result.message)
                        }
                    }
                }
                is Result.Loading -> {
                    answerText?.let(restoreAnswer)
                    handle.update { isResolvingPendingAction = false }
                }
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

    private fun PinnedTurnConfig.toStored() = ResumeTurnPin(
        endpoint = endpoint,
        endpointType = endpointType,
        agentId = agentId,
        model = model,
        promptPrefix = promptPrefix,
        ephemeralAgent = ephemeralAgent,
        isTemporary = isTemporary,
    )

    private fun ResumeTurnPin.toPinnedTurn() = PinnedTurnConfig(
        endpoint = endpoint,
        endpointType = endpointType,
        agentId = agentId,
        model = model,
        promptPrefix = promptPrefix,
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

        /** The resume route's answer to a request fingerprint that does not match the paused run. */
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409
    }
}
