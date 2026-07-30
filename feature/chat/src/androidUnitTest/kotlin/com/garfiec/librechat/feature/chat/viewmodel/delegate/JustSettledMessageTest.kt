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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * `justSettledMessageId` — which response just took over from the streaming bubble.
 *
 * Three attempts at this mechanism compiled, passed every gate, and did nothing, so these tests
 * are written to fail on each of those broken versions specifically:
 *
 *  - **Derived in a `LaunchedEffect` on `isStreaming`.** An effect commits after the composition
 *    that registered it, so the groups had already collapsed by the time the flag turned on.
 *    Guarded by asserting the flag is set in the SAME emission as the swap — a property that
 *    terminal `flow.value` assertions cannot see.
 *  - **Derived during composition instead.** Merely opening a conversation would mark its last
 *    message as freshly settled. Guarded by running a real display-rebuilding path and asserting
 *    the writer left the flag alone, rather than reading back an untouched default.
 *  - **Cleared at the turn boundary.** A queue drain re-enters `beginStreaming` inline in the same
 *    Main dispatch as the finalize, so the clear landed before Compose saw the flag at all.
 *    Guarded by asserting a following turn OVERWRITES, with no clear in between.
 *
 * This module has no Compose harness, which is why the properties have to be pinned here.
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

    private fun finalEvent(request: Message?, response: Message?) = StreamEvent.Final(
        requestMessage = request,
        responseMessage = response,
        conversation = null,
    )

    /** Records every value the flow emits. Unconfined, so emission is synchronous with the write. */
    private fun MutableStateFlow<ChatUiState>.record(into: MutableList<ChatUiState>): Job =
        CoroutineScope(Dispatchers.Unconfined).launch { collect { into += it } }

    @Test
    fun `no emission ever shows the finalized reply without naming it`() {
        // The single-emission property, which a terminal `flow.value` assertion cannot see:
        // splitting finalizeChatDisplay's update in two would leave a frame where the reply is on
        // screen and the flag is not yet set — precisely the state the groups read to decide
        // whether to collapse, and precisely what the LaunchedEffect version produced.
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(optimistic))
        val emissions = mutableListOf<ChatUiState>()
        val job = flow.record(emissions)

        delegate.finalizeChatDisplay(finalEvent(optimistic, message("a1", parentId = "u1")))
        job.cancel()

        val showingReply = emissions.filter { state ->
            state.displayMessages.any { it.message.messageId == "a1" }
        }
        assertThat(showingReply).isNotEmpty()
        showingReply.forEach { state ->
            assertThat(state.justSettledMessageId).isEqualTo("a1")
            // The completion-flash invariant rides in the same update, and any split that broke
            // one would break the other.
            assertThat(state.isStreaming).isFalse()
        }
    }

    @Test
    fun `finalize names the response and swaps it into the active path`() {
        val optimistic = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(optimistic))

        delegate.finalizeChatDisplay(finalEvent(optimistic, message("a1", parentId = "u1")))

        assertThat(flow.value.justSettledMessageId).isEqualTo("a1")
        assertThat(flow.value.displayMessages.map { it.message.messageId }).containsExactly("u1", "a1")
    }

    @Test
    fun `rebuilding the display without a finalize settles nothing`() {
        // The mount trap: a derivation from `!isStreaming` would mark the last message of every
        // opened conversation as freshly settled. This runs a real display-rebuilding path —
        // asserting an untouched default would pass no matter what any writer did.
        val user = message("u1", isUser = true)
        val messages = listOf(user, message("a1", parentId = "u1"), message("a2", parentId = "u1"))
        val (delegate, flow) = delegateWith(
            ChatUiState(
                content = MessagesState(
                    messages = messages,
                    displayMessages = buildActiveMessagePath(messages),
                    isStreaming = false,
                ),
            ),
        )

        delegate.switchBranch("u1", 1)

        assertThat(flow.value.activeBranches["u1"]).isEqualTo(1)
        assertThat(flow.value.justSettledMessageId).isNull()
    }

    @Test
    fun `a following turn overwrites the flag with no clear in between`() {
        // Deliberately no markSettled(null). A drain re-enters beginStreaming inline in the same
        // dispatch as the finalize — nothing on that path suspends — so a clear there runs before
        // Compose reads the flag. Persisting until the next finalize is what makes it work.
        val u1 = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(u1))
        delegate.finalizeChatDisplay(finalEvent(u1, message("a1", parentId = "u1")))
        assertThat(flow.value.justSettledMessageId).isEqualTo("a1")

        val u2 = message("u2", parentId = "a1", isUser = true)
        delegate.finalizeChatDisplay(finalEvent(u2, message("a2", parentId = "u2")))

        assertThat(flow.value.justSettledMessageId).isEqualTo("a2")
    }

    @Test
    fun `a persisting flag can only ever re-match the message it named`() {
        // Why persisting is harmless: the value is a message id, so the worst case is ONE message
        // keeping its groups expanded for the ViewModel's life — which is what the flag asks for.
        val u1 = message("u1", isUser = true)
        val (delegate, flow) = delegateWith(streamingState(u1))
        delegate.finalizeChatDisplay(finalEvent(u1, message("a1", parentId = "u1")))

        val settled = flow.value.justSettledMessageId
        assertThat(flow.value.messages.count { it.messageId == settled }).isEqualTo(1)
    }

    @Test
    fun `an un-sent turn settles nothing`() {
        // earlyAbort removes the optimistic message entirely — there is no reply to suppress on,
        // and this is the one clear that stays.
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

        delegate.finalizeChatDisplay(finalEvent(optimistic, null))

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
