package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers [preferInMemoryRequest] — the guard that keeps a stop from downgrading the optimistic
 * user message. The abort endpoint's `requestMessage` is skeletal (text-only), so when the
 * turn's user message is already in memory the payload copy must be dropped in favor of the
 * `finalizeChatDisplay` backfill; when it isn't, the skeletal copy is better than nothing.
 */
class FinalMessagesTest {

    private fun message(
        id: String,
        parentId: String? = null,
        text: String = "msg-$id",
        isUser: Boolean = false,
        attachments: List<Attachment>? = null,
    ) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = parentId,
        text = text,
        isCreatedByUser = isUser,
        attachments = attachments,
    )

    @Test
    fun `drops the payload request when the response parent is already in memory`() {
        val optimistic = message("u1", text = "hi", isUser = true, attachments = listOf(Attachment(fileId = "f1")))
        val event = StreamEvent.Final(
            requestMessage = message("u1", text = "hi", isUser = true), // skeletal: no attachments
            responseMessage = message("a1", parentId = "u1"),
        )

        val adjusted = event.preferInMemoryRequest(listOf(optimistic))

        assertThat(adjusted.requestMessage).isNull()
        assertThat(adjusted.responseMessage?.messageId).isEqualTo("a1")
    }

    @Test
    fun `keeps the payload request when the response parent is not in memory`() {
        val event = StreamEvent.Final(
            requestMessage = message("server-u1", text = "hi", isUser = true),
            responseMessage = message("a1", parentId = "server-u1"),
        )

        val adjusted = event.preferInMemoryRequest(listOf(message("local-u1", isUser = true)))

        assertThat(adjusted.requestMessage?.messageId).isEqualTo("server-u1")
    }

    @Test
    fun `no-ops when the payload has no response or no request`() {
        val noResponse = StreamEvent.Final(requestMessage = message("u1", isUser = true))
        assertThat(noResponse.preferInMemoryRequest(listOf(message("u1", isUser = true))))
            .isEqualTo(noResponse)

        val noRequest = StreamEvent.Final(responseMessage = message("a1", parentId = "u1"))
        assertThat(noRequest.preferInMemoryRequest(listOf(message("u1", isUser = true))))
            .isEqualTo(noRequest)
    }

    @Test
    fun `resolves the response from the legacy message field`() {
        val event = StreamEvent.Final(
            requestMessage = message("u1", isUser = true),
            message = message("a1", parentId = "u1"), // legacy slot
        )

        val adjusted = event.preferInMemoryRequest(listOf(message("u1", isUser = true)))

        assertThat(adjusted.requestMessage).isNull()
    }

    private fun textPart(text: String) = MessageContentPart(type = ContentType.TEXT, text = text)

    @Test
    fun `normalize rebuilds the missing text from content parts`() {
        val event = StreamEvent.Final(
            responseMessage = message("a1", parentId = "u1", text = "").copy(
                content = listOf(textPart("Half an"), textPart("answer")),
            ),
            aborted = true,
        )

        val normalized = event.normalizeAbortedFrame(emptyList())

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

        assertThat(event.normalizeAbortedFrame(emptyList()).responseMessage?.text)
            .isEqualTo("already here")
    }

    @Test
    fun `normalize backfills the legacy message slot too`() {
        val event = StreamEvent.Final(
            message = message("a1", parentId = "u1", text = "").copy(content = listOf(textPart("partial"))),
            aborted = true,
        )

        val normalized = event.normalizeAbortedFrame(emptyList())

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
