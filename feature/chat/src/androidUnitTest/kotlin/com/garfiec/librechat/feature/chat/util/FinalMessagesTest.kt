package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the aborted-frame helpers. The skeletal-request problem is handled by the monotonic
 * `mergedOver` merge (see TemporaryChatMergeTest), so these tests cover the text rebuild and
 * the persisted-server-side predicate.
 */
class FinalMessagesTest {

    private fun message(
        id: String,
        parentId: String? = null,
        text: String = "msg-$id",
        isUser: Boolean = false,
    ) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = parentId,
        text = text,
        isCreatedByUser = isUser,
    )

    private fun textPart(text: String) = MessageContentPart(type = ContentType.TEXT, text = text)

    @Test
    fun `normalize rebuilds the missing text from content parts`() {
        val event = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1", text = "").copy(
                content = listOf(textPart("Half an"), textPart("answer")),
            ),
            aborted = true,
        )

        val normalized = event.normalizeAbortedFrame()

        // Space inserted between parts that would otherwise run together — matches the server's
        // own parseTextParts, so the cached row equals the row a later fetch returns.
        assertThat(normalized.responseMessage?.text).isEqualTo("Half an answer")
    }

    @Test
    fun `normalize leaves an existing text alone`() {
        val event = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1", text = "already here").copy(
                content = listOf(textPart("different")),
            ),
            aborted = true,
        )

        assertThat(event.normalizeAbortedFrame().responseMessage?.text)
            .isEqualTo("already here")
    }

    @Test
    fun `normalize backfills the legacy message slot too`() {
        val event = StreamEvent.Final(
            message = message("a1", parentId = "u1", text = "").copy(content = listOf(textPart("partial"))),
            aborted = true,
        )

        val normalized = event.normalizeAbortedFrame()

        assertThat(normalized.message?.text).isEqualTo("partial")
        assertThat(normalized.responseMessage).isNull()
    }

    @Test
    fun `a stopped turn with content counts as persisted server-side`() {
        val event = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1").copy(content = listOf(textPart("partial"))),
            aborted = true,
        )

        assertThat(event.abortWasPersistedServerSide()).isTrue()
    }

    @Test
    fun `a stopped turn with no content parts was not persisted`() {
        // The server filters its content before both saving and emitting, so an empty list here
        // means it saved nothing — caching it would strand a blank bubble no reload can clear.
        val emptyContent = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1").copy(content = emptyList()),
            aborted = true,
        )
        assertThat(emptyContent.abortWasPersistedServerSide()).isFalse()

        val nullContent = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1"),
            aborted = true,
        )
        assertThat(nullContent.abortWasPersistedServerSide()).isFalse()
    }

    @Test
    fun `an early abort was never persisted`() {
        val event = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1").copy(content = listOf(textPart("x"))),
            aborted = true,
            earlyAbort = true,
        )

        assertThat(event.abortWasPersistedServerSide()).isFalse()
    }
}
