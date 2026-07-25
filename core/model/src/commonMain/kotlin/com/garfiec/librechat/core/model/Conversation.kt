package com.garfiec.librechat.core.model

import com.garfiec.librechat.core.model.serializer.LenientInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
    /** Reasoning mode for Responses-API models (sibling of [reasoningEffort]). */
    @SerialName("reasoning_mode") val reasoningMode: String? = null,
    /** Reasoning context carried alongside [reasoningMode] for Responses-API models. */
    @SerialName("reasoning_context") val reasoningContext: String? = null,
    val effort: String? = null,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    val thinkingDisplay: String? = null,
    val stop: List<String>? = null,
    val iconURL: String? = null,
    val greeting: String? = null,
    val spec: String? = null,
    val tools: List<String>? = null,
    @SerialName("web_search") val webSearch: Boolean? = null,
    /** Google Gemini "URL Context" grounding (v0.8.7). Google-only, sibling of [webSearch]. */
    @SerialName("url_context") val urlContext: Boolean? = null,
    /** Anthropic prompt-cache duration: `"5m"` | `"1h"` (v0.8.7). Persisted per-conversation. */
    val promptCacheTtl: String? = null,
    /** Whether the conversation is pinned to the top of the list (v0.8.7). */
    val pinned: Boolean? = null,
    /** Chat Project (folder) this conversation is assigned to, or null if unassigned (v0.8.7). */
    val chatProjectId: String? = null,
    /** True for a temporary chat (v0.8.6): not persisted to normal history and
     *  expired/cleaned up server-side after [expiredAt]. Server-derived. */
    val isTemporary: Boolean? = null,
    /** Expiry for a temporary chat, computed server-side from the interface
     *  `temporaryChatRetention` setting. Null for permanent chats. */
    @Serializable(with = LenientInstantSerializer::class)
    val expiredAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class)
    val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class)
    val updatedAt: Instant? = null,
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
