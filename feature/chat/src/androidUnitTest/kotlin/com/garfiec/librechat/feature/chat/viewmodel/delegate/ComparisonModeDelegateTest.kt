package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ComparisonHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class ComparisonModeDelegateTest {

    private fun delegateWith(state: ChatUiState = ChatUiState()): Pair<ComparisonModeDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val handle = ChatStateHandle(flow, CoroutineScope(Dispatchers.Unconfined))
        val delegate = ComparisonModeDelegate(
            handle = ComparisonHandle(handle),
            messageRepository = mockk<MessageRepository>(relaxed = true),
            reloadConversation = {},
        )
        return delegate to flow
    }

    private fun textPart(text: String, agentId: String?) =
        MessageContentPart(type = ContentType.TEXT, text = text, agentId = agentId)

    private fun parallelMessage(secondaryAgentId: String) = Message(
        messageId = "m-parallel",
        conversationId = "c1",
        content = listOf(
            textPart("primary reply", agentId = "agent_primary"),
            textPart("secondary reply", agentId = secondaryAgentId),
        ),
    )

    @Test
    fun `rehydrate from a real added-agent id maps to AGENTS endpoint`() {
        val (delegate, flow) = delegateWith()

        delegate.rehydrateFromMessage(parallelMessage("agent_secondary____1"))

        val comparison = flow.value.comparisonState
        assertThat(comparison.isEnabled).isTrue()
        assertThat(comparison.secondaryEndpoint).isEqualTo("agents")
        assertThat(comparison.secondaryModel).isEqualTo("agent_secondary")
        assertThat(comparison.secondaryAgentId).isEqualTo("agent_secondary____1")
        assertThat(comparison.primaryAgentId).isEqualTo("agent_primary")
        assertThat(comparison.parallelMessageId).isEqualTo("m-parallel")
    }

    @Test
    fun `rehydrate from an ephemeral added-agent id decodes endpoint and model`() {
        val (delegate, flow) = delegateWith()

        delegate.rehydrateFromMessage(parallelMessage("openAI__gpt-4o___GPT-4o____1"))

        val comparison = flow.value.comparisonState
        assertThat(comparison.isEnabled).isTrue()
        assertThat(comparison.secondaryEndpoint).isEqualTo("openAI")
        assertThat(comparison.secondaryModel).isEqualTo("gpt-4o")
    }

    @Test
    fun `rehydrate switches a landing screen to active`() {
        val (delegate, flow) = delegateWith(ChatUiState(content = MessagesState(screenState = ChatScreenState.LANDING)))

        delegate.rehydrateFromMessage(parallelMessage("agent_secondary____1"))

        assertThat(flow.value.screenState).isEqualTo(ChatScreenState.ACTIVE)
    }

    @Test
    fun `rehydrate no-ops when the message has no added-agent part`() {
        val (delegate, flow) = delegateWith()
        val single = Message(
            messageId = "m1",
            conversationId = "c1",
            content = listOf(textPart("just one", agentId = "agent_primary")),
        )

        delegate.rehydrateFromMessage(single)

        assertThat(flow.value.comparisonState.isEnabled).isFalse()
    }
}
