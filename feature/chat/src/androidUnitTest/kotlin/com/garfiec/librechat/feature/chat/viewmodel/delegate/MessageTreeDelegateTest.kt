package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.viewmodel.ChatScreenState
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Tests the in-memory finalize that drives the gap-free completion view for both
 * normal and temporary chats. The merge itself is covered by TemporaryChatMergeTest;
 * here we assert the delegate wires it into displayMessages, reconciles the optimistic
 * user message by id, preserves untouched instances, and marks the screen ACTIVE.
 */
class MessageTreeDelegateTest {

    private fun message(
        id: String,
        parentId: String? = null,
        text: String = "msg-$id",
        isUser: Boolean = false,
    ) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = parentId,
        text = text,
        isCreatedByUser = isUser,
    )

    private fun delegateWith(state: ChatUiState): Pair<MessageTreeDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val handle = ChatStateHandle(flow, CoroutineScope(Dispatchers.Unconfined))
        return MessageTreeDelegate(handle) to flow
    }

    @Test
    fun `finalizeChatDisplay reconciles optimistic user message by id and appends the reply`() {
        val optimistic = message("u1", text = "hi", isUser = true)
        val (delegate, flow) = delegateWith(
            ChatUiState(
                messages = listOf(optimistic),
                displayMessages = buildActiveMessagePath(listOf(optimistic)),
                isStreaming = true,
            ),
        )

        val event = StreamEvent.Final(
            requestMessage = message("u1", text = "hi", isUser = true),
            responseMessage = message("a1", parentId = "u1", text = "hello there"),
        )

        delegate.finalizeChatDisplay(event)

        val result = flow.value
        assertThat(result.messages.map { it.messageId }).containsExactly("u1", "a1").inOrder()
        assertThat(result.displayMessages.map { it.message.messageId }).containsExactly("u1", "a1").inOrder()
        assertThat(result.displayMessages.last().message.text).isEqualTo("hello there")
        assertThat(result.screenState).isEqualTo(ChatScreenState.ACTIVE)
    }

    @Test
    fun `finalizeChatDisplay preserves identity of untouched messages`() {
        val priorUser = message("u1", isUser = true)
        val priorAi = message("a1", parentId = "u1")
        val optimistic = message("u2", parentId = "a1", isUser = true)
        val (delegate, flow) = delegateWith(
            ChatUiState(
                messages = listOf(priorUser, priorAi, optimistic),
                isStreaming = true,
            ),
        )

        val event = StreamEvent.Final(
            requestMessage = message("u2", parentId = "a1", isUser = true),
            responseMessage = message("a2", parentId = "u2", text = "reply"),
        )

        delegate.finalizeChatDisplay(event)

        val merged = flow.value.messages
        // Untouched earlier turns keep their exact instances (no re-mount on recompose).
        assertThat(merged[0]).isSameInstanceAs(priorUser)
        assertThat(merged[1]).isSameInstanceAs(priorAi)
        assertThat(merged.map { it.messageId }).containsExactly("u1", "a1", "u2", "a2").inOrder()
    }

    @Test
    fun `finalizeChatDisplay with no final messages is a no-op`() {
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(
            ChatUiState(messages = listOf(optimistic), isStreaming = true),
        )

        delegate.finalizeChatDisplay(StreamEvent.Final())

        assertThat(flow.value.messages).containsExactly(optimistic)
    }
}
