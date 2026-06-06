package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests the in-memory merge that finalizes a temporary chat's display WITHOUT
 * persisting to Room. The leak this guards against: the normal post-stream path
 * round-trips through the Room read-through (upserting message rows to disk); for
 * a temp chat we drive the display from this pure merge instead.
 */
class TemporaryChatMergeTest {

    private fun message(id: String, parentId: String? = null, text: String = "t-$id", isUser: Boolean = false) =
        Message(
            messageId = id,
            conversationId = "conv-1",
            parentMessageId = parentId,
            text = text,
            isCreatedByUser = isUser,
        )

    @Test
    fun `replaces optimistic user message by id and appends the response`() {
        val existing = listOf(message("u1", text = "hi", isUser = true))
        // Final event returns the canonical request (same id) + a new response.
        val finalMessages = listOf(
            message("u1", text = "hi", isUser = true),
            message("a1", parentId = "u1", text = "hello there"),
        )

        val merged = mergeFinalMessagesInMemory(existing, finalMessages)

        assertThat(merged.map { it.messageId }).containsExactly("u1", "a1").inOrder()
        assertThat(merged.last().text).isEqualTo("hello there")
    }

    @Test
    fun `updates an existing message in place without reordering`() {
        val existing = listOf(message("u1", isUser = true), message("a1", text = "partial"))
        val finalMessages = listOf(message("a1", text = "complete"))

        val merged = mergeFinalMessagesInMemory(existing, finalMessages)

        assertThat(merged.map { it.messageId }).containsExactly("u1", "a1").inOrder()
        assertThat(merged.first { it.messageId == "a1" }.text).isEqualTo("complete")
    }

    @Test
    fun `preserves prior turns and appends new ones`() {
        val existing = listOf(message("u1", isUser = true), message("a1"))
        val finalMessages = listOf(message("u2", isUser = true), message("a2"))

        val merged = mergeFinalMessagesInMemory(existing, finalMessages)

        assertThat(merged.map { it.messageId }).containsExactly("u1", "a1", "u2", "a2").inOrder()
    }
}
