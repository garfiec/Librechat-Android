package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The gallery must resolve the exact same URLs the message renderers show, so it reads through the
 * same `parseImageGenResult` / `parseStreamingImageGenResult` the cards use — resolving
 * independently is how tapping image #2 of 3 ends up opening image #1.
 */
class BranchMediaTest {

    private val baseUrl = "https://chat.example.com"

    private fun node(message: Message) =
        MessageNode(message = message, children = emptyList(), siblingIndex = 0, siblingCount = 1)

    private fun imageGenPart(toolCallId: String) = MessageContentPart(
        type = ContentType.TOOL_CALL,
        toolCall = AgentToolCall(id = toolCallId, name = "image_gen_oai", output = "done"),
    )

    private fun message(id: String, parts: List<MessageContentPart>, attachments: List<Attachment>) =
        Message(
            messageId = id,
            conversationId = "c1",
            text = "",
            content = parts,
            attachments = attachments,
        )

    @Test
    fun `one image gen call surfaces every image it produced`() {
        val message = message(
            id = "m1",
            parts = listOf(imageGenPart("call_1")),
            attachments = listOf(
                Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
                Attachment(fileId = "f2", filepath = "/api/files/f2/two.png", toolCallId = "call_1"),
                Attachment(fileId = "f3", filepath = "/api/files/f3/three.png", toolCallId = "call_1"),
            ),
        )

        val media = collectMessageMedia(message, baseUrl)

        assertThat(media.map { it.url }).containsExactly(
            "https://chat.example.com/api/files/f1/one.png",
            "https://chat.example.com/api/files/f2/two.png",
            "https://chat.example.com/api/files/f3/three.png",
        ).inOrder()
    }

    @Test
    fun `two image gen calls in one message surface in content order`() {
        val message = message(
            id = "m1",
            parts = listOf(imageGenPart("call_1"), imageGenPart("call_2")),
            attachments = listOf(
                Attachment(fileId = "f2", filepath = "/api/files/f2/two.png", toolCallId = "call_2"),
                Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            ),
        )

        val media = collectMessageMedia(message, baseUrl)

        assertThat(media.map { it.url }).containsExactly(
            "https://chat.example.com/api/files/f1/one.png",
            "https://chat.example.com/api/files/f2/two.png",
        ).inOrder()
    }

    @Test
    fun `the in flight streaming call contributes every image it has so far`() {
        val media = extractBranchMedia(
            displayMessages = emptyList(),
            activeToolCalls = listOf(ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "{}")),
            streamingAttachments = listOf(
                Attachment(fileId = "s1", filepath = "/api/files/s1/a.png", toolCallId = "call_1"),
                Attachment(fileId = "s2", filepath = "/api/files/s2/b.png", toolCallId = "call_1"),
            ),
            baseUrl = baseUrl,
        )

        assertThat(media.map { it.url }).containsExactly(
            "https://chat.example.com/api/files/s1/a.png",
            "https://chat.example.com/api/files/s2/b.png",
        ).inOrder()
    }

    @Test
    fun `persisted and streaming images are deduped by url`() {
        val message = message(
            id = "m1",
            parts = listOf(imageGenPart("call_1")),
            attachments = listOf(
                Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            ),
        )

        val media = extractBranchMedia(
            displayMessages = listOf(node(message)),
            activeToolCalls = listOf(ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "{}")),
            streamingAttachments = listOf(
                Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            ),
            baseUrl = baseUrl,
        )

        assertThat(media).hasSize(1)
    }

    @Test
    fun `a non image gen tool call contributes nothing`() {
        val message = message(
            id = "m1",
            parts = listOf(
                MessageContentPart(
                    type = ContentType.TOOL_CALL,
                    toolCall = AgentToolCall(id = "call_1", name = "web_search", output = "done"),
                ),
            ),
            attachments = listOf(
                Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            ),
        )

        assertThat(collectMessageMedia(message, baseUrl)).isEmpty()
    }
}
