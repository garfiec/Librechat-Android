package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
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
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformDelegateFactory
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for the context-gauge init-order crash (agents endpoint).
 *
 * `observeContextProjection()` launches a collector on `_uiState` during construction.
 * Once `loadFlags` enables the gauge (backend >= 0.8.7), that collector fires
 * `refreshContextProjection` -> `resolveProjectionModel`, which on the AGENTS endpoint
 * reads the `resolvedAgentModels` map. If that `val` is declared *after* the `init`
 * block, its initializer hasn't run when the collector re-enters the half-constructed
 * ViewModel, so the field is still null and the map lookup throws a `NullPointerException`
 * that crashes the app (surfaced via the coroutine's uncaught-exception path -> the
 * device sees `FATAL EXCEPTION: main`).
 *
 * The crash only reproduces when an AGENT is the selection *at construction time* (a new
 * chat handed off with an agent selected), not when the agent is applied post-construction.
 * This test constructs that state directly under an [UnconfinedTestDispatcher], which (like
 * the device's `Dispatchers.Main.immediate`) runs the init-launched collectors inline during
 * construction.
 *
 * The guard is a `coVerify` that the projection reached `getAgentForEditing` — i.e. it ran
 * to completion past the `resolvedAgentModels` map lookup on the agents branch. With the
 * init-order bug the map is null when the collector re-enters mid-construction, so the lookup
 * throws before that call and the verification fails; with the fix it passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelContextProjectionInitTest {

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
    private val connectivityObserver = mockk<com.garfiec.librechat.core.common.network.ConnectivityObserver>(relaxed = true)
    private val serverDataStore = mockk<com.garfiec.librechat.core.data.datastore.ServerDataStore>(relaxed = true)
    private val settingsDataStore = mockk<com.garfiec.librechat.core.data.datastore.SettingsDataStore>(relaxed = true)
    private val platformDelegateFactory = mockk<PlatformDelegateFactory>(relaxed = true)
    private val serverFileSelectionHandoff = mockk<ServerFileSelectionHandoff>(relaxed = true)

    private val selectionHandoff = NewChatSelectionHandoff()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Enable the context gauge: interface flag defaults true, backend gate needs >= 0.8.7.
        every { configRepository.startupConfig } returns MutableStateFlow(null)
        every { configRepository.detectedBackendVersion } returns MutableStateFlow("0.8.7")
        every { roleRepository.userPermissions } returns MutableStateFlow(null)

        // Collection-typed flows read by init-time delegates (model refilter, favorites): relaxed
        // mockk hands back a bare Object for the erased element type, which the delegates cast to
        // Map/List. Feed empty containers so those paths run cleanly (irrelevant to the gauge).
        every { configRepository.endpointConfigs } returns MutableStateFlow(emptyMap())
        every { configRepository.availableModels } returns MutableStateFlow(emptyMap())
        every { favoritesRepository.favorites } returns MutableStateFlow(emptyList())

        // Two init-time `.first()` reads over relaxed flows (relaxed -> emptyFlow -> NoSuchElement).
        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())

        // Keep the Room read-through silent so the handoff-seeded tail message survives as the
        // projection's `displayMessages` tail (an emission here would rebuild the path).
        every { messageRepository.observeMessages(any()) } returns emptyFlow()
        every { serverFileSelectionHandoff.selectionsFor(any()) } returns emptyFlow()
        // Init-time collectors over relaxed SharedFlows: `SharedFlow.collect` returns `Nothing`,
        // so a relaxed mock throws KotlinNothingValueException. Feed real never-emitting flows.
        // (All unrelated to the gauge path under test.)
        every { keyRepository.keyInvalidations } returns MutableSharedFlow()
        every { platformDelegateFactory.createShareConsumer().shareAvailable } returns MutableSharedFlow()

        // On the agents branch `resolveProjectionModel` falls through the (empty) `resolvedAgentModels`
        // cache to the agent-detail fetch; a benign Error keeps the path deterministic and network-free.
        coEvery { agentRepository.getAgentForEditing(any()) } returns Result.Error(message = "test")

        // Pin the AGENTS selection: `init` -> loadConversationModel -> getConversation. A non-Success
        // result makes `applyConversationModel` a no-op, so the handoff's AGENTS selection survives to
        // the projection. Left to relaxed mockk this is load-bearing but implicit — an Error keeps the
        // precondition explicit so a change in relaxed handling of the sealed Result can't silently
        // flip the selection off AGENTS and fail this test on correct code.
        coEvery { conversationRepository.getConversation(any(), any()) } returns Result.Error(message = "test")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun contextProjectionOnAgentsEndpointDoesNotCrashDuringConstruction() = runTest(testDispatcher) {
        val conversationId = "conv-1"
        val agentId = "agent_test_1"
        // Stage the landing -> Chat(id) handoff exactly as the send path does: an agent selection
        // plus the optimistic user message, so the resumed VM lands on the AGENTS endpoint with a
        // message tail (both required for the projection to reach the agents-branch map lookup).
        selectionHandoff.put(
            conversationId = conversationId,
            endpoint = EndpointConstants.AGENTS,
            model = agentId,
            optimisticUserMessage = Message(
                messageId = "user-msg-1",
                conversationId = conversationId,
                isCreatedByUser = true,
                text = "hello",
            ),
        )

        // UnconfinedTestDispatcher runs the init-launched collectors *inline during construction*
        // (like the device's Dispatchers.Main.immediate) — the condition the bug needs. A
        // StandardTestDispatcher would defer them until after the ctor returns, by which point the
        // field is initialized, so the bug wouldn't reproduce.
        newViewModel(initialConversationId = conversationId)
        advanceUntilIdle()

        // The context projection reaches `resolveProjectionModel` on the agents branch, falls
        // through the (empty) `resolvedAgentModels` cache, and calls `getAgentForEditing` for the
        // agent's real model. Verifying that call proves the projection ran to completion.
        //
        // This is the regression guard: with the init-order bug (the map declared after `init`),
        // `resolvedAgentModels` is null when the collector re-enters mid-construction, so the map
        // lookup throws before this call is ever reached and the verification fails.
        coVerify { agentRepository.getAgentForEditing(agentId) }
    }

    private fun newViewModel(initialConversationId: String?): ChatViewModel =
        ChatViewModel(
            initialConversationId = initialConversationId,
            initialAgentId = null,
            agentRepository = agentRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            fileRepository = fileRepository,
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
            activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:user-1"))),
        )
}
