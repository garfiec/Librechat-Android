package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
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
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformDelegateFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json

/**
 * The collaborators of a [ChatViewModel] under test, and the stubs it cannot be constructed
 * without.
 *
 * Every `ChatViewModel` test builds its subject from here, so adding a collaborator to the 30-odd
 * constructor arguments stays a one-file change.
 *
 * Mocks are `relaxed`, but the stubs in [stubDefaults] are not optional decoration:
 * - `uiState` is `combine(_uiState, <pref flows>).stateIn(Eagerly, ChatUiState())`, so until every
 *   preference flow has emitted, `uiState.value` is the untouched initial state no matter what the
 *   ViewModel did — an unstubbed pref makes a test assert against a value nothing produced.
 * - `StateFlow.collect` returns `Nothing`, which a relaxed mock cannot fabricate, so an unstubbed
 *   revision flow throws inside whichever collector reaches it first.
 *
 * A test that needs a different value just re-stubs after `stubDefaults()`; the later `every` wins.
 */
internal class ChatViewModelTestFixture {

    val agentRepository = mockk<AgentRepository>(relaxed = true)
    val chatRepository = mockk<ChatRepository>(relaxed = true)
    val messageRepository = mockk<MessageRepository>(relaxed = true)
    val fileRepository = mockk<FileRepository>(relaxed = true)
    val configRepository = mockk<ConfigRepository>(relaxed = true)
    val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    val endpointTokenRepository = mockk<EndpointTokenRepository>(relaxed = true)
    val draftRepository = mockk<DraftRepository>(relaxed = true)
    val favoritesRepository = mockk<FavoritesRepository>(relaxed = true)
    val keyRepository = mockk<KeyRepository>(relaxed = true)
    val presetRepository = mockk<PresetRepository>(relaxed = true)
    val promptRepository = mockk<PromptRepository>(relaxed = true)
    val shareRepository = mockk<ShareRepository>(relaxed = true)
    val mcpRepository = mockk<McpRepository>(relaxed = true)
    val userRepository = mockk<UserRepository>(relaxed = true)
    val roleRepository = mockk<RoleRepository>(relaxed = true)
    val permissionGate = mockk<PermissionGate>(relaxed = true)
    val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true)
    val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    val platformDelegateFactory = mockk<PlatformDelegateFactory>(relaxed = true)
    val serverFileSelectionHandoff = mockk<ServerFileSelectionHandoff>(relaxed = true)

    val selectionHandoff = NewChatSelectionHandoff()

    /** Driven by hand where a test needs a prompt mutation to reach a retained ViewModel. */
    val promptRevision = MutableStateFlow(0L)

    fun stubDefaults() {
        every { configRepository.startupConfig } returns MutableStateFlow(null)
        every { configRepository.detectedBackendVersion } returns MutableStateFlow("0.8.7")
        every { configRepository.detectedBackend } returns MutableStateFlow(null)
        every { configRepository.endpointConfigs } returns MutableStateFlow(emptyMap())
        every { configRepository.availableModels } returns MutableStateFlow(emptyMap())
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
        every { favoritesRepository.favorites } returns MutableStateFlow(emptyList())
        every { keyRepository.keyInvalidations } returns MutableSharedFlow()
        every { agentRepository.revision } returns MutableStateFlow(0L)
        every { promptRepository.revision } returns promptRevision
        every { messageRepository.observeMessages(any()) } returns emptyFlow()
        every { serverFileSelectionHandoff.selectionsFor(any()) } returns emptyFlow()
        every { platformDelegateFactory.createShareConsumer().sharesFor(any()) } returns emptyFlow()

        every { serverDataStore.currentUrlFlow } returns MutableStateFlow("https://example.test")
        every { settingsDataStore.selectedMcpServers } returns MutableStateFlow(emptySet())
        every { settingsDataStore.enabledTools } returns MutableStateFlow(emptySet())
        every { settingsDataStore.chatFontSize } returns MutableStateFlow(ChatFontSize.MEDIUM)
        every { settingsDataStore.starredModelsDisplay } returns MutableStateFlow(StarredModelsDisplay.OFF)
        every { settingsDataStore.chatHeaderContent } returns MutableStateFlow(ChatHeaderContent.MODEL)
        every { settingsDataStore.chatHeaderAlignment } returns MutableStateFlow(ChatHeaderAlignment.CENTER)
        every { settingsDataStore.contextBarPlacement } returns MutableStateFlow(ContextBarPlacement.HIDDEN)
        every { settingsDataStore.contextGaugeExpanded } returns MutableStateFlow(false)
        every { settingsDataStore.duringRunAction } returns MutableStateFlow(DuringRunAction.QUEUE)
    }

    fun build(
        defaultDispatcher: CoroutineDispatcher,
        initialConversationId: String? = null,
        initialAgentId: String? = null,
        initialIsTemporary: Boolean = false,
        activeAccountProvider: ActiveAccountProvider =
            InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:user-1"))),
    ): ChatViewModel = ChatViewModel(
        initialConversationId = initialConversationId,
        initialAgentId = initialAgentId,
        initialIsTemporary = initialIsTemporary,
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
        defaultDispatcher = defaultDispatcher,
        selectionHandoff = selectionHandoff,
        serverFileSelectionHandoff = serverFileSelectionHandoff,
        promptInsertionHandoff = PromptInsertionHandoff(),
        activeAccountProvider = activeAccountProvider,
    )
}
