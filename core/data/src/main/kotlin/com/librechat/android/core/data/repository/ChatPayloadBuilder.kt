package com.librechat.android.core.data.repository

import com.librechat.android.core.model.EModelEndpoint
import com.librechat.android.core.model.FileReference
import com.librechat.android.core.model.request.AddedConversation
import com.librechat.android.core.model.request.ChatRequest
import com.librechat.android.core.model.request.EphemeralAgent
import com.librechat.android.core.model.request.NO_PARENT
import com.librechat.android.core.model.toSerialName

object ChatPayloadBuilder {

    /**
     * Maps common API serial keys (e.g. `openAI`, `azureOpenAI`) to [EModelEndpoint] for normalization.
     */
    private val serialNameToEndpoint: Map<String, EModelEndpoint> = mapOf(
        "azureOpenAI" to EModelEndpoint.AZURE_OPENAI,
        "openAI" to EModelEndpoint.OPENAI,
        "google" to EModelEndpoint.GOOGLE,
        "anthropic" to EModelEndpoint.ANTHROPIC,
        "assistants" to EModelEndpoint.ASSISTANTS,
        "azureAssistants" to EModelEndpoint.AZURE_ASSISTANTS,
        "agents" to EModelEndpoint.AGENTS,
        "custom" to EModelEndpoint.CUSTOM,
        "bedrock" to EModelEndpoint.BEDROCK,
    )

    /**
     * LibreChat accepts [extendedModelEndpointSchema]: built-in serial names **or** custom endpoint
     * keys from YAML (`OpenRouter`, `OpenClaw`, …). Sending a coerced enum (e.g. defaulting unknown
     * to `agents`) breaks parseCompactConvo / ephemeral agents and yields `missing_model`.
     */
    private fun endpointWireString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return EModelEndpoint.AGENTS.toSerialName()
        serialNameToEndpoint[trimmed]?.let { return it.toSerialName() }
        serialNameToEndpoint.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.let {
            return it.value.toSerialName()
        }
        runCatching { EModelEndpoint.valueOf(trimmed.uppercase()) }.getOrNull()?.let {
            return it.toSerialName()
        }
        return trimmed
    }

    /**
     * `parseCompactConvo` only has Zod schemas for built-in [EModelEndpoint] keys. YAML custom
     * endpoints (`OpenRouter`, …) need [endpointType] = `custom` so the server picks
     * `compactEndpointSchemas.custom` (openAI-shaped schema). Without it: `Unknown endpoint: OpenRouter`.
     */
    private fun isBuiltInEndpointSerial(endpointWire: String): Boolean {
        if (serialNameToEndpoint.containsKey(endpointWire)) return true
        return serialNameToEndpoint.keys.any { it.equals(endpointWire, ignoreCase = true) }
    }

    /**
     * LibreChat's agents chat stack expects the same agent id in both `model` and `agent_id` when
     * using the agents endpoint (same as the main chat screen). Favorites / voice often only set
     * [agentId], which led to 403 "insufficient permissions" on the server for some installs.
     */
    private fun normalizeModelAndAgentId(
        endpointWire: String,
        model: String?,
        agentId: String?,
    ): Pair<String?, String?> {
        if (endpointWire != EModelEndpoint.AGENTS.toSerialName()) return model to agentId
        val agentRef = model?.takeIf { it.isNotBlank() } ?: agentId?.takeIf { it.isNotBlank() }
            ?: return null to null
        return agentRef to agentRef
    }

    fun build(
        text: String,
        conversationId: String?,
        endpoint: String,
        model: String?,
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
    ): ChatRequest {
        val endpointWire = endpointWireString(endpoint)
        val (resolvedModel, resolvedAgentId) = normalizeModelAndAgentId(
            endpointWire = endpointWire,
            model = model,
            agentId = agentId,
        )

        val resolvedParentMessageId = parentMessageId ?: NO_PARENT

        val endpointTypeWire =
            if (isBuiltInEndpointSerial(endpointWire)) null else EModelEndpoint.CUSTOM.toSerialName()

        return ChatRequest(
            text = text,
            conversationId = conversationId,
            parentMessageId = resolvedParentMessageId,
            endpoint = endpointWire,
            endpointType = endpointTypeWire,
            model = resolvedModel,
            agentId = resolvedAgentId,
            overrideParentMessageId = overrideParentMessageId,
            responseMessageId = responseMessageId,
            isEdited = isEdited,
            isRegenerate = isRegenerate,
            isContinued = isContinued,
            webSearch = if (webSearch) true else null,
            files = files?.takeIf { it.isNotEmpty() },
            addedConvo = addedConvo,
            ephemeralAgent = ephemeralAgent,
        )
    }
}
