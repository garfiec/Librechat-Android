package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val conversationId: String? = null,
    val title: String? = "New Chat",
    val user: String? = null,
    val endpoint: EModelEndpoint? = null,
    val endpointType: EModelEndpoint? = null,
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
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
