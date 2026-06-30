package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.request.AddedConversation
import com.garfiec.librechat.core.model.request.ChatRequest
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.request.NO_PARENT
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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
            // v0.8.7 #13815: the user's IANA zone (e.g. "America/New_York") so the server resolves
            // agent {{current_date}}/{{current_datetime}} to the user's wall clock, not its own.
            // Additive — always sent; older servers ignore the unknown key.
            timezone = TimeZone.currentSystemDefault().id,
        )
    }

    /**
     * Produces the final chat-send body. When [modelParams] is present, the provider-keyed model
     * params ride at the TOP LEVEL alongside the typed request fields — mirroring the web client's
     * `createPayload` spread `{ ...userMessage, ...endpointOption }`. [json] must be the shared
     * client instance (encodeDefaults=false / explicitNulls=false) so the encoded request keeps the
     * same wire shape as sending the [ChatRequest] directly. Params override base keys on collision.
     */
    fun toBody(json: Json, request: ChatRequest, modelParams: JsonObject?): JsonObject {
        val base = json.encodeToJsonElement(request).jsonObject
        return if (modelParams.isNullOrEmpty()) base else JsonObject(base + modelParams)
    }
}
