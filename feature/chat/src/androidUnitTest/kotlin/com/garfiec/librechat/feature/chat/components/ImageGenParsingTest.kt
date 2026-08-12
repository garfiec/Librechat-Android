package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The settled (persisted) image-gen parse; its streaming sibling is [StreamingImageGenParsingTest].
 * The two must agree, because `BranchMedia` resolves the gallery's URLs through them and a
 * divergence opens the wrong image full-screen.
 */
class ImageGenParsingTest {

    private val baseUrl = "https://chat.example.com"

    @Test
    fun `multiple attachments produce multiple image urls in arrival order`() {
        val toolCall = AgentToolCall(id = "call_1", name = "image_gen_oai", output = "done")
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            Attachment(fileId = "f2", filepath = "/api/files/f2/two.png", toolCallId = "call_1"),
        )

        val result = parseImageGenResult(toolCall, baseUrl, attachments)

        assertThat(result.imageUrls).containsExactly(
            "https://chat.example.com/api/files/f1/one.png",
            "https://chat.example.com/api/files/f2/two.png",
        ).inOrder()
        assertThat(result.isGenerating).isFalse()
    }

    @Test
    fun `ignores attachments belonging to another tool call`() {
        val toolCall = AgentToolCall(id = "call_1", name = "image_gen_oai", output = "done")
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            Attachment(fileId = "f9", filepath = "/api/files/f9/other.png", toolCallId = "call_2"),
        )

        assertThat(parseImageGenResult(toolCall, baseUrl, attachments).imageUrls)
            .containsExactly("https://chat.example.com/api/files/f1/one.png")
    }

    @Test
    fun `attachments win over the output json fallback`() {
        val toolCall = AgentToolCall(
            id = "call_1",
            name = "image_gen_oai",
            output = """{"url":"https://cdn.example.com/legacy.png"}""",
        )
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
        )

        assertThat(parseImageGenResult(toolCall, baseUrl, attachments).imageUrls)
            .containsExactly("https://chat.example.com/api/files/f1/one.png")
    }

    @Test
    fun `output json fallback still yields a single url when there are no attachments`() {
        val toolCall = AgentToolCall(
            id = "call_1",
            name = "image_gen_oai",
            output = """{"url":"https://cdn.example.com/legacy.png"}""",
        )

        val result = parseImageGenResult(toolCall, baseUrl, emptyList())

        assertThat(result.imageUrls).containsExactly("https://cdn.example.com/legacy.png")
        assertThat(result.isGenerating).isFalse()
    }

    @Test
    fun `file id output fallback resolves against the base url`() {
        val toolCall = AgentToolCall(
            id = "call_1",
            name = "image_gen_oai",
            output = """{"file_id":"abc"}""",
        )

        assertThat(parseImageGenResult(toolCall, baseUrl, emptyList()).imageUrls)
            .containsExactly("https://chat.example.com/api/files/abc")
    }

    @Test
    fun `no output and no attachments still reads as generating`() {
        val toolCall = AgentToolCall(id = "call_1", name = "image_gen_oai")

        val result = parseImageGenResult(toolCall, baseUrl, emptyList())

        assertThat(result.imageUrls).isEmpty()
        assertThat(result.isGenerating).isTrue()
    }

    @Test
    fun `duplicate attachment urls collapse`() {
        val toolCall = AgentToolCall(id = "call_1", name = "image_gen_oai", output = "done")
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
        )

        assertThat(parseImageGenResult(toolCall, baseUrl, attachments).imageUrls).hasSize(1)
    }
}
