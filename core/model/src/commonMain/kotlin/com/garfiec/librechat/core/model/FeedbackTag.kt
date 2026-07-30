package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The reason keys the feedback route accepts, mirroring upstream `FEEDBACK_TAGS`.
 *
 * Each key belongs to exactly one [rating]. The server re-checks that pairing and rejects a
 * mismatch with 400, so carrying the direction on the entry keeps the picker from offering a
 * choice the write would bounce.
 */
@Serializable
enum class FeedbackTag(val rating: FeedbackRating) {
    @SerialName("not_matched")
    NOT_MATCHED(FeedbackRating.THUMBS_DOWN),

    @SerialName("inaccurate")
    INACCURATE(FeedbackRating.THUMBS_DOWN),

    @SerialName("bad_style")
    BAD_STYLE(FeedbackRating.THUMBS_DOWN),

    @SerialName("missing_image")
    MISSING_IMAGE(FeedbackRating.THUMBS_DOWN),

    @SerialName("unjustified_refusal")
    UNJUSTIFIED_REFUSAL(FeedbackRating.THUMBS_DOWN),

    @SerialName("not_helpful")
    NOT_HELPFUL(FeedbackRating.THUMBS_DOWN),

    @SerialName("other")
    OTHER(FeedbackRating.THUMBS_DOWN),

    @SerialName("accurate_reliable")
    ACCURATE_RELIABLE(FeedbackRating.THUMBS_UP),

    @SerialName("creative_solution")
    CREATIVE_SOLUTION(FeedbackRating.THUMBS_UP),

    @SerialName("clear_well_written")
    CLEAR_WELL_WRITTEN(FeedbackRating.THUMBS_UP),

    @SerialName("attention_to_detail")
    ATTENTION_TO_DETAIL(FeedbackRating.THUMBS_UP),

    ;

    companion object {
        /** The tags offered for [rating], in upstream's declaration order. */
        fun forRating(rating: FeedbackRating): List<FeedbackTag> = entries.filter { it.rating == rating }
    }
}
