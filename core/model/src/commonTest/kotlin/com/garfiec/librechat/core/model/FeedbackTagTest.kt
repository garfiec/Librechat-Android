package com.garfiec.librechat.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the tag registry against upstream `packages/data-provider/src/feedback.ts`.
 *
 * The literals below are transcribed from `FEEDBACK_REASON_KEYS` and `FEEDBACK_TAGS`, not derived
 * from our own enum — a test that encodes and decodes with the same serializer would pass no
 * matter what the keys said, while the server's `z.enum` would still reject them.
 */
class FeedbackTagTest {

    private val json = Json

    /** `FEEDBACK_REASON_KEYS`, in declaration order. */
    private val upstreamKeys = listOf(
        "not_matched",
        "inaccurate",
        "bad_style",
        "missing_image",
        "unjustified_refusal",
        "not_helpful",
        "other",
        "accurate_reliable",
        "creative_solution",
        "clear_well_written",
        "attention_to_detail",
    )

    /** The `direction` each `FEEDBACK_TAGS` entry declares. */
    private val upstreamDirections = mapOf(
        "not_matched" to "thumbsDown",
        "inaccurate" to "thumbsDown",
        "bad_style" to "thumbsDown",
        "missing_image" to "thumbsDown",
        "unjustified_refusal" to "thumbsDown",
        "not_helpful" to "thumbsDown",
        "other" to "thumbsDown",
        "accurate_reliable" to "thumbsUp",
        "creative_solution" to "thumbsUp",
        "clear_well_written" to "thumbsUp",
        "attention_to_detail" to "thumbsUp",
    )

    private fun keyOf(tag: FeedbackTag) =
        json.encodeToString(FeedbackTag.serializer(), tag).trim('"')

    private fun keyOf(rating: FeedbackRating) =
        json.encodeToString(FeedbackRating.serializer(), rating).trim('"')

    @Test
    fun tagKeys_matchUpstreamExactly() {
        assertEquals(upstreamKeys, FeedbackTag.entries.map(::keyOf))
    }

    @Test
    fun ratings_serializeToTheValuesTheRouteAccepts() {
        assertEquals("thumbsUp", keyOf(FeedbackRating.THUMBS_UP))
        assertEquals("thumbsDown", keyOf(FeedbackRating.THUMBS_DOWN))
    }

    @Test
    fun everyTagDeclaresUpstreamsDirection() {
        // The route re-checks this pairing and 400s on a mismatch, so an inverted entry here would
        // silently make one whole column of the picker unsubmittable.
        FeedbackTag.entries.forEach { tag ->
            assertEquals(upstreamDirections[keyOf(tag)], keyOf(tag.rating), "direction for ${tag.name}")
        }
    }

    @Test
    fun forRating_partitionsTheRegistry() {
        val up = FeedbackTag.forRating(FeedbackRating.THUMBS_UP)
        val down = FeedbackTag.forRating(FeedbackRating.THUMBS_DOWN)
        assertEquals(4, up.size)
        assertEquals(7, down.size)
        assertEquals(FeedbackTag.entries.size, up.size + down.size)
    }
}
