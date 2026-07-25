package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/share/:shareId/fork` (v0.8.8 line, #13714).
 *
 * [targetMessageIndex] is a position in the shared conversation's message list, NOT a message
 * id — the recipient of a share link never sees server message ids, so the cut point has to be
 * expressed in terms of what they can actually see. Null copies the whole conversation.
 */
@Serializable
data class ForkSharedConversationRequest(
    val targetMessageIndex: Int? = null,
)
