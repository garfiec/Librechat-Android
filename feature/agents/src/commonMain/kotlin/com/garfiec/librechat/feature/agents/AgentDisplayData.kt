package com.garfiec.librechat.feature.agents

import androidx.compose.runtime.Immutable

@Immutable
data class AgentCardDisplayData(
    val id: String,
    val name: String,
    val description: String?,
    val avatarUrl: String?,
    val author: String?,
    val authorName: String?,
)

@Immutable
data class AgentDetailDisplayData(
    val id: String,
    val name: String,
    val description: String?,
    val avatarUrl: String?,
    val author: String?,
    val authorName: String?,
    val model: String?,
    val category: String?,
    val tools: List<String>?,
    val conversationStarters: List<String>,
    /**
     * Who to reach about this agent — its declared support contact, or the owner's contact when
     * it declares none (v0.8.8). Null when neither exists.
     */
    val contact: AgentContactDisplayData? = null,
)

/**
 * A resolved agent contact.
 *
 * At least one of [name] / [email] is non-blank — an entry with neither says nothing and is
 * dropped at mapping time rather than rendered as an empty row.
 */
@Immutable
data class AgentContactDisplayData(
    val name: String?,
    val email: String?,
)

@Immutable
data class AgentHandoffDisplayData(
    val id: String,
    val name: String,
)

@Immutable
data class AgentActionDisplayData(
    val actionId: String?,
    val domain: String?,
    val type: String?,
    val authType: String?,
    val rawSpec: String?,
    val functionCount: Int,
)

@Immutable
data class AgentToolDisplayData(
    val toolId: String?,
    val name: String?,
    val description: String?,
    val icon: String?,
    val isAvailable: Boolean,
)
