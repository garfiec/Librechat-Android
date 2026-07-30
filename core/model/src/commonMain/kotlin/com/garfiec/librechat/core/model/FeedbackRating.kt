package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackRating {
    @SerialName("thumbsUp")
    THUMBS_UP,

    @SerialName("thumbsDown")
    THUMBS_DOWN,

    /**
     * A rating this client does not recognise. [Feedback.rating] defaults to it so
     * `coerceInputValues` absorbs an unknown value instead of throwing — a throw there fails the
     * whole `GET /api/messages/:conversationId` decode and loses every message in the
     * conversation, not just the feedback. Never sent: the write shape is [MinimalFeedback],
     * whose rating has no default.
     */
    @SerialName("unknown")
    UNKNOWN,
}
