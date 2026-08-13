package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Message
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for the context-gauge init-order crash (agents endpoint).
 *
 * The projection lives in `ContextProjectionDelegate`, whose `start()` is launched from
 * `ChatViewModel.init` and collects the state flow during construction. Once `loadFlags`
 * enables the gauge (backend >= 0.8.7), that collector fires `refreshContextProjection` ->
 * `resolveProjectionModel`, which on the AGENTS endpoint reads the delegate's
 * `resolvedAgentModels` map. The original crash was that this cache was a `ChatViewModel` `val`
 * declared *after* `init`, so its initializer hadn't run when the collector re-entered the
 * half-constructed ViewModel — the field was null and the map lookup threw a
 * `NullPointerException` that crashed the app (`FATAL EXCEPTION: main`). Homing the cache inside
 * the delegate makes that structurally impossible (the delegate — and its map — is fully
 * constructed before `init` calls `start()`); this test locks the behavior in either shape.
 *
 * The crash only reproduces when an AGENT is the selection *at construction time* (a new
 * chat handed off with an agent selected), not when the agent is applied post-construction.
 * This test constructs that state directly under an [UnconfinedTestDispatcher], which (like
 * the device's `Dispatchers.Main.immediate`) runs the init-launched collector inline during
 * construction.
 *
 * The guard is a `coVerify` that the projection reached `getAgentForEditing` — i.e. it ran
 * to completion past the `resolvedAgentModels` map lookup on the agents branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelContextProjectionInitTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fixture = ChatViewModelTestFixture()
    private val agentRepository get() = fixture.agentRepository
    private val messageRepository get() = fixture.messageRepository
    private val configRepository get() = fixture.configRepository
    private val conversationRepository get() = fixture.conversationRepository
    private val favoritesRepository get() = fixture.favoritesRepository
    private val keyRepository get() = fixture.keyRepository
    private val roleRepository get() = fixture.roleRepository
    private val settingsDataStore get() = fixture.settingsDataStore
    private val platformDelegateFactory get() = fixture.platformDelegateFactory
    private val serverFileSelectionHandoff get() = fixture.serverFileSelectionHandoff
    private val selectionHandoff get() = fixture.selectionHandoff

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fixture.stubDefaults()

        // Keep the Room read-through silent so the handoff-seeded tail message survives as the
        // projection's `displayMessages` tail — an emission here would rebuild the path. Restated
        // over the fixture default because this test depends on it.
        every { messageRepository.observeMessages(any()) } returns emptyFlow()

        // Two init-time `.first()` reads over relaxed flows (relaxed -> emptyFlow -> NoSuchElement).
        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())

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
        // agent's real model. Verifying that call proves the projection ran to completion past the
        // map lookup during construction — the point the init-order crash used to fail at.
        coVerify { agentRepository.getAgentForEditing(agentId) }
    }

    private fun newViewModel(initialConversationId: String?): ChatViewModel =
        fixture.build(
            defaultDispatcher = testDispatcher,
            initialConversationId = initialConversationId,
        )
}
