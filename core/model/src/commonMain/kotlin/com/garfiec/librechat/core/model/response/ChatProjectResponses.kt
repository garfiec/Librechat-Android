package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.Conversation
import kotlinx.serialization.Serializable

@Serializable
data class DeleteChatProjectResponse(
    val deletedCount: Int = 0,
    val modifiedCount: Int = 0,
)

@Serializable
data class AssignConversationToProjectResponse(
    val conversation: Conversation,
    val previousProjectId: String? = null,
    val projectId: String? = null,
)
