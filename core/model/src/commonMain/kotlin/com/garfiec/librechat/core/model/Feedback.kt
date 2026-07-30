package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Feedback as the server hands it back, and as it is cached in Room.
 *
 * [tag] stays a raw [JsonElement] because the shape is not stable across server versions: the
 * validated route persists the minimal form (a bare key string), while rows written before it can
 * hold the full tag object. A typed field would throw on whichever shape it did not expect.
 * [MinimalFeedback] is the write counterpart and does pin the shape.
 */
@Serializable
data class Feedback(
    val rating: FeedbackRating = FeedbackRating.UNKNOWN,
    val tag: JsonElement? = null,
    val text: String? = null,
)
