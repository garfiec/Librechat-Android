package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Message
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guard: a conversation not in the Room cache, opened offline, must surface an error and
 * a retryable empty state rather than a blank message area.
 *
 * Asserts at the ViewModel level against a repository that fails the way the real one does — by
 * RETURNING `Result.Error`, since `getMessages` is `safeApiCall`-wrapped and lets only
 * `CancellationException` propagate. A throwing fake, or a delegate-level test, passes against the
 * broken shape and proves nothing.
 *
 * Assertions target [ChatUiState.messagesLoadFailed] rather than `error`: `error` is a channel every
 * loader shares, so a later init-time write can overwrite this one and make the assertion about mock
 * ordering instead of behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelMessageLoadFailureTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fixture = ChatViewModelTestFixture()
    private val agentRepository get() = fixture.agentRepository
    private val messageRepository get() = fixture.messageRepository
    private val configRepository get() = fixture.configRepository
    private val conversationRepository get() = fixture.conversationRepository
    private val favoritesRepository get() = fixture.favoritesRepository
    private val keyRepository get() = fixture.keyRepository
    private val roleRepository get() = fixture.roleRepository
    private val serverDataStore get() = fixture.serverDataStore
    private val settingsDataStore get() = fixture.settingsDataStore
    private val platformDelegateFactory get() = fixture.platformDelegateFactory
    private val serverFileSelectionHandoff get() = fixture.serverFileSelectionHandoff
    private val selectionHandoff get() = fixture.selectionHandoff

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fixture.stubDefaults()

        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())

        // The offline case: nothing cached, so the Room read-through emits an empty list.
        every { messageRepository.observeMessages(any()) } returns flowOf(emptyList())
        coEvery { conversationRepository.getConversation(any(), any()) } returns Result.Error(message = "test")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedMessageLoadWithEmptyCacheSurfacesErrorAndRetryableEmptyState() = runTest(testDispatcher) {
        coEvery { messageRepository.getMessages(any()) } returns Result.Error(message = "boom")

        val viewModel = newViewModel(initialConversationId = "conv-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected the retryable empty state to be armed", state.messagesLoadFailed)
        // Must leave LOADING, or the screen spins forever instead of showing the empty state.
        assertEquals(ChatScreenState.ACTIVE, state.screenState)
    }

    @Test
    fun failedMessageLoadStaysSilentWhenAHandedOffChatAlreadyHasContent() = runTest(testDispatcher) {
        // A chat handed off from the landing page carries its just-sent user message, and the
        // server persists the request only once the reply completes — so this fetch is expected to
        // fail while the reply streams. Reporting it would pop an error over a working chat.
        coEvery { messageRepository.getMessages(any()) } returns Result.Error(message = "boom")
        selectionHandoff.put(
            conversationId = "conv-1",
            endpoint = "openAI",
            model = "gpt-4",
            optimisticUserMessage = Message(
                messageId = "user-msg-1",
                conversationId = "conv-1",
                isCreatedByUser = true,
                text = "hello",
            ),
        )

        val viewModel = newViewModel(initialConversationId = "conv-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("seeded chat must not arm the empty state", state.messagesLoadFailed)
    }

    @Test
    fun successfulMessageLoadLeavesEmptyStateDisarmed() = runTest(testDispatcher) {
        // The offline cache-hit path: the repository swallows the network failure and returns the
        // cached rows as Success, so the empty state must stay down.
        val cached = listOf(
            Message(
                messageId = "m-1",
                conversationId = "conv-1",
                isCreatedByUser = true,
                text = "cached",
            ),
        )
        coEvery { messageRepository.getMessages(any()) } returns Result.Success(cached)
        every { messageRepository.observeMessages(any()) } returns flowOf(cached)

        val viewModel = newViewModel(initialConversationId = "conv-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("cache hit must not arm the empty state", state.messagesLoadFailed)
        assertEquals(ChatScreenState.ACTIVE, state.screenState)
    }

    private fun newViewModel(initialConversationId: String?): ChatViewModel =
        fixture.build(
            defaultDispatcher = testDispatcher,
            initialConversationId = initialConversationId,
        )
}
