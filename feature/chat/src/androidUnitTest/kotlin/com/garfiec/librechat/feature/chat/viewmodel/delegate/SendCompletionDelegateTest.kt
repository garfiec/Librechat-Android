package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.feature.chat.util.AbortFrameFixtures
import com.garfiec.librechat.feature.chat.util.applyAbortContract
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ComparisonState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.MessageTreeHandle
import com.garfiec.librechat.feature.chat.viewmodel.MessagesState
import com.garfiec.librechat.feature.chat.viewmodel.NewChatSelectionHandoff
import com.garfiec.librechat.feature.chat.viewmodel.SendCompletionHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers [SendCompletionDelegate.onFinal]'s aborted-turn decisions against realistic frames
 * ([AbortFrameFixtures]): what gets cached, what gets saved, and which title path runs. The
 * frames are passed through `applyAbortContract` first, exactly as `handleFinal` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendCompletionDelegateTest {

    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val modelDelegate = mockk<ModelSelectionDelegate>(relaxed = true)
    private val tts = mockk<PlatformTts>(relaxed = true)
    private val reloadConversation = mockk<(String) -> Unit>(relaxed = true)

    private fun optimisticUserMessage() = Message(
        messageId = AbortFrameFixtures.USER_MESSAGE_ID,
        conversationId = AbortFrameFixtures.CONVERSATION_ID,
        text = "hi",
        isCreatedByUser = true,
        sender = "User",
        createdAt = "t0",
    )

    private fun baseState(isComparison: Boolean = false) = ChatUiState(
        conversation = ConversationMetaState(conversationId = AbortFrameFixtures.CONVERSATION_ID),
        content = MessagesState(messages = listOf(optimisticUserMessage()), isStreaming = true),
        comparisonState = ComparisonState(isEnabled = isComparison),
    )

    private fun delegateWith(
        scope: TestScope,
        state: ChatUiState = baseState(),
    ): Pair<SendCompletionDelegate, MutableStateFlow<ChatUiState>> {
        val flow = MutableStateFlow(state)
        val root = ChatStateHandle(flow, scope)
        every { modelDelegate.conversationModelResolved } returns true
        val delegate = SendCompletionDelegate(
            handle = SendCompletionHandle(root),
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            draftRepository = draftRepository,
            modelDelegate = modelDelegate,
            treeDelegate = MessageTreeDelegate(MessageTreeHandle(root)), // real: the finalize matters here
            tts = tts,
            selectionHandoff = NewChatSelectionHandoff(),
            reloadConversation = reloadConversation,
        )
        return delegate to flow
    }

    private fun SendCompletionDelegate.finalArrives(event: StreamEvent.Final, isComparison: Boolean = false) {
        onFinal(
            event = event,
            conversationId = AbortFrameFixtures.CONVERSATION_ID,
            completedResponseText = "partial answer",
            shouldAutoRead = false,
            isNewConversation = false,
            isHandedOffNewChat = false,
            isComparison = isComparison,
            originAccount = null,
            aborted = event.aborted,
        )
    }

    @Test
    fun `a persisted abort caches the full turn with merged instances`() = runTest(StandardTestDispatcher()) {
        val (delegate, _) = delegateWith(this)

        delegate.finalArrives(AbortFrameFixtures.persistedAbortFrame().applyAbortContract())
        advanceUntilIdle()

        val turn = slot<List<Message>>()
        coVerify(exactly = 1) { messageRepository.cacheMessages(capture(turn), any()) }
        assertThat(turn.captured.map { it.messageId })
            .containsExactly(AbortFrameFixtures.USER_MESSAGE_ID, AbortFrameFixtures.RESPONSE_MESSAGE_ID)
            .inOrder()
        // The cached request is the merged copy, not the frame's skeletal one.
        assertThat(turn.captured.first().sender).isEqualTo("User")
        assertThat(turn.captured.first().createdAt).isEqualTo("t0")
        // The cached response carries the rebuilt text, matching the server's saved row.
        assertThat(turn.captured.last().text).isEqualTo("partial answer")
    }

    @Test
    fun `an unpersisted abort caches only the user message`() = runTest(StandardTestDispatcher()) {
        val (delegate, flow) = delegateWith(this)

        // applyAbortContract drops the response the server never saved; only the user turn —
        // which IS persisted on a non-early abort — reaches the cache.
        delegate.finalArrives(AbortFrameFixtures.contentlessAbortFrame().applyAbortContract())
        advanceUntilIdle()

        val turn = slot<List<Message>>()
        coVerify(exactly = 1) { messageRepository.cacheMessages(capture(turn), any()) }
        assertThat(turn.captured.map { it.messageId }).containsExactly(AbortFrameFixtures.USER_MESSAGE_ID)
        // And no phantom assistant bubble in the tree for the next send to parent onto.
        assertThat(flow.value.messages.map { it.messageId })
            .containsExactly(AbortFrameFixtures.USER_MESSAGE_ID)
    }

    @Test
    fun `an aborted turn never saves the stub conversation and re-reads the title network-first`() =
        runTest(StandardTestDispatcher()) {
            coEvery { conversationRepository.refreshConversation(any(), any()) } returns
                Result.Success(Conversation(conversationId = AbortFrameFixtures.CONVERSATION_ID, title = "Real Title"))
            val (delegate, flow) = delegateWith(this)

            delegate.finalArrives(AbortFrameFixtures.persistedAbortFrame().applyAbortContract())
            advanceUntilIdle()

            // The frame's conversation is a stub with the hardcoded 'New Chat' title — saving it
            // would overwrite the real row (title, endpoint, model) in Room.
            coVerify(exactly = 0) { conversationRepository.saveConversation(any(), any()) }
            // Title: never generate for a stopped turn; re-read network-first (an immediate-mode
            // title that finished before the Stop exists server-side while the cache still holds
            // the placeholder).
            coVerify(exactly = 0) { conversationRepository.generateTitle(any(), any()) }
            coVerify(exactly = 1) { conversationRepository.refreshConversation(any(), any()) }
            assertThat(flow.value.conversation.conversationTitle).isEqualTo("Real Title")
        }

    /**
     * The server's title save is gated on the request's unwind, so the first post-abort read
     * races it and can return the placeholder — the on-device symptom was a stopped first turn
     * reverting from its TitleUpdate-delivered title to "New Chat". The re-read must retry past
     * the placeholder; the retry that finds the real title also re-upserts, healing the row the
     * racing read poisoned.
     */
    @Test
    fun `the aborted title re-read retries past the placeholder`() = runTest(StandardTestDispatcher()) {
        coEvery { conversationRepository.refreshConversation(any(), any()) } returnsMany listOf(
            Result.Success(Conversation(conversationId = AbortFrameFixtures.CONVERSATION_ID, title = "New Chat")),
            Result.Success(Conversation(conversationId = AbortFrameFixtures.CONVERSATION_ID, title = "Real Title")),
        )
        val (delegate, flow) = delegateWith(this)

        delegate.finalArrives(AbortFrameFixtures.persistedAbortFrame().applyAbortContract())
        advanceUntilIdle()

        assertThat(flow.value.conversation.conversationTitle).isEqualTo("Real Title")
        coVerify(exactly = 2) { conversationRepository.refreshConversation(any(), any()) }
    }

    /**
     * When every read returns the placeholder (the Stop genuinely cancelled title generation
     * in flight), the in-memory title — possibly already delivered via the TitleUpdate SSE
     * event — must not be downgraded to "New Chat".
     */
    @Test
    fun `a placeholder-only title re-read never downgrades the in-memory title`() =
        runTest(StandardTestDispatcher()) {
            coEvery { conversationRepository.refreshConversation(any(), any()) } returns
                Result.Success(Conversation(conversationId = AbortFrameFixtures.CONVERSATION_ID, title = "New Chat"))
            val state = baseState().let {
                it.copy(conversation = it.conversation.copy(conversationTitle = "History of Computing Essay"))
            }
            val (delegate, flow) = delegateWith(this, state)

            delegate.finalArrives(AbortFrameFixtures.persistedAbortFrame().applyAbortContract())
            advanceUntilIdle()

            assertThat(flow.value.conversation.conversationTitle).isEqualTo("History of Computing Essay")
            coVerify(exactly = 3) { conversationRepository.refreshConversation(any(), any()) }
        }

    @Test
    fun `a stopped comparison turn finalizes in memory instead of reloading`() =
        runTest(StandardTestDispatcher()) {
            val (delegate, _) = delegateWith(this, baseState(isComparison = true))

            delegate.finalArrives(
                AbortFrameFixtures.persistedAbortFrame().applyAbortContract(),
                isComparison = true,
            )
            advanceUntilIdle()

            // The reload would race the server's post-frame persistence (emit-then-save) and
            // return the conversation without the partial — the vanishing-partial bug in
            // comparison clothes.
            verify(exactly = 0) { reloadConversation(any()) }
            coVerify(exactly = 1) { messageRepository.cacheMessages(any(), any()) }
        }

    @Test
    fun `a clean comparison final still reconciles via reload`() = runTest(StandardTestDispatcher()) {
        val (delegate, _) = delegateWith(this, baseState(isComparison = true))

        delegate.finalArrives(
            StreamEvent.Final(
                requestMessage = optimisticUserMessage(),
                responseMessage = AbortFrameFixtures.abortedResponse().copy(unfinished = false),
            ),
            isComparison = true,
        )
        advanceUntilIdle()

        verify(exactly = 1) { reloadConversation(AbortFrameFixtures.CONVERSATION_ID) }
        coVerify(exactly = 0) { messageRepository.cacheMessages(any(), any()) }
    }

    @Test
    fun `a temporary chat never caches even on a persisted abort`() = runTest(StandardTestDispatcher()) {
        val state = baseState().let {
            it.copy(conversation = it.conversation.copy(isTemporaryChat = true))
        }
        val (delegate, _) = delegateWith(this, state)

        delegate.finalArrives(AbortFrameFixtures.persistedAbortFrame().applyAbortContract())
        advanceUntilIdle()

        // SECURITY: temp-chat data-at-rest guard — no Room writes, no conversation save.
        coVerify(exactly = 0) { messageRepository.cacheMessages(any(), any()) }
        coVerify(exactly = 0) { conversationRepository.saveConversation(any(), any()) }
    }
}
