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
        ChatViewModel(
            initialConversationId = initialConversationId,
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
