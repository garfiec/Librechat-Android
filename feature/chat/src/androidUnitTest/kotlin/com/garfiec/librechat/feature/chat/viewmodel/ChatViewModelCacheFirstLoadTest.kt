package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Message
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
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
 * Opening a conversation must render the cached copy immediately and revalidate behind it (#300).
 *
 * Every test drives `ChatViewModel.init`, which is the only call site that opts into `cacheFirst`.
 * `getMessages` is held on a [CompletableDeferred] so the window between "cache emitted" and
 * "fetch settled" is observable rather than raced past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCacheFirstLoadTest {

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

    /** Holds `getMessages` open so the pre-settle window can be asserted on. */
    private val fetchGate = CompletableDeferred<Result<List<Message>>>()

    private val cachedMessages = listOf(
        Message(
            messageId = "m-1",
            conversationId = "conv-1",
            isCreatedByUser = true,
            text = "cached",
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fixture.stubDefaults()

        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())

        coEvery { conversationRepository.getConversation(any(), any()) } returns Result.Error(message = "test")
        coEvery { messageRepository.getMessages(any()) } coAnswers { fetchGate.await() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cachedMessagesRenderBeforeTheFetchSettles() = runTest(testDispatcher) {
        every { messageRepository.observeMessages(any()) } returns flowOf(cachedMessages)

        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatScreenState.ACTIVE, state.screenState)
        assertEquals(1, state.displayMessages.size)
        assertTrue("the in-flight revalidate must show an indicator", state.isRefreshingMessages)
    }

    @Test
    fun theRevalidateIndicatorClearsWhenTheFetchSettles() = runTest(testDispatcher) {
        every { messageRepository.observeMessages(any()) } returns flowOf(cachedMessages)

        val viewModel = newViewModel()
        advanceUntilIdle()
        // Asserted on both sides of the settle: `assertFalse` alone would pass just as well against
        // a build that never raises the indicator at all.
        assertTrue(viewModel.uiState.value.isRefreshingMessages)

        fetchGate.complete(Result.Success(cachedMessages))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRefreshingMessages)
    }

    @Test
    fun anEmptyCacheKeepsTheSpinnerUntilTheFetchSettles() = runTest(testDispatcher) {
        every { messageRepository.observeMessages(any()) } returns flowOf(emptyList())

        val viewModel = newViewModel()
        advanceUntilIdle()

        // Without this, an uncached online open would flash a blank thread before the messages land.
        assertEquals(ChatScreenState.LOADING, viewModel.uiState.value.screenState)
    }

    @Test
    fun settlingWithNoMessagesReleasesTheSpinner() = runTest(testDispatcher) {
        // A conversation that genuinely has no messages: the fetch succeeds, the upsert writes
        // nothing, and Room never emits a second time. The only thing that can release the spinner
        // is the settle itself re-running the combine — so this fails if `revalidated` is read as a
        // plain flag inside collect instead of being a combine input.
        every { messageRepository.observeMessages(any()) } returns flowOf(emptyList())

        val viewModel = newViewModel()
        advanceUntilIdle()
        fetchGate.complete(Result.Success(emptyList()))
        advanceUntilIdle()

        assertEquals(ChatScreenState.ACTIVE, viewModel.uiState.value.screenState)
        assertFalse(viewModel.uiState.value.messagesLoadFailed)
    }

    @Test
    fun aFailedRevalidateOverCachedMessagesStaysSilent() = runTest(testDispatcher) {
        // The ordinary offline open. The retryable empty state from #299 is for an empty cache
        // only — arming it here would cover a thread the user can read perfectly well.
        every { messageRepository.observeMessages(any()) } returns flowOf(cachedMessages)

        val viewModel = newViewModel()
        advanceUntilIdle()
        fetchGate.complete(Result.Error(message = "offline"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("cached rows must not arm the empty state", state.messagesLoadFailed)
        assertEquals(ChatScreenState.ACTIVE, state.screenState)
        assertEquals(1, state.displayMessages.size)
    }

    private fun newViewModel(): ChatViewModel =
        fixture.build(
            defaultDispatcher = testDispatcher,
            initialConversationId = "conv-1",
        )
}
