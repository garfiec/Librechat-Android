package com.librechat.android.feature.conversations

import androidx.compose.runtime.Immutable
import com.librechat.android.core.model.Conversation

@Immutable
data class ArchivedConversationDisplayData(
    val id: String,
    val title: String,
    val endpoint: String,
    val model: String?,
    val archivedAt: String?,
)

fun Conversation.toArchivedDisplayData() = ArchivedConversationDisplayData(
    id = conversationId ?: "",
    title = title ?: "New Chat",
    endpoint = endpoint?.takeIf { it.isNotBlank() } ?: "chat",
    model = model,
    archivedAt = updatedAt,
)
