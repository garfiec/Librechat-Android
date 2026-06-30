package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Chat Project (folder) that conversations can be assigned to (v0.8.7).
 * Mirrors upstream `TChatProject`; the server identifier is the Mongo `_id`.
 */
@Serializable
data class ChatProject(
    @SerialName("_id") val id: String,
    val name: String,
    val description: String? = null,
    val user: String? = null,
    val conversationCount: Int = 0,
    val lastConversationAt: String? = null,
    val lastConversationId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    companion object {
        /**
         * Sentinel project id for the read-side "no project" filter
         * (`GET /api/convos?projectId=unassigned`). Distinct from the write-side
         * unassign (`assignConversation(projectId = null)`).
         */
        const val UNASSIGNED = "unassigned"
    }
}
