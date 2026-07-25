package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.request.SteerCancelRequest
import com.garfiec.librechat.core.model.request.SteerRequest
import com.garfiec.librechat.core.model.response.ChatAbortResponse
import com.garfiec.librechat.core.model.response.ChatResumeResponse
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import com.garfiec.librechat.core.model.response.SteerCancelResponse
import com.garfiec.librechat.core.model.response.SteerResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface ChatRepository {
    fun startChat(
        text: String,
        conversationId: String?,
        endpoint: String,
        endpointType: String? = null,
        key: String? = null,
        modelDisplayLabel: String? = null,
        model: String?,
        userMessageId: String? = null,
        parentMessageId: String? = null,
        agentId: String? = null,
        overrideParentMessageId: String? = null,
        responseMessageId: String? = null,
        isEdited: Boolean = false,
        isRegenerate: Boolean = false,
        isContinued: Boolean = false,
        webSearch: Boolean = false,
        files: List<FileReference>? = null,
        addedConvo: AddedConversation? = null,
        ephemeralAgent: EphemeralAgent? = null,
        isTemporary: Boolean = false,
        modelParams: JsonObject? = null,
    ): Flow<StreamEvent>

    /**
     * Asks the server to stop the in-flight turn. The response is only an ack — the stopped
     * turn arrives as an `aborted` final frame on the SSE stream, which must stay open.
     *
     * [streamId] may be null (Stop before the `created` milestone assigns a conversation id):
     * when no id resolves, the abort route falls back to the caller's most recent active job.
     *
     * [isTemporary] is forwarded so the server stamps the partial it persists with the temp-chat
     * expiry; omitting it leaves the row with no TTL. See [ChatAbortRequest].
     *
     * The ack is returned rather than discarded because it carries `pendingSteers`: steers the
     * aborted run never injected, handed over claim-on-read. Ignoring the body loses them.
     */
    suspend fun abortChat(streamId: String?, isTemporary: Boolean = false): Result<ChatAbortResponse>

    /**
     * Resolves a run paused for human review (tool approval / ask-user question).
     *
     * Ack-only, like [abortChat]: the resumed turn continues over the SSE stream the caller is
     * already collecting, so nothing here opens or re-opens a stream. The request must replay the
     * paused turn's agent selection — see [ChatResumeRequest].
     */
    suspend fun resumeChat(request: ChatResumeRequest): Result<ChatResumeResponse>

    /**
     * Queues instruction text for injection into the run currently generating on this
     * conversation (v0.8.8 mid-run steering). Ack-only: acceptance means queued, and the
     * injection itself arrives as `on_steer_applied` on the open SSE stream.
     *
     * A rejection is expected traffic, not an exception path — the run may have just ended,
     * paused, or filled its queue. Callers read the code off the failure and degrade with
     * [com.garfiec.librechat.core.model.steer.steerFallbackFor]; none of the outcomes may drop
     * the user's text.
     */
    suspend fun steerChat(request: SteerRequest): Result<SteerResponse>

    /**
     * Withdraws a queued steer before it is injected. Succeeding with `removed = false` means
     * the cancel lost its race, which is not an error — the steer is already in the reply.
     */
    suspend fun cancelSteer(request: SteerCancelRequest): Result<SteerCancelResponse>

    suspend fun checkStreamStatus(conversationId: String): ChatStatusResponse
    fun resumeStream(conversationId: String): Flow<StreamEvent>
}
