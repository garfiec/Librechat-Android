package com.garfiec.librechat.feature.conversations

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.Conversation

@Immutable
data class ArchivedConversationDisplayData(
    val id: String,
    val title: String,
    val endpoint: String,
    val model: String?,
)

fun Conversation.toArchivedDisplayData() = ArchivedConversationDisplayData(
    id = conversationId ?: "",
    title = title ?: "New Chat",
    endpoint = endpoint ?: "chat",
    model = model,
)
