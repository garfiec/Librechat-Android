package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.MessageTreeHandle
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * `justSettledMessageId` — which response just took over from the streaming bubble.
 *
 * It exists because the UI cannot work this out for itself. Deriving it in a `LaunchedEffect` on
 * `isStreaming` looked right, passed every gate, and did nothing: an effect body commits AFTER the
 * composition that registered it, so the finalized message's activity groups had already chosen to
 * collapse by the time the flag turned on, and nothing re-opens a collapsed group. The obvious
 * repair — deriving it during composition instead — fails the other way, because merely opening a
 * conversation would mark its last message as freshly settled and keep those groups open forever.
 *
 * So the property under test is that the flag names a TRANSITION, not the state "not streaming".
 * These assertions are the guard: this module has no Compose harness, which is exactly why three
 * green gate runs said nothing about a mechanism that never fired.
 */
class JustSettledMessageTest {

    private fun message(id: String, parentId: String? = null, isUser: Boolean = false) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = parentId,
        text = "msg-$id",
        isCreatedByUser = isUser,
    )

    private fun delegateWith(state: ChatUiState): Pair<MessageTreeDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val handle = ChatStateHandle(flow, CoroutineScope(Dispatchers.Unconfined))
        return MessageTreeDelegate(MessageTreeHandle(handle)) to flow
    }

    private fun streamingState(vararg messages: Message) = ChatUiState(
        content = MessagesState(
            messages = messages.toList(),
            displayMessages = buildActiveMessagePath(messages.toList()),
            isStreaming = true,
        ),
    )

    private fun finalEvent(request: Message?, response: Message) = StreamEvent.Final(
        requestMessage = request,
        responseMessage = response,
        conversation = null,
    )

    @Test
    fun `finalize names the response in the same emission that swaps it in`() {
        // Same emission, not a later one: anything that arrives after the swap is too late for the
        // groups' first composition.
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(optimistic))

        delegate.finalizeChatDisplay(finalEvent(optimistic, message("a1", parentId = "u1")))

        val state = flow.value
        assertThat(state.justSettledMessageId).isEqualTo("a1")
        assertThat(state.isStreaming).isFalse()
        assertThat(state.displayMessages.map { it.message.messageId }).containsExactly("u1", "a1")
    }

    @Test
    fun `a conversation that merely loads has settled nothing`() {
        // The trap in deriving this from `!isStreaming`: every opened conversation would mark its
        // last message as freshly settled and keep its groups permanently expanded.
        val (_, flow) = delegateWith(
            ChatUiState(
                content = MessagesState(
                    messages = listOf(message("u1", isUser = true), message("a1", parentId = "u1")),
                    displayMessages = buildActiveMessagePath(
                        listOf(message("u1", isUser = true), message("a1", parentId = "u1")),
                    ),
                    isStreaming = false,
                ),
            ),
        )

        assertThat(flow.value.justSettledMessageId).isNull()
    }

    @Test
    fun `the flag does not outlive its turn`() {
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(optimistic))
        delegate.finalizeChatDisplay(finalEvent(optimistic, message("a1", parentId = "u1")))
        assertThat(flow.value.justSettledMessageId).isEqualTo("a1")

        // What `beginStreaming` does at the next turn boundary. Left set, the previous turn's reply
        // would keep its groups open on every later visit to this conversation.
        delegate.markSettled(null)

        assertThat(flow.value.justSettledMessageId).isNull()
    }

    @Test
    fun `a second turn names its own reply, not the first`() {
        val u1 = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(u1))
        delegate.finalizeChatDisplay(finalEvent(u1, message("a1", parentId = "u1")))

        delegate.markSettled(null)
        val u2 = message("u2", parentId = "a1", isUser = true)
        delegate.finalizeChatDisplay(finalEvent(u2, message("a2", parentId = "u2")))

        assertThat(flow.value.justSettledMessageId).isEqualTo("a2")
    }

    @Test
    fun `an un-sent turn settles nothing`() {
        // earlyAbort removes the optimistic message entirely — there is no reply to suppress on.
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(optimistic))
        delegate.markSettled("stale-from-a-previous-turn")

        delegate.unsendOptimisticTurn("u1")

        assertThat(flow.value.justSettledMessageId).isNull()
    }

    @Test
    fun `a final carrying no response names nothing`() {
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(optimistic))

        delegate.finalizeChatDisplay(
            StreamEvent.Final(requestMessage = optimistic, responseMessage = null, conversation = null),
        )

        assertThat(flow.value.justSettledMessageId).isNull()
    }

    @Test
    fun `markSettled covers the comparison path that never finalizes`() {
        // A live comparison rebuilds from a background reload instead of finalizing in memory, so
        // the id is set before the reload and the message matches when it lands.
        val (delegate, flow) = delegateWith(streamingState(message("u1", isUser = true)))

        delegate.markSettled("a1")

        assertThat(flow.value.justSettledMessageId).isEqualTo("a1")
    }
}
