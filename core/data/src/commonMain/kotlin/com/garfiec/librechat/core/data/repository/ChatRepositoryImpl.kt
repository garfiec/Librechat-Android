package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.sse.SseClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    override suspend fun abortChat(streamId: String?, isTemporary: Boolean): Result<Unit> = safeApiCall {
        chatApi.abortChat(streamId, isTemporary)
    }

    override suspend fun checkStreamStatus(conversationId: String): ChatStatusResponse {
        return chatApi.getChatStatus(conversationId)
    }

    override fun resumeStream(conversationId: String): Flow<StreamEvent> = flow {
        val streamUrl = "api/agents/chat/stream/$conversationId"
        emitAll(sseClient.connect(streamUrl, resume = true, connectivityFlow = connectivityObserver.isConnected))
    }.flowOn(dispatcher)
}
