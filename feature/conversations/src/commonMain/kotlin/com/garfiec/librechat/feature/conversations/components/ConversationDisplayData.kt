package com.garfiec.librechat.feature.conversations.components

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.resolveEndpointIconUrl
import kotlin.time.Instant

@Immutable
data class ConversationDisplayData(
    val conversationId: String,
    val title: String,
    val endpoint: String?,
    val model: String?,
    // Only the *formatting* of this happens at render time — see LocalRelativeTimeReference.
    val updatedAt: Instant?,
    val isBookmarked: Boolean,
    val endpointIconUrl: String? = null,
)

fun Conversation.toDisplayData(
    endpointConfigs: Map<String, EndpointConfig>,
): ConversationDisplayData = ConversationDisplayData(
    conversationId = conversationId ?: "",
    title = title ?: "New Chat",
    endpoint = endpoint,
    model = model,
    updatedAt = updatedAt,
    isBookmarked = SAVED_TAG in tags,
    endpointIconUrl = resolveEndpointIconUrl(endpointConfigs),
)
