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

class ChatRepositoryImpl(
    private val chatApi: ChatApi,
    private val sseClient: SseClient,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatcher: CoroutineDispatcher,
) : ChatRepository {

    override fun startChat(
        text: String,
        conversationId: String?,
        endpoint: String,
        endpointType: String?,
        key: String?,
        modelDisplayLabel: String?,
        model: String?,
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
        )
        val startResponse = chatApi.startChat(endpoint, request)
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

    override suspend fun abortChat(streamId: String): Result<Unit> = safeApiCall {
        chatApi.abortChat(streamId)
    }

    override suspend fun checkStreamStatus(conversationId: String): ChatStatusResponse {
        return chatApi.getChatStatus(conversationId)
    }

    override fun resumeStream(conversationId: String): Flow<StreamEvent> = flow {
        val streamUrl = "api/agents/chat/stream/$conversationId"
        emitAll(sseClient.connect(streamUrl, resume = true, connectivityFlow = connectivityObserver.isConnected))
    }.flowOn(dispatcher)
}
