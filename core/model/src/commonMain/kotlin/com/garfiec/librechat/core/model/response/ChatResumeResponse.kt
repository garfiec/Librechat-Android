package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

/**
 * Ack for `POST /api/agents/chat/resume`. Like the abort ack it carries no content: the
 * resumed turn continues over the SSE stream the client is already collecting, so the caller
 * must keep that stream open and let the run finish through the normal event flow.
 */
@Serializable
data class ChatResumeResponse(
    val streamId: String? = null,
    val conversationId: String? = null,
    /** `"resuming"` on success. */
    val status: String? = null,
)
