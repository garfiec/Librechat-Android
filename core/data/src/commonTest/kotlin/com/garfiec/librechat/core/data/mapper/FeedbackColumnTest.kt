package com.garfiec.librechat.core.data.mapper

import com.garfiec.librechat.core.model.FeedbackRating
import com.garfiec.librechat.core.model.FeedbackTag
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.MinimalFeedback
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The optimistic thumb has to survive the next Room read.
 *
 * It previously did not: the write stored a bare `"thumbsUp"` into a column [toModel] decodes as
 * `Feedback` JSON, the decode threw, and its catch turned the thumb back into null — so the thumb
 * filled, then emptied itself the next time the list re-emitted.
 */
class FeedbackColumnTest {

    private fun entityWithFeedback(feedback: MinimalFeedback?) =
        Message(
            messageId = "m1",
            conversationId = "c1",
            text = "hi",
            isCreatedByUser = false,
        ).toEntity().copy(feedback = feedbackColumnValue(feedback))

    @Test
    fun submittedFeedback_survivesTheRoundTrip() {
        val restored = entityWithFeedback(
            MinimalFeedback(
                rating = FeedbackRating.THUMBS_DOWN,
                tag = FeedbackTag.UNJUSTIFIED_REFUSAL,
                text = "it just said no",
            ),
        ).toModel()

        val feedback = assertNotNull(restored.feedback)
        assertEquals(FeedbackRating.THUMBS_DOWN, feedback.rating)
        assertEquals("unjustified_refusal", feedback.tag?.jsonPrimitive?.content)
        assertEquals("it just said no", feedback.text)
    }

    @Test
    fun tagIsStoredAsTheBareKey_matchingWhatTheServerPersists() {
        // The route saves the validated minimal form, so a later `GET /messages` overwrites this
        // row with a bare-string tag. Storing the full object here would make the cached row and
        // the fetched row decode differently.
        val column = assertNotNull(
            feedbackColumnValue(
                MinimalFeedback(FeedbackRating.THUMBS_UP, FeedbackTag.CREATIVE_SOLUTION),
            ),
        )
        assertEquals("""{"rating":"thumbsUp","tag":"creative_solution"}""", column)
    }

    @Test
    fun clearing_writesNullIntoTheColumn() {
        assertNull(feedbackColumnValue(null))
        assertNull(entityWithFeedback(null).toModel().feedback)
    }
}
