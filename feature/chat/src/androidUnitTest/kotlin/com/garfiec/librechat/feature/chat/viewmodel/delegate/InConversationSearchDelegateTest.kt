package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.SearchHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.garfiec.librechat.feature.chat.viewmodel.SearchMatch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Test

/**
 * Behavior tests for [InConversationSearchDelegate]: it flattens matches via the shared
 * render-order enumeration and emits a [com.garfiec.librechat.feature.chat.viewmodel.SearchFocusRequest]
 * carrying the occurrence index the renderer will resolve. Each request must carry a fresh
 * monotonic id so consecutive navigations (even to the same match) re-fire the scroll effect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InConversationSearchDelegateTest {

    private val stateFlow = MutableStateFlow(ChatUiState())
    private val stateHandle = ChatStateHandle(stateFlow, TestScope())
    private val delegate = InConversationSearchDelegate(SearchHandle(stateHandle))

    private val state get() = stateFlow.value

    private fun node(text: String) = MessageNode(
        message = Message(messageId = "m", conversationId = "c", text = text),
        children = emptyList(),
        siblingIndex = 0,
        siblingCount = 1,
    )

    private fun seed(vararg texts: String) {
        stateFlow.value = ChatUiState(content = MessagesState(displayMessages = texts.map { node(it) }))
    }

    /** A parallel (Compare-Models) message: a primary-agent part plus an added-agent (`____1`) part. */
    private fun parallelNode(primaryText: String, secondaryText: String) = MessageNode(
        message = Message(
            messageId = "m",
            conversationId = "c",
            content = listOf(
                MessageContentPart(type = ContentType.TEXT, text = primaryText, agentId = "agent_primary"),
                MessageContentPart(type = ContentType.TEXT, text = secondaryText, agentId = "agent_primary____1"),
            ),
        ),
        children = emptyList(),
        siblingIndex = 0,
        siblingCount = 1,
    )

    @Test
    fun `parallel turn counts only the primary agent's occurrences`() {
        // The single/primary list renders only the primary-agent parts (collapseParallelToPrimary),
        // so the secondary "foo foo" must NOT inflate the match list or shift occurrence indices.
        stateFlow.value = ChatUiState(content = MessagesState(displayMessages = listOf(parallelNode("foo", "foo foo"))))
        delegate.onSearchQueryChanged("foo")

        assertThat(state.searchMatchIndices).containsExactly(
            SearchMatch(messageIndex = 0, occurrenceInMessage = 0),
        )
    }

    @Test
    fun `query change flattens per-occurrence matches across messages`() {
        seed("foo foo", "bar", "foo")
        delegate.onSearchQueryChanged("foo")

        assertThat(state.searchMatchIndices).containsExactly(
            SearchMatch(messageIndex = 0, occurrenceInMessage = 0),
            SearchMatch(messageIndex = 0, occurrenceInMessage = 1),
            SearchMatch(messageIndex = 2, occurrenceInMessage = 0),
        ).inOrder()
    }

    @Test
    fun `query change focuses the first match`() {
        seed("foo foo", "foo")
        delegate.onSearchQueryChanged("foo")

        assertThat(requireNotNull(state.searchFocusRequest).messageIndex).isEqualTo(0)
        assertThat(state.currentSearchMatchIndex).isEqualTo(0)
        assertThat(state.searchMatchIndices[state.currentSearchMatchIndex])
            .isEqualTo(SearchMatch(messageIndex = 0, occurrenceInMessage = 0))
    }

    @Test
    fun `next advances focus to the next occurrence in the same message`() {
        seed("foo foo foo")
        delegate.onSearchQueryChanged("foo")
        delegate.nextSearchMatch()

        assertThat(requireNotNull(state.searchFocusRequest).messageIndex).isEqualTo(0)
        assertThat(state.searchMatchIndices[state.currentSearchMatchIndex])
            .isEqualTo(SearchMatch(messageIndex = 0, occurrenceInMessage = 1))
    }

    @Test
    fun `next wraps around past the last match`() {
        seed("foo", "foo")
        delegate.onSearchQueryChanged("foo")
        delegate.nextSearchMatch() // -> index 1
        delegate.nextSearchMatch() // wraps -> index 0

        assertThat(state.currentSearchMatchIndex).isEqualTo(0)
        assertThat(state.searchFocusRequest?.messageIndex).isEqualTo(0)
    }

    @Test
    fun `previous wraps from first to last`() {
        seed("foo", "bar foo")
        delegate.onSearchQueryChanged("foo")
        delegate.previousSearchMatch()

        assertThat(state.currentSearchMatchIndex).isEqualTo(1)
        assertThat(state.searchFocusRequest?.messageIndex).isEqualTo(1)
    }

    @Test
    fun `each navigation carries a fresh request id even for the same match`() {
        seed("only")
        delegate.onSearchQueryChanged("only")
        val first = requireNotNull(state.searchFocusRequest).requestId

        // Single match: next wraps to the same match, but the id must still change so
        // the LaunchedEffect re-fires and re-scrolls.
        delegate.nextSearchMatch()
        val second = requireNotNull(state.searchFocusRequest).requestId

        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `scroll handled clears the focus request`() {
        seed("foo")
        delegate.onSearchQueryChanged("foo")
        assertThat(state.searchFocusRequest).isNotNull()

        delegate.onSearchScrollHandled()
        assertThat(state.searchFocusRequest).isNull()
    }

    @Test
    fun `blank query clears matches and focus`() {
        seed("foo")
        delegate.onSearchQueryChanged("foo")
        delegate.onSearchQueryChanged("")

        assertThat(state.searchMatchIndices).isEmpty()
        assertThat(state.searchFocusRequest).isNull()
    }

    @Test
    fun `close resets search state`() {
        seed("foo")
        delegate.onSearchQueryChanged("foo")
        delegate.closeSearch()

        assertThat(state.isSearchOpen).isFalse()
        assertThat(state.searchQuery).isEmpty()
        assertThat(state.searchMatchIndices).isEmpty()
        assertThat(state.searchFocusRequest).isNull()
    }
}
