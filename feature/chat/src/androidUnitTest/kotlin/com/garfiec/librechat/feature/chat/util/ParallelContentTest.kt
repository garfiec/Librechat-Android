package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParallelContentTest {

    private fun textPart(text: String, agentId: String?, groupId: Int? = null) =
        MessageContentPart(type = ContentType.TEXT, text = text, agentId = agentId, groupId = groupId)

    private fun parallelMessage() = Message(
        messageId = "m1",
        conversationId = "c1",
        content = listOf(
            textPart("primary reply", agentId = "agent_abc", groupId = 0),
            textPart("secondary reply", agentId = "agent_abc____1", groupId = 1),
        ),
    )

    // --- isAddedAgentId ---

    @Test
    fun `isAddedAgentId detects index suffix`() {
        assertThat(isAddedAgentId("agent_abc____1")).isTrue()
        assertThat(isAddedAgentId("openAI__gpt-4o___GPT-4o____2")).isTrue()
    }

    @Test
    fun `isAddedAgentId is false for unsuffixed or null ids`() {
        assertThat(isAddedAgentId("agent_abc")).isFalse()
        assertThat(isAddedAgentId("openAI__gpt-4o___GPT-4o")).isFalse()
        assertThat(isAddedAgentId(null)).isFalse()
    }

    // --- stripAgentIdSuffix ---

    @Test
    fun `stripAgentIdSuffix removes trailing index`() {
        assertThat(stripAgentIdSuffix("agent_abc____1")).isEqualTo("agent_abc")
        assertThat(stripAgentIdSuffix("openAI__gpt-4o___GPT-4o____1")).isEqualTo("openAI__gpt-4o___GPT-4o")
        assertThat(stripAgentIdSuffix("agent_abc")).isEqualTo("agent_abc")
    }

    // --- isEphemeralAgentId ---

    @Test
    fun `isEphemeralAgentId only real agent ids are non-ephemeral`() {
        assertThat(isEphemeralAgentId("agent_abc")).isFalse()
        assertThat(isEphemeralAgentId("openAI__gpt-4o___GPT-4o")).isTrue()
        assertThat(isEphemeralAgentId(null)).isTrue()
    }

    // --- parseEphemeralAgentId ---

    @Test
    fun `parseEphemeralAgentId decodes endpoint and model`() {
        val parsed = parseEphemeralAgentId("openAI__gpt-4o___GPT-4o")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.endpoint).isEqualTo("openAI")
        assertThat(parsed.model).isEqualTo("gpt-4o")
        assertThat(parsed.sender).isEqualTo("GPT-4o")
        assertThat(parsed.index).isNull()
    }

    @Test
    fun `parseEphemeralAgentId decodes index suffix`() {
        val parsed = parseEphemeralAgentId("openAI__gpt-4o___GPT-4o____1")
        assertThat(parsed!!.index).isEqualTo(1)
        assertThat(parsed.endpoint).isEqualTo("openAI")
        assertThat(parsed.model).isEqualTo("gpt-4o")
    }

    @Test
    fun `parseEphemeralAgentId restores colons in model names`() {
        val parsed = parseEphemeralAgentId("anthropic__claude-3__opus")
        assertThat(parsed!!.endpoint).isEqualTo("anthropic")
        assertThat(parsed.model).isEqualTo("claude-3:opus")
    }

    @Test
    fun `parseEphemeralAgentId returns null for non-ephemeral ids`() {
        assertThat(parseEphemeralAgentId("agentabc")).isNull()
    }

    // --- hasParallelParts / agentId extraction ---

    @Test
    fun `hasParallelParts true only when an added-agent part exists`() {
        assertThat(hasParallelParts(parallelMessage())).isTrue()
        val single = Message(
            messageId = "m2",
            conversationId = "c1",
            content = listOf(textPart("hi", agentId = "agent_abc")),
        )
        assertThat(hasParallelParts(single)).isFalse()
    }

    @Test
    fun `primary and secondary agent ids are extracted`() {
        assertThat(primaryAgentId(parallelMessage())).isEqualTo("agent_abc")
        assertThat(secondaryAgentId(parallelMessage())).isEqualTo("agent_abc____1")
    }

    // --- partsForPane ---

    @Test
    fun `partsForPane splits by pane`() {
        val primary = partsForPane(parallelMessage(), secondary = false)
        assertThat(primary).hasSize(1)
        assertThat(primary[0].text).isEqualTo("primary reply")

        val secondary = partsForPane(parallelMessage(), secondary = true)
        assertThat(secondary).hasSize(1)
        assertThat(secondary[0].text).isEqualTo("secondary reply")
    }

    @Test
    fun `partsForPane keeps unattributed parts on the primary pane`() {
        val message = Message(
            messageId = "m3",
            conversationId = "c1",
            content = listOf(
                textPart("unattributed", agentId = null),
                textPart("added", agentId = "agent_abc____1"),
            ),
        )
        val primary = partsForPane(message, secondary = false)
        assertThat(primary.map { it.text }).containsExactly("unattributed")
        val secondary = partsForPane(message, secondary = true)
        assertThat(secondary.map { it.text }).containsExactly("added")
    }
}
