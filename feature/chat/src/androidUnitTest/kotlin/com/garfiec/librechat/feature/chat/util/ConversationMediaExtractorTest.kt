package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the Artifacts/Links tabs' extraction. The artifact half is deliberately per-content-part:
 * joining parts first would build a string that renders nowhere, and an unclosed directive in one
 * part would swallow the parts after it.
 */
class ConversationMediaExtractorTest {

    private fun message(text: String = "", parts: List<MessageContentPart>? = null) =
        Message(messageId = "m1", conversationId = "c1", text = text, content = parts)

    private fun textPart(text: String) = MessageContentPart(type = ContentType.TEXT, text = text)

    private fun thinkPart(think: String) = MessageContentPart(type = ContentType.THINK, think = think)

    private fun artifact(id: String, body: String = "hello") =
        ":::artifact{identifier=\"$id\" type=\"text/html\" title=\"$id\"}\n```html\n$body\n```\n:::"

    @Test
    fun `extracts artifacts from a message without content parts`() {
        val groups = extractConversationArtifacts(listOf(message(text = artifact("a"))))
        assertEquals(1, groups.size)
        assertEquals("a", groups[0][0].identifier)
    }

    @Test
    fun `groups versions of the same identifier across messages`() {
        val groups = extractConversationArtifacts(
            listOf(
                message(text = artifact("counter", "v1")),
                message(
                    text = ":::artifact{identifier=\"counter\" type=\"text/html\" title=\"C\" " +
                        "version=\"2\"}\n```html\nv2\n```\n:::",
                ),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 2), groups[0].map { it.version })
    }

    @Test
    fun `an unclosed directive in one part does not swallow later parts`() {
        // The regression per-part detection exists to prevent: joined, the truncated artifact in
        // part 1 would run to the end of the joined string and absorb part 2's real artifact.
        val message = message(
            parts = listOf(
                textPart(":::artifact{identifier=\"cut\" type=\"text/html\" title=\"Cut\"}\n```html\n<p>partial"),
                textPart(artifact("whole")),
            ),
        )

        val groups = extractConversationArtifacts(listOf(message))
        // The truncated one is excluded (incomplete); the intact one survives intact.
        assertEquals(1, groups.size)
        assertEquals("whole", groups[0][0].identifier)
    }

    @Test
    fun `incomplete artifacts are excluded from the gallery`() {
        val truncated = ":::artifact{identifier=\"cut\" type=\"text/html\" title=\"Cut\"}\n```html\n<p>partial"
        assertEquals(emptyList<Any>(), extractConversationArtifacts(listOf(message(text = truncated))))
    }

    @Test
    fun `think parts are scanned independently of text parts`() {
        val message = message(
            parts = listOf(thinkPart("reasoning about it"), textPart(artifact("a"))),
        )
        val groups = extractConversationArtifacts(listOf(message))
        assertEquals(1, groups.size)
        assertEquals("a", groups[0][0].identifier)
    }

    @Test
    fun `links are still extracted across parts and deduped`() {
        val message = message(
            parts = listOf(
                textPart("see https://example.com/a for more"),
                textPart("also https://example.com/a and https://other.test/b"),
            ),
        )
        val links = extractConversationLinks(listOf(message))
        assertEquals(listOf("https://example.com/a", "https://other.test/b"), links.map { it.url })
        assertEquals("example.com", links[0].host)
    }
}
