package com.garfiec.librechat.core.model.request

import com.garfiec.librechat.core.model.FileReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Sentinel parentMessageId for root messages (no parent). */
const val NO_PARENT = "00000000-0000-0000-0000-000000000000"

@Serializable
data class ChatRequest(
    val text: String,
    val conversationId: String? = null,
    /** Client-minted id for the user message being sent. The server adopts it as the
     *  request message's id and echoes it back in the Final event, so the optimistic
     *  message reconciles by id (matters for temp chats, which merge in-memory and
     *  never round-trip through Room). Mirrors the web client's top-level `messageId`.
     *  Omitted on paths that don't create a new user message (regenerate/continue). */
    val messageId: String? = null,
    val parentMessageId: String,
    val endpoint: String,
    val endpointType: String? = null,
    val modelDisplayLabel: String? = null,
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
    /** When true, the server marks this conversation temporary (v0.8.6): skips
     *  title generation, keeps it out of normal history, and sets an `expiredAt`
     *  from the interface `temporaryChatRetention`. Sent on the chat request. */
    val isTemporary: Boolean? = null,
    /** IANA timezone id (e.g. "America/New_York") of the user's device (v0.8.7 #13815).
     *  The server threads it into `replaceSpecialVars` so agent `{{current_date}}` /
     *  `{{current_datetime}}` resolve to the user's wall clock instead of the server's.
     *  Always sent (additive; older servers ignore the unknown key). */
    val timezone: String? = null,
)
