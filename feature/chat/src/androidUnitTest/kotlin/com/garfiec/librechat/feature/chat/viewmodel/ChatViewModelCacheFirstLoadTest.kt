package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.ResumePinStore
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformDelegateFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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

    private val agentRepository = mockk<AgentRepository>(relaxed = true)
    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val fileRepository = mockk<FileRepository>(relaxed = true)
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val endpointTokenRepository = mockk<EndpointTokenRepository>(relaxed = true)
    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val favoritesRepository = mockk<FavoritesRepository>(relaxed = true)
    private val keyRepository = mockk<KeyRepository>(relaxed = true)
    private val presetRepository = mockk<PresetRepository>(relaxed = true)
    private val promptRepository = mockk<PromptRepository>(relaxed = true)
    private val shareRepository = mockk<ShareRepository>(relaxed = true)
    private val mcpRepository = mockk<McpRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)
    private val connectivityObserver =
        mockk<com.garfiec.librechat.core.common.network.ConnectivityObserver>(relaxed = true)
    private val serverDataStore =
        mockk<com.garfiec.librechat.core.data.datastore.ServerDataStore>(relaxed = true)
    private val settingsDataStore =
        mockk<com.garfiec.librechat.core.data.datastore.SettingsDataStore>(relaxed = true)
    private val platformDelegateFactory = mockk<PlatformDelegateFactory>(relaxed = true)
    private val serverFileSelectionHandoff = mockk<ServerFileSelectionHandoff>(relaxed = true)

    private val selectionHandoff = NewChatSelectionHandoff()

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

        every { configRepository.startupConfig } returns MutableStateFlow(null)
        every { configRepository.detectedBackendVersion } returns MutableStateFlow("0.8.7")
        every { configRepository.detectedBackend } returns MutableStateFlow(null)
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
        every { configRepository.endpointConfigs } returns MutableStateFlow(emptyMap())
        every { configRepository.availableModels } returns MutableStateFlow(emptyMap())
        every { favoritesRepository.favorites } returns MutableStateFlow(emptyList())
        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())
        every { serverFileSelectionHandoff.selectionsFor(any()) } returns emptyFlow()
        every { keyRepository.keyInvalidations } returns MutableSharedFlow()
        // StateFlow.collect returns Nothing, so a relaxed mock throws in the delegate's collector.
        every { agentRepository.revision } returns MutableStateFlow(0L)
        every { platformDelegateFactory.createShareConsumer().sharesFor(any()) } returns emptyFlow()

        // `uiState` is combine(_uiState, these pref flows).stateIn(Eagerly, ChatUiState()): until
        // EVERY one of them emits, `uiState.value` is the untouched initial state no matter what
        // the ViewModel did. Relaxed mocks never emit — these stubs are load-bearing.
        every { serverDataStore.currentUrlFlow } returns MutableStateFlow("https://example.test")
        every { settingsDataStore.chatFontSize } returns MutableStateFlow(ChatFontSize.MEDIUM)
        every { settingsDataStore.starredModelsDisplay } returns MutableStateFlow(StarredModelsDisplay.OFF)
        every { settingsDataStore.chatHeaderContent } returns MutableStateFlow(ChatHeaderContent.MODEL)
        every { settingsDataStore.chatHeaderAlignment } returns MutableStateFlow(ChatHeaderAlignment.CENTER)
        every { settingsDataStore.contextBarPlacement } returns MutableStateFlow(ContextBarPlacement.HIDDEN)
        every { settingsDataStore.contextGaugeExpanded } returns MutableStateFlow(false)
        every { settingsDataStore.duringRunAction } returns MutableStateFlow(DuringRunAction.QUEUE)

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
        ChatViewModel(
            initialConversationId = "conv-1",
            initialAgentId = null,
            agentRepository = agentRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            fileRepository = fileRepository,
            resumePinStore = ResumePinStore(),
            configRepository = configRepository,
            conversationRepository = conversationRepository,
            endpointTokenRepository = endpointTokenRepository,
            draftRepository = draftRepository,
            favoritesRepository = favoritesRepository,
            keyRepository = keyRepository,
            presetRepository = presetRepository,
            promptRepository = promptRepository,
            shareRepository = shareRepository,
            mcpRepository = mcpRepository,
            userRepository = userRepository,
            roleRepository = roleRepository,
            permissionGate = permissionGate,
            connectivityObserver = connectivityObserver,
            serverDataStore = serverDataStore,
            settingsDataStore = settingsDataStore,
            platformDelegateFactory = platformDelegateFactory,
            json = Json { ignoreUnknownKeys = true },
            defaultDispatcher = testDispatcher,
            selectionHandoff = selectionHandoff,
            serverFileSelectionHandoff = serverFileSelectionHandoff,
            promptInsertionHandoff = PromptInsertionHandoff(),
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:user-1"))),
        )
}
