package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MergeStreamedAttachmentsTest {

    private fun message(vararg attachments: Attachment) =
        Message(
            messageId = "m1",
            conversationId = "c1",
            attachments = if (attachments.isEmpty()) null else attachments.toList(),
        )

    @Test
    fun `attachments already present are left untouched`() {
        val msg = message(Attachment(fileId = "f1", filename = "a.pdf"))
        val streamed = listOf(Attachment(fileId = "f1", filename = "a.pdf"))

        val result = mergeStreamedAttachments(msg, streamed)

        assertThat(result).isSameInstanceAs(msg)
        assertThat(result.attachments).hasSize(1)
    }

    @Test
    fun `missing streamed attachment is merged in`() {
        val msg = message()
        val streamed = listOf(Attachment(fileId = "f1", filename = "a.pdf", toolCallId = "call_1"))

        val result = mergeStreamedAttachments(msg, streamed)

        assertThat(result.attachments).hasSize(1)
        assertThat(result.attachments!!.single().fileId).isEqualTo("f1")
    }

    @Test
    fun `only the gap is filled, server copy is kept`() {
        val msg = message(Attachment(fileId = "f1", filename = "server.pdf"))
        val streamed = listOf(
            Attachment(fileId = "f1", filename = "streamed.pdf"),
            Attachment(fileId = "f2", filename = "new.png"),
        )

        val result = mergeStreamedAttachments(msg, streamed)

        assertThat(result.attachments!!.map { it.fileId }).containsExactly("f1", "f2").inOrder()
        // Server's copy of f1 (server.pdf) is preserved, not overwritten by the streamed one.
        assertThat(result.attachments!!.first { it.fileId == "f1" }.filename).isEqualTo("server.pdf")
    }

    @Test
    fun `empty streamed list is a no-op`() {
        val msg = message(Attachment(fileId = "f1"))
        assertThat(mergeStreamedAttachments(msg, emptyList())).isSameInstanceAs(msg)
    }

    @Test
    fun `streamed attachments without file id are skipped`() {
        val msg = message()
        val streamed = listOf(Attachment(fileId = null, filename = "nameless.bin"))
        assertThat(mergeStreamedAttachments(msg, streamed)).isSameInstanceAs(msg)
    }
}
