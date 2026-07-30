package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.Feedback
import com.garfiec.librechat.core.model.FeedbackRating
import com.garfiec.librechat.core.model.FeedbackTag
import com.garfiec.librechat.core.model.MinimalFeedback
import com.garfiec.librechat.core.model.request.FeedbackRequest
import com.garfiec.librechat.core.network.di.librechatJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The body `PUT /api/messages/:id/:id/feedback` actually puts on the wire.
 *
 * Encoded with the real [librechatJson], not a locally-configured copy: `encodeDefaults = false`
 * and `explicitNulls = false` are what decide whether `rating` survives and whether clearing omits
 * the key, so a test that rebuilt the config would be asserting something the app never sends.
 */
class FeedbackWireShapeTest {

    private fun encode(request: FeedbackRequest) =
        librechatJson.encodeToString(FeedbackRequest.serializer(), request)

    @Test
    fun submission_isAnObjectWithRatingAndTag() {
        // The schema is an object with a REQUIRED tag. The bare `{"feedback":"thumbsUp"}` this
        // replaced is rejected 400.
        assertEquals(
            """{"feedback":{"rating":"thumbsUp","tag":"accurate_reliable"}}""",
            encode(
                FeedbackRequest(
                    MinimalFeedback(
                        rating = FeedbackRating.THUMBS_UP,
                        tag = FeedbackTag.ACCURATE_RELIABLE,
                    ),
                ),
            ),
        )
    }

    @Test
    fun thumbsDown_carriesTheUsersComment() {
        assertEquals(
            """{"feedback":{"rating":"thumbsDown","tag":"inaccurate","text":"wrong date"}}""",
            encode(
                FeedbackRequest(
                    MinimalFeedback(
                        rating = FeedbackRating.THUMBS_DOWN,
                        tag = FeedbackTag.INACCURATE,
                        text = "wrong date",
                    ),
                ),
            ),
        )
    }

    @Test
    fun omittedComment_dropsTheKeyRatherThanSendingNull() {
        assertEquals(
            """{"feedback":{"rating":"thumbsDown","tag":"other"}}""",
            encode(
                FeedbackRequest(
                    MinimalFeedback(
                        rating = FeedbackRating.THUMBS_DOWN,
                        tag = FeedbackTag.OTHER,
                        text = null,
                    ),
                ),
            ),
        )
    }

    @Test
    fun anUnrecognisedRatingCoercesInsteadOfFailingTheMessageDecode() {
        // Pinned against the app's real decoder, not a locally-built one: `FeedbackRating.UNKNOWN`
        // only does anything because `librechatJson` sets `coerceInputValues`, so a test that
        // rebuilt the config would keep passing if that flag were dropped. Feedback rides on
        // Message, and a throw here loses every message in the conversation, not just the thumb.
        val decoded = librechatJson.decodeFromString(
            Feedback.serializer(),
            """{"rating":"shrug","tag":"other"}""",
        )
        assertEquals(FeedbackRating.UNKNOWN, decoded.rating)
    }

    @Test
    fun clearing_emitsAnEmptyBody() {
        // The route destructures `const { feedback } = req.body` with no default, so an omitted
        // key reads as undefined and its `feedback == null` guard clears instead of 400ing.
        assertEquals("{}", encode(FeedbackRequest(feedback = null)))
    }
}
