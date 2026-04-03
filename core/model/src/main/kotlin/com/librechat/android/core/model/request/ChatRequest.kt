package com.librechat.android.core.model.request

import com.librechat.android.core.model.EModelEndpoint
import com.librechat.android.core.model.FileReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Sentinel parentMessageId for root messages (no parent). */
const val NO_PARENT = "00000000-0000-0000-0000-000000000000"

@Serializable
data class ChatRequest(
    val text: String,
    val conversationId: String? = null,
    val parentMessageId: String,
    /** Built-in serial (`openAI`, `agents`, …) or custom YAML name (`OpenRouter`, `OpenClaw`, …). */
    val endpoint: String,
    val endpointType: String? = null,
    val model: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val isContinued: Boolean = false,
    val isEdited: Boolean = false,
    val isRegenerate: Boolean = false,
    val overrideParentMessageId: String? = null,
    val responseMessageId: String? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val maxContextTokens: Int? = null,
    val system: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val effort: String? = null,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    val stop: List<String>? = null,
    val tools: List<String>? = null,
    val iconURL: String? = null,
    val greeting: String? = null,
    val spec: String? = null,
    val modelLabel: String? = null,
    val maxTokens: Int? = null,
    val promptPrefix: String? = null,
    val chatGptLabel: String? = null,
    val resendFiles: Boolean? = null,
    val imageDetail: String? = null,
    val key: String? = null,
    val extra: JsonObject? = null,
    @SerialName("web_search") val webSearch: Boolean? = null,
    val files: List<FileReference>? = null,
    val addedConvo: AddedConversation? = null,
    val ephemeralAgent: EphemeralAgent? = null,
)
