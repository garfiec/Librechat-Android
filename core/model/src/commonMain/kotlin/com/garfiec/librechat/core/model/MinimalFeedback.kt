package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable

/**
 * The feedback write shape, mirroring upstream `TMinimalFeedback`.
 *
 * [tag] is required: the route validates against an object schema whose `tag` is not optional, so
 * a bare rating is rejected with 400. [text] is the user's optional comment (server cap: 1024).
 *
 * Distinct from [Feedback] on purpose. Neither [rating] nor [tag] carries a default, so
 * `encodeDefaults = false` cannot drop them from the body.
 */
@Serializable
data class MinimalFeedback(
    val rating: FeedbackRating,
    val tag: FeedbackTag,
    val text: String? = null,
)
