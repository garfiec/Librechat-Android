package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * Bodies for the Chat Projects endpoints (v0.8.7). Unlike the conversation
 * mutation endpoints, the projects routes read `req.body` fields directly — they
 * are NOT wrapped in an `arg` envelope.
 */
@Serializable
data class CreateChatProjectRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class UpdateChatProjectRequest(
    val name: String? = null,
    val description: String? = null,
)

/** Body for `PUT /api/projects/conversations/:conversationId`. `projectId = null` unassigns. */
@Serializable
data class AssignConversationToProjectRequest(
    val projectId: String? = null,
)
