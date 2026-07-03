package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComparisonDisplayTest {

    private fun textPart(text: String, agentId: String?) =
        MessageContentPart(type = ContentType.TEXT, text = text, agentId = agentId)

    private fun node(message: Message) = MessageNode(
        message = message,
        children = emptyList(),
        siblingIndex = 0,
        siblingCount = 1,
    )

    private fun parallelNode(id: String = "m1") = node(
        Message(
            messageId = id,
            conversationId = "c1",
            sender = "raw",
            content = listOf(
                textPart("primary reply", agentId = "agent_a"),
                textPart("secondary reply", agentId = "agent_a____1"),
            ),
        ),
    )

    @Test
    fun `buildComparisonDisplayMessages keeps each pane's parts and sets sender`() {
        val primary = buildComparisonDisplayMessages(
            listOf(parallelNode()),
            secondary = false,
            parallelMessageId = "m1",
            finalContent = null,
            senderName = "Primary Model",
        )
        assertThat(primary[0].message.content?.map { it.text }).containsExactly("primary reply")
        assertThat(primary[0].message.sender).isEqualTo("Primary Model")

        val secondary = buildComparisonDisplayMessages(
            listOf(parallelNode()),
            secondary = true,
            parallelMessageId = "m1",
            finalContent = null,
            senderName = "Secondary Model",
        )
        assertThat(secondary[0].message.content?.map { it.text }).containsExactly("secondary reply")
        assertThat(secondary[0].message.sender).isEqualTo("Secondary Model")
    }

    @Test
    fun `secondary pane re-points endpoint and icon so the avatar differs from primary`() {
        val withPrimaryIcon = node(
            Message(
                messageId = "m1",
                conversationId = "c1",
                endpoint = "anthropic",
                iconURL = "https://host/primary-avatar.png",
                content = listOf(
                    textPart("primary reply", agentId = "agent_a"),
                    textPart("secondary reply", agentId = "agent_a____1"),
                ),
            ),
        )
        val primary = buildComparisonDisplayMessages(
            listOf(withPrimaryIcon), secondary = false,
            parallelMessageId = "m1", finalContent = null, senderName = "P",
        )[0].message
        // Primary keeps its own endpoint/icon.
        assertThat(primary.endpoint).isEqualTo("anthropic")
        assertThat(primary.iconURL).isEqualTo("https://host/primary-avatar.png")

        val secondary = buildComparisonDisplayMessages(
            listOf(withPrimaryIcon), secondary = true,
            parallelMessageId = "m1", finalContent = null, senderName = "S",
            secondaryEndpoint = "openAI", secondaryIconUrl = null,
        )[0].message
        // Secondary is re-pointed: endpoint swapped, primary's icon cleared (endpoint fallback).
        assertThat(secondary.endpoint).isEqualTo("openAI")
        assertThat(secondary.iconURL).isNull()
    }

    @Test
    fun `buildComparisonDisplayMessages falls back to buffer when pane has no parts`() {
        // A message attributed only to the added agent → primary pane has no parts,
        // so the captured streaming buffer is substituted.
        val onlyAdded = node(
            Message(
                messageId = "m1",
                conversationId = "c1",
                content = listOf(textPart("secondary only", agentId = "agent_a____1")),
            ),
        )
        val primary = buildComparisonDisplayMessages(
            listOf(onlyAdded),
            secondary = false,
            parallelMessageId = "m1",
            finalContent = "buffered primary",
            senderName = "Primary",
        )
        assertThat(primary[0].message.content?.map { it.text }).containsExactly("buffered primary")
    }

    @Test
    fun `buildComparisonDisplayMessages uses buffer for the Final-reload gap`() {
        // Non-parallel message that matches the parallel id (server not yet attributed).
        val plain = node(
            Message(messageId = "m1", conversationId = "c1", content = listOf(textPart("raw", agentId = null))),
        )
        val result = buildComparisonDisplayMessages(
            listOf(plain),
            secondary = false,
            parallelMessageId = "m1",
            finalContent = "gap buffer",
            senderName = "Primary",
        )
        assertThat(result[0].message.content?.map { it.text }).containsExactly("gap buffer")
    }

    @Test
    fun `collapseParallelToPrimary drops added-agent parts, leaves others untouched`() {
        val plain = node(Message(messageId = "m0", conversationId = "c1", text = "hi"))
        val collapsed = collapseParallelToPrimary(listOf(plain, parallelNode("m1")))

        assertThat(collapsed[0]).isSameInstanceAs(plain)
        assertThat(collapsed[1].message.content?.map { it.text }).containsExactly("primary reply")
    }
}
