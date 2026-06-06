package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val conversationId: String? = null,
    val title: String? = "New Chat",
    val user: String? = null,
    val endpoint: String? = null,
    val endpointType: String? = null,
    val model: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("assistant_id") val assistantId: String? = null,
    val tags: List<String> = emptyList(),
    val isArchived: Boolean = false,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val topK: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    val maxOutputTokens: Int? = null,
    val maxContextTokens: Int? = null,
    val maxTokens: Int? = null,
    val system: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val effort: String? = null,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    val thinkingDisplay: String? = null,
    val stop: List<String>? = null,
    val iconURL: String? = null,
    val greeting: String? = null,
    val spec: String? = null,
    val tools: List<String>? = null,
    @SerialName("web_search") val webSearch: Boolean? = null,
    /** True for a temporary chat (v0.8.6): not persisted to normal history and
     *  expired/cleaned up server-side after [expiredAt]. Server-derived. */
    val isTemporary: Boolean? = null,
    /** ISO-8601 expiry for a temporary chat, computed server-side from the
     *  interface `temporaryChatRetention` setting. Null for permanent chats. */
    val expiredAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Resolves the icon URL to display for this conversation, preferring the per-conversation
 * [iconURL] (set by agents/assistants threads) over the per-endpoint URL from
 * [EndpointConfig.iconURL]. Returns null when no URL is available — callers fall through
 * to the bundled brand glyph or Material fallback.
 */
fun Conversation.resolveEndpointIconUrl(
    endpointConfigs: Map<String, EndpointConfig>,
): String? = iconURL ?: endpoint?.let { endpointConfigs[it]?.iconURL }
