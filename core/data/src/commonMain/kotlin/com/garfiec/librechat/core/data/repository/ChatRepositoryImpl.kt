package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.PendingSteer
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.error.ServerErrorCode
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
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.sse.SseClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ChatRepositoryImpl(
    private val chatApi: ChatApi,
    private val sseClient: SseClient,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatcher: CoroutineDispatcher,
    private val json: Json,
) : ChatRepository {

    override fun startChat(
        text: String,
        conversationId: String?,
        endpoint: String,
        endpointType: String?,
        key: String?,
        modelDisplayLabel: String?,
        model: String?,
        userMessageId: String?,
        parentMessageId: String?,
        agentId: String?,
        overrideParentMessageId: String?,
        responseMessageId: String?,
        isEdited: Boolean,
        isRegenerate: Boolean,
        isContinued: Boolean,
        webSearch: Boolean,
        files: List<FileReference>?,
        addedConvo: AddedConversation?,
        ephemeralAgent: EphemeralAgent?,
        isTemporary: Boolean,
        modelParams: JsonObject?,
    ): Flow<StreamEvent> = flow {
        // Phase 1: POST to start the chat - get back a streamId (= conversationId)
        val request = ChatPayloadBuilder.build(
            text = text,
            conversationId = conversationId,
            endpoint = endpoint,
            endpointType = endpointType,
            key = key,
            modelDisplayLabel = modelDisplayLabel,
            model = model,
            userMessageId = userMessageId,
            parentMessageId = parentMessageId,
            agentId = agentId,
            overrideParentMessageId = overrideParentMessageId,
            responseMessageId = responseMessageId,
            isEdited = isEdited,
            isRegenerate = isRegenerate,
            isContinued = isContinued,
            webSearch = webSearch,
            files = files,
            addedConvo = addedConvo,
            ephemeralAgent = ephemeralAgent,
            isTemporary = isTemporary,
        )
        // A send whose parent response is still being written now 409s ("Cannot submit a
        // follow-up while the selected parent response is still being saved"), which an
        // immediate send after an abort or a stream end reaches legitimately. It clears in
        // milliseconds, so retrying is the correct handling; surfacing it would tell the user
        // their message failed when nothing is wrong.
        //
        // Retried ONLY when the 409 carries no `code`. That is the discriminator, not a message
        // match: every other 409 on this route is coded (RESOURCE_RECOVERY_REQUIRED needs the
        // user to reattach files, RUN_REPLACED is a deduplication, GENERATION_PREDECESSOR_MISMATCH
        // and RECOVERY_PAYLOAD_MISMATCH answer request fields mobile does not send), and retrying
        // any of them would either loop or paper over something the user must act on.
        //
        // Read via generationCodeOf, NOT ServerErrorCode.from: this body puts an English sentence
        // under `error`, which from()'s MCP fallback would hand back as a code.
        val startResponse = retryWhileThrowing(
            retryable = { it.statusCode == HTTP_CONFLICT && it.generationErrorCode == null },
        ) {
            chatApi.startChat(endpoint, ChatPayloadBuilder.toBody(json, request, modelParams))
        }
        val streamId = startResponse.conversationId

        // Emit a Created event from the POST response so the ViewModel
        // knows the conversationId before any SSE events arrive.
        emit(StreamEvent.Created(
            conversationId = streamId,
            messageId = "",
            parentMessageId = "",
            generationCreatedAt = startResponse.generationCreatedAt,
        ))

        // Phase 2: GET the SSE stream using the streamId
        val streamUrl = "api/agents/chat/stream/$streamId"
        emitAll(sseClient.connect(streamUrl, connectivityFlow = connectivityObserver.isConnected))
    }.flowOn(dispatcher)

    override suspend fun abortChat(
        streamId: String?,
        isTemporary: Boolean,
        claimSteers: (List<PendingSteer>) -> Unit,
    ): Result<ChatAbortResponse> =
        when (
            val result = retryWhile(
                // The run acknowledged the stop but has not reached a point where it can be
                // stopped. The server explicitly asks to be asked again (`Retry-After: 1`), so
                // reporting a stop failure here would be a lie the user has to act on.
                retryable = {
                    it.statusCode == HTTP_CONFLICT &&
                        it.generationErrorCode == ServerErrorCode.RUN_STILL_ACTIVE
                },
            ) { safeApiCall { chatApi.abortChat(streamId, isTemporary) } }
        ) {
            // Claimed here, not at the call site: the server dropped its copy writing this ack.
            is Result.Success -> {
                claimSteers(result.data.pendingSteers)
                Result.Success(result.data.copy(pendingSteers = emptyList()))
            }

            else -> result
        }

    override suspend fun resumeChat(request: ChatResumeRequest): Result<ChatResumeResponse> = safeApiCall {
        chatApi.resumeChat(request)
    }

    override suspend fun steerChat(request: SteerRequest): Result<SteerResponse> = safeApiCall {
        chatApi.steerChat(request)
    }

    override suspend fun cancelSteer(request: SteerCancelRequest): Result<SteerCancelResponse> = safeApiCall {
        chatApi.cancelSteer(request)
    }

    override suspend fun checkStreamStatus(
        conversationId: String,
        claimSteers: (List<PendingSteer>) -> Unit,
    ): ChatStatusResponse {
        // Throws rather than returning a Result, so it never gets safeApiCall's dispatcher hop;
        // take it explicitly (#326).
        //
        // 503 SERVER_NOT_READY is retried here rather than at the three call sites, because all
        // three read this answer as "the run is gone" and act on it — one ends the stream as
        // ResumeFailed, one silently declines to resume, one abandons a network recovery. The
        // status route answers it while the generation epoch is still settling or while the
        // terminal owner is mid-persist, both of which resolve within about a second, so letting
        // it escape means giving up on a run that is still alive.
        val status = withContext(dispatcher) {
            retryWhileThrowing(
                retryable = {
                    it.statusCode == HTTP_SERVICE_UNAVAILABLE &&
                        it.generationErrorCode == ServerErrorCode.SERVER_NOT_READY
                },
            ) { chatApi.getChatStatus(conversationId) }
        }
        // Before returning, so no staleness guard at the call site can sit between the read and
        // the claim. The server already deleted its copy answering this request.
        claimSteers(status.unrecoveredSteers)
        return status.copy(unrecoveredSteers = emptyList())
    }

    override fun resumeStream(conversationId: String): Flow<StreamEvent> = flow {
        val streamUrl = "api/agents/chat/stream/$conversationId"
        emitAll(sseClient.connect(streamUrl, resume = true, connectivityFlow = connectivityObserver.isConnected))
    }.flowOn(dispatcher)

    /**
     * Runs [block] again while it fails with a transient, server-nominated retryable error.
     *
     * The generation routes gained several outcomes that are explicitly "ask me again in a
     * moment" — 409 `RUN_STILL_ACTIVE` on abort, 503 `SERVER_NOT_READY` on status — each carrying
     * `Retry-After: 1`. They are races inside the server's own generation fencing, not failures,
     * and treating them as failures abandons a run that is still alive.
     *
     * Bounded at [MAX_TRANSIENT_RETRIES] extra attempts, and the wait honours the server's
     * `Retry-After` when it sent one, clamped so a hostile or confused value cannot park the
     * caller. Exhausting the budget returns the last error, so every existing failure path stays
     * reachable — this only changes how quickly it is reached.
     */
    private suspend fun <T> retryWhile(
        retryable: (ApiException) -> Boolean,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var attempt = 0
        while (true) {
            val result = block()
            val exception = (result as? Result.Error)?.exception as? ApiException
            if (exception == null || !retryable(exception) || attempt >= MAX_TRANSIENT_RETRIES) {
                return result
            }
            attempt++
            delay(retryDelayMillis(exception))
        }
    }

    /** [retryWhile] for the one call that reports failure by throwing rather than by `Result`. */
    private suspend fun <T> retryWhileThrowing(
        retryable: (ApiException) -> Boolean,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!retryable(e) || attempt >= MAX_TRANSIENT_RETRIES) throw e
                attempt++
                delay(retryDelayMillis(e))
            }
        }
    }

    private fun retryDelayMillis(exception: ApiException): Long =
        (exception.retryAfterSeconds?.times(1000) ?: DEFAULT_RETRY_DELAY_MS)
            .coerceIn(DEFAULT_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)

    /**
     * The code on a generation-route error body. Every predicate here reads it through this one
     * accessor, so none of them can pick up `ServerErrorCode.from`'s `error` fallback — on these
     * routes an uncoded body is a distinct outcome, not a body whose code lives elsewhere.
     */
    private val ApiException.generationErrorCode: String? get() = ServerErrorCode.generationCodeOf(body)

    private companion object {
        const val HTTP_CONFLICT = 409
        const val HTTP_SERVICE_UNAVAILABLE = 503

        /**
         * Extra attempts after the first. Three covers the status route's own three-read epoch
         * verification with room to spare; more would keep a user staring at a spinner for a
         * server that is not going to settle.
         */
        const val MAX_TRANSIENT_RETRIES = 3
        const val DEFAULT_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_DELAY_MS = 2_000L
    }
}
