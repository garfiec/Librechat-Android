package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.core.model.request.ChatRequest
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.request.NO_PARENT

object ChatPayloadBuilder {

    fun build(
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
    ): ChatRequest {
        val resolvedParentMessageId = parentMessageId ?: NO_PARENT

        return ChatRequest(
            text = text,
            conversationId = conversationId,
            messageId = userMessageId,
            parentMessageId = resolvedParentMessageId,
            endpoint = endpoint,
            endpointType = endpointType,
            key = key,
            modelDisplayLabel = modelDisplayLabel,
            model = model,
            agentId = agentId,
            overrideParentMessageId = overrideParentMessageId,
            responseMessageId = responseMessageId,
            isEdited = isEdited,
            isRegenerate = isRegenerate,
            isContinued = isContinued,
            webSearch = if (webSearch) true else null,
            files = files?.takeIf { it.isNotEmpty() },
            addedConvo = addedConvo,
            ephemeralAgent = ephemeralAgent,
            isTemporary = if (isTemporary) true else null,
        )
    }
}
