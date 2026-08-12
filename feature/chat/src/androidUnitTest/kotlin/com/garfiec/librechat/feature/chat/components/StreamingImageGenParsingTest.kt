package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamingImageGenParsingTest {

    private val baseUrl = "https://chat.example.com"

    @Test
    fun `generating state before attachment arrives`() {
        val toolCall = ActiveToolCall(
            id = "call_1",
            name = "image_gen_oai",
            input = """{"prompt":"a red fox","quality":"high"}""",
        )

        val result = parseStreamingImageGenResult(toolCall, baseUrl, emptyList())

        assertThat(result.isGenerating).isTrue()
        assertThat(result.imageUrls).isEmpty()
        assertThat(result.prompt).isEqualTo("a red fox")
        assertThat(result.quality).isEqualTo("high")
    }

    @Test
    fun `swaps to image when matching attachment lands mid-stream`() {
        val toolCall = ActiveToolCall(
            id = "call_1",
            name = "image_gen_oai",
            input = """{"prompt":"a red fox"}""",
        )
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/fox.png", toolCallId = "call_1"),
        )

        val result = parseStreamingImageGenResult(toolCall, baseUrl, attachments)

        assertThat(result.isGenerating).isFalse()
        assertThat(result.imageUrls).containsExactly("https://chat.example.com/api/files/f1/fox.png")
        assertThat(result.prompt).isEqualTo("a red fox")
    }

    @Test
    fun `ignores attachment belonging to a different tool call`() {
        val toolCall = ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "{}")
        val attachments = listOf(
            Attachment(fileId = "f9", filepath = "/api/files/f9/other.png", toolCallId = "call_other"),
        )

        val result = parseStreamingImageGenResult(toolCall, baseUrl, attachments)

        assertThat(result.isGenerating).isTrue()
        assertThat(result.imageUrls).isEmpty()
    }

    @Test
    fun `resolves absolute http filepath without prefixing baseUrl`() {
        val toolCall = ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "{}")
        val attachments = listOf(
            Attachment(filepath = "https://cdn.example.com/img.png", toolCallId = "call_1"),
        )

        val result = parseStreamingImageGenResult(toolCall, baseUrl, attachments)

        assertThat(result.imageUrls).containsExactly("https://cdn.example.com/img.png")
    }

    @Test
    fun `every streamed attachment for the call becomes an image url in order`() {
        val toolCall = ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "{}")
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            Attachment(fileId = "f2", filepath = "/api/files/f2/two.png", toolCallId = "call_1"),
            Attachment(fileId = "f3", filepath = "/api/files/f3/three.png", toolCallId = "call_other"),
        )

        val result = parseStreamingImageGenResult(toolCall, baseUrl, attachments)

        assertThat(result.imageUrls).containsExactly(
            "https://chat.example.com/api/files/f1/one.png",
            "https://chat.example.com/api/files/f2/two.png",
        ).inOrder()
        assertThat(result.isGenerating).isFalse()
    }

    @Test
    fun `duplicate attachment urls collapse`() {
        val toolCall = ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "{}")
        val attachments = listOf(
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
            Attachment(fileId = "f1", filepath = "/api/files/f1/one.png", toolCallId = "call_1"),
        )

        assertThat(parseStreamingImageGenResult(toolCall, baseUrl, attachments).imageUrls).hasSize(1)
    }

    @Test
    fun `tolerates malformed args json`() {
        val toolCall = ActiveToolCall(id = "call_1", name = "image_gen_oai", input = "not json")

        val result = parseStreamingImageGenResult(toolCall, baseUrl, emptyList())

        assertThat(result.prompt).isNull()
        assertThat(result.quality).isNull()
        assertThat(result.isGenerating).isTrue()
    }
}
