package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.ToolCallType
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.core.model.content.ImageUrlContent
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors web `serializeMessageForClipboard` (useCopyToClipboard.ts, d920328bfa53). */
class MessageClipboardTest {

    private fun message(parts: List<MessageContentPart>?, text: String = "") = Message(
        messageId = "m1",
        conversationId = "c1",
        text = text,
        content = parts,
    )

    private fun text(value: String) = MessageContentPart(type = ContentType.TEXT, text = value)

    @Test
    fun aMessageWithoutPartsCopiesItsText() {
        assertEquals("plain body", serializeMessageForClipboard(message(null, text = "plain body")))
        assertEquals("plain body", serializeMessageForClipboard(message(emptyList(), text = "plain body")))
    }

    @Test
    fun textPartsCopyUnlabeled() {
        val out = serializeMessageForClipboard(message(listOf(text("one"), text("two"))))
        assertEquals("one\ntwo", out)
    }

    @Test
    fun reasoningCopiesAsALabeledBlockWithThinkTagsStripped() {
        val out = serializeMessageForClipboard(
            message(
                listOf(
                    MessageContentPart(type = ContentType.THINK, think = "<think>chain of thought</think>"),
                    text("answer"),
                ),
            ),
        )
        assertEquals("Thinking:\nchain of thought\nanswer", out)
    }

    @Test
    fun toolCallsCopyAsLabeledJson() {
        val out = serializeMessageForClipboard(
            message(
                listOf(
                    MessageContentPart(
                        type = ContentType.TOOL_CALL,
                        toolCall = AgentToolCall(id = "t1", name = "search", output = "done"),
                    ),
                    text("answer"),
                ),
            ),
        )
        assertTrue(out.startsWith("Tool:\n{"), "got: $out")
        assertTrue("\"name\":\"search\"" in out)
        assertTrue(out.endsWith("\nanswer"))
    }

    @Test
    fun codeInterpreterCallsGetTheirOwnLabel() {
        val out = serializeMessageForClipboard(
            message(
                listOf(
                    MessageContentPart(
                        type = ContentType.TOOL_CALL,
                        toolCall = AgentToolCall(type = ToolCallType.CODE_INTERPRETER, id = "t1"),
                    ),
                ),
            ),
        )
        assertTrue(out.startsWith("Run Code:\n"), "got: $out")
    }

    @Test
    fun imageUrlCopiesTheUrlItself() {
        val out = serializeMessageForClipboard(
            message(
                listOf(
                    MessageContentPart(
                        type = ContentType.IMAGE_URL,
                        imageUrl = ImageUrlContent(url = "https://x/img.png"),
                    ),
                ),
            ),
        )
        assertEquals("Image:\nhttps://x/img.png", out)
    }

    @Test
    fun steersAndActivityLabelsCopyWithTheirWebLabels() {
        val out = serializeMessageForClipboard(
            message(
                listOf(
                    MessageContentPart(type = ContentType.STEER, steer = JsonPrimitive("go left")),
                    MessageContentPart(type = ContentType.ACTIVITY_LABEL, activityLabel = "Searched the docs"),
                ),
            ),
        )
        assertEquals("You (steered):\ngo left\nActivity:\nSearched the docs", out)
    }

    @Test
    fun blankAndReservationPartsAreSkipped() {
        val out = serializeMessageForClipboard(
            message(
                listOf(
                    text("  "),
                    MessageContentPart(type = ContentType.ACTIVITY_LABEL, activityLabel = "", pending = true),
                    MessageContentPart(type = ContentType.THINK, think = "  "),
                    text("kept"),
                ),
            ),
        )
        assertEquals("kept", out)
    }

    @Test
    fun errorPartsCopyTheirErrorText() {
        val out = serializeMessageForClipboard(
            message(listOf(MessageContentPart(type = ContentType.ERROR, error = "boom"))),
        )
        assertEquals("boom", out)
    }
}
