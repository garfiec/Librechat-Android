package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Two invariants for the chrome-rate dedupe: streaming churn must be equivalent, and every
 * discrete transition the chrome renders must not be.
 */
class ChatChromeEquivalenceTest {

    private val streaming = ChatUiState(
        content = MessagesState(
            screenState = ChatScreenState.ACTIVE,
            isStreaming = true,
            streamingContent = "Once upon",
        ),
    )

    private fun ChatUiState.withContent(transform: MessagesState.() -> MessagesState) =
        copy(content = content.transform())

    @Test
    fun `same instance is equivalent`() {
        assertThat(streaming.chromeEquivalentTo(streaming)).isTrue()
    }

    @Test
    fun `streaming text flush is equivalent`() {
        val next = streaming.withContent { copy(streamingContent = "Once upon a time") }
        assertThat(streaming.chromeEquivalentTo(next)).isTrue()
    }

    @Test
    fun `active tool call updates are equivalent`() {
        val next = streaming.withContent {
            copy(activeToolCalls = listOf(ActiveToolCall(id = "t1", name = "web_search")))
        }
        assertThat(streaming.chromeEquivalentTo(next)).isTrue()
    }

    @Test
    fun `streaming attachment arrival is equivalent`() {
        val next = streaming.withContent {
            copy(streamingAttachments = listOf(Attachment(messageId = "m1")))
        }
        assertThat(streaming.chromeEquivalentTo(next)).isTrue()
    }

    @Test
    fun `comparison pane streaming churn is equivalent`() {
        val base = streaming.copy(comparisonState = ComparisonState(isEnabled = true))
        val next = base.copy(
            comparisonState = base.comparisonState.copy(
                primaryStreamingContent = "left",
                secondaryStreamingContent = "right",
                primaryActiveToolCalls = listOf(ActiveToolCall(id = "t1", name = "web_search")),
                secondaryActiveToolCalls = listOf(ActiveToolCall(id = "t2", name = "execute_code")),
            ),
        )
        assertThat(base.chromeEquivalentTo(next)).isTrue()
    }

    @Test
    fun `each comparison streaming buffer is individually neutralized`() {
        val base = streaming.copy(comparisonState = ComparisonState(isEnabled = true))
        val toolCall = listOf(ActiveToolCall(id = "t1", name = "web_search"))
        val churns = listOf<(ComparisonState) -> ComparisonState>(
            { it.copy(primaryStreamingContent = "x") },
            { it.copy(secondaryStreamingContent = "x") },
            { it.copy(primaryActiveToolCalls = toolCall) },
            { it.copy(secondaryActiveToolCalls = toolCall) },
        )
        churns.forEach { churn ->
            val next = base.copy(comparisonState = churn(base.comparisonState))
            assertThat(base.chromeEquivalentTo(next)).isTrue()
        }
    }

    @Test
    fun `isStreaming transition is not equivalent`() {
        val next = streaming.withContent { copy(isStreaming = false, streamingContent = "") }
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `context usage update is not equivalent`() {
        val next = streaming.withContent {
            copy(contextUsage = ContextUsage(remainingContextTokens = 100_000))
        }
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `retry info is not equivalent`() {
        val next = streaming.withContent { copy(retryInfo = RetryInfo(attempt = 1, maxAttempts = 3)) }
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `composer input change is not equivalent`() {
        val next = streaming.copy(composer = ComposerState(inputText = "hi"))
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `error change is not equivalent`() {
        val next = streaming.copy(error = "boom")
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `message swap alone is not equivalent`() {
        // finalizeChatDisplay swaps the tree in the same emission that clears streaming.
        val finalized = Message(messageId = "m1", conversationId = "c1")
        val messagesOnly = streaming.withContent { copy(messages = listOf(finalized)) }
        val displayOnly = streaming.withContent {
            copy(
                displayMessages = listOf(
                    MessageNode(
                        message = finalized,
                        children = emptyList(),
                        siblingIndex = 0,
                        siblingCount = 1,
                    ),
                ),
            )
        }
        val branchesOnly = streaming.withContent { copy(activeBranches = mapOf("root" to 1)) }
        assertThat(streaming.chromeEquivalentTo(messagesOnly)).isFalse()
        assertThat(streaming.chromeEquivalentTo(displayOnly)).isFalse()
        assertThat(streaming.chromeEquivalentTo(branchesOnly)).isFalse()
    }

    @Test
    fun `neutralized copy resets churn fields and preserves the rest`() {
        val loud = streaming
            .withContent {
                copy(
                    activeToolCalls = listOf(ActiveToolCall(id = "t1", name = "web_search")),
                    streamingAttachments = listOf(Attachment(messageId = "m1")),
                )
            }
            .copy(
                comparisonState = ComparisonState(
                    isEnabled = true,
                    primaryStreamingContent = "left",
                    secondaryStreamingContent = "right",
                ),
            )
        val neutral = loud.neutralizeStreamingChurn()
        assertThat(neutral.streamingContent).isEmpty()
        assertThat(neutral.activeToolCalls).isEmpty()
        assertThat(neutral.streamingAttachments).isEmpty()
        assertThat(neutral.comparisonState.primaryStreamingContent).isEmpty()
        assertThat(neutral.comparisonState.secondaryStreamingContent).isEmpty()
        assertThat(neutral.isStreaming).isTrue()
        assertThat(neutral.comparisonState.isEnabled).isTrue()
        assertThat(neutral.screenState).isEqualTo(ChatScreenState.ACTIVE)
    }

    @Test
    fun `token usage update is not equivalent`() {
        val next = streaming.withContent {
            copy(tokenUsage = com.garfiec.librechat.core.model.usage.TokenUsage())
        }
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `subagent trace update is not equivalent`() {
        // Feeds the thread via ChatRoot's LocalSubagentProgress.
        val next = streaming.copy(
            subagents = SubagentState(
                subagentProgress = mapOf("tc1" to SubagentTrace(parentToolCallId = "tc1")),
            ),
        )
        assertThat(streaming.chromeEquivalentTo(next)).isFalse()
    }

    @Test
    fun `navigation and action effect fields are not equivalent`() {
        // ChatScreenEffects keys on these.
        val navigated = streaming.copy(
            conversation = ConversationMetaState(pendingNavigationConversationId = "c1"),
        )
        val forked = streaming.copy(
            actions = ConversationActionsState(forkedConversationId = "c2"),
        )
        assertThat(streaming.chromeEquivalentTo(navigated)).isFalse()
        assertThat(streaming.chromeEquivalentTo(forked)).isFalse()
    }

    @Test
    fun `queue and voice changes are not equivalent`() {
        val queued = streaming.copy(queue = QueueState(isQueuePaused = true))
        val recording = streaming.copy(voice = VoiceState(isRecording = true))
        assertThat(streaming.chromeEquivalentTo(queued)).isFalse()
        assertThat(streaming.chromeEquivalentTo(recording)).isFalse()
    }

    @Test
    fun `comparison secondary streaming flag transition is not equivalent`() {
        val base = streaming.copy(comparisonState = ComparisonState(isEnabled = true))
        val next = base.copy(
            comparisonState = base.comparisonState.copy(secondaryIsStreaming = true),
        )
        assertThat(base.chromeEquivalentTo(next)).isFalse()
    }
}
