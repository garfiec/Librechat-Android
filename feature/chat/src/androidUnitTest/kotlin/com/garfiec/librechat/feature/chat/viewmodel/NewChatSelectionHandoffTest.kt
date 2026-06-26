package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NewChatSelectionHandoffTest {

    @Test
    fun takeWithMatchingIdReturnsAndClears() {
        val handoff = NewChatSelectionHandoff()
        handoff.put("conv_1", "openAI", "gpt-4o")

        val taken = handoff.take("conv_1")
        assertThat(taken).isEqualTo(
            NewChatSelectionHandoff.Selection("conv_1", "openAI", "gpt-4o"),
        )
        // Single-slot: a second take of the same id is empty (already consumed).
        assertThat(handoff.take("conv_1")).isNull()
    }

    @Test
    fun takeWithMismatchedIdReturnsNullAndKeepsEntry() {
        val handoff = NewChatSelectionHandoff()
        handoff.put("conv_1", "agents", "agent_X")

        assertThat(handoff.take("other")).isNull()
        // The staged entry survives a mismatched take and is still retrievable.
        assertThat(handoff.take("conv_1")).isEqualTo(
            NewChatSelectionHandoff.Selection("conv_1", "agents", "agent_X"),
        )
    }

    @Test
    fun takeOnEmptyReturnsNull() {
        assertThat(NewChatSelectionHandoff().take("conv_1")).isNull()
    }

    @Test
    fun putOverwritesPreviousSingleSlot() {
        val handoff = NewChatSelectionHandoff()
        handoff.put("conv_1", "openAI", "gpt-4o")
        handoff.put("conv_2", "anthropic", "claude-3")

        // Only the latest staged transition is retained.
        assertThat(handoff.take("conv_1")).isNull()
        assertThat(handoff.take("conv_2")).isEqualTo(
            NewChatSelectionHandoff.Selection("conv_2", "anthropic", "claude-3"),
        )
    }

    @Test
    fun nullModelIsCarriedThrough() {
        val handoff = NewChatSelectionHandoff()
        handoff.put("conv_1", "agents", null)

        assertThat(handoff.take("conv_1")).isEqualTo(
            NewChatSelectionHandoff.Selection("conv_1", "agents", null),
        )
    }

    @Test
    fun optimisticUserMessageIsCarriedThrough() {
        val handoff = NewChatSelectionHandoff()
        val optimistic = Message(
            messageId = "msg_1",
            conversationId = "",
            text = "hello",
            isCreatedByUser = true,
        )
        handoff.put("conv_1", "openAI", "gpt-4o", optimistic)

        assertThat(handoff.take("conv_1")).isEqualTo(
            NewChatSelectionHandoff.Selection("conv_1", "openAI", "gpt-4o", optimistic),
        )
    }
}
