package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.response.ChatStatusResponse
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
    suspend fun abortChat(streamId: String): Result<Unit>
    suspend fun checkStreamStatus(conversationId: String): ChatStatusResponse
    fun resumeStream(conversationId: String): Flow<StreamEvent>
}
