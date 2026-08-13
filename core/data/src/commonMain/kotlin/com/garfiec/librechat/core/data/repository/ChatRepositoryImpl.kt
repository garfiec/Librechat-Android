package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.PendingSteer
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
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.sse.SseClient
import kotlinx.coroutines.CoroutineDispatcher
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
        val startResponse = chatApi.startChat(endpoint, ChatPayloadBuilder.toBody(json, request, modelParams))
        val streamId = startResponse.conversationId

        // Emit a Created event from the POST response so the ViewModel
        // knows the conversationId before any SSE events arrive.
        emit(StreamEvent.Created(
            conversationId = streamId,
            messageId = "",
            parentMessageId = "",
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
        when (val result = safeApiCall { chatApi.abortChat(streamId, isTemporary) }) {
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
        val status = withContext(dispatcher) { chatApi.getChatStatus(conversationId) }
        // Before returning, so no staleness guard at the call site can sit between the read and
        // the claim. The server already deleted its copy answering this request.
        claimSteers(status.unrecoveredSteers)
        return status.copy(unrecoveredSteers = emptyList())
    }

    override fun resumeStream(conversationId: String): Flow<StreamEvent> = flow {
        val streamUrl = "api/agents/chat/stream/$conversationId"
        emitAll(sseClient.connect(streamUrl, resume = true, connectivityFlow = connectivityObserver.isConnected))
    }.flowOn(dispatcher)
}
