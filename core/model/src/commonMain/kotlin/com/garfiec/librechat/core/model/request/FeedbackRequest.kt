package com.garfiec.librechat.core.model.request

import com.garfiec.librechat.core.model.MinimalFeedback
import kotlinx.serialization.Serializable

/**
 * Body for `PUT /api/messages/:conversationId/:messageId/feedback`.
 *
 * A null [feedback] clears: `explicitNulls = false` omits the key, and the route's bare
 * `const { feedback } = req.body` reads that as `undefined`, which its `feedback == null` guard
 * treats the same as an explicit null.
 */
@Serializable
data class FeedbackRequest(
    val feedback: MinimalFeedback? = null,
)
