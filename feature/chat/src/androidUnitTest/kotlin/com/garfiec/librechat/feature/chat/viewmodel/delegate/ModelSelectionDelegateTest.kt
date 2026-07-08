package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ModelSelectionHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMetaState
import com.garfiec.librechat.feature.chat.viewmodel.ModelSelectionState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavior tests for new-chat model-selection seeding and validation.
 *
 * These lock in three previously-fixed bugs against regression (issue #71):
 * - PR #108: first-agent flash/clobber — [loadAgents] must not auto-select for
 *   saved conversations, and no fallback path may persist last-used.
 * - PR #110: retained-landing staleness — [ModelSelectionDelegate.seedInitialSelection]
 *   re-applies a changed last-used while on the blank landing.
 * - The initial multi-writer race — [ModelSelectionDelegate.seedInitialSelection] is
 *   the single deterministic authority, with precedence
 *   last-used → first agent (AGENTS default) → first config model, and WAIT-on-
 *   unresolved so arrival order can't change the outcome.
 *
 * Mirrors [EndpointKeyStatusDelegateTest]: standalone [ChatStateHandle] over a
 * [TestScope], MockK fakes, `advanceUntilIdle()` to drain, `CompletableDeferred` in
 * `coAnswers {}` to control ordering. The seeder collects a never-completing combine
 * on the delegate's [TestScope] (not the runTest scope), so leftover collectors don't
 * fail the test — same as the template's init-block collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSelectionDelegateTest {

    private val lastUsedEndpoint = MutableStateFlow<String?>(null)
    private val lastUsedModel = MutableStateFlow<String?>(null)
    private val availableModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val endpointConfigs = MutableStateFlow<Map<String, EndpointConfig>>(emptyMap())

    private val configRepository = mockk<ConfigRepository>(relaxed = true).also {
        every { it.availableModels } returns availableModels
        every { it.endpointConfigs } returns endpointConfigs
    }
    private val agentRepository = mockk<AgentRepository>(relaxed = true)
    private val mcpRepository = mockk<McpRepository>(relaxed = true)
    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true).also {
        every { it.lastUsedEndpoint } returns lastUsedEndpoint
        every { it.lastUsedModel } returns lastUsedModel
    }
    private val permissionGate = mockk<PermissionGate>(relaxed = true)

    // Fake connectivity: tests drive offline→online transitions via [isConnected].value.
    // Starts connected so the observer's initial current-state emission is never a
    // transition (mirrors the real observer, which emits the current state on collect).
    private val isConnected = MutableStateFlow(true)
    private val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true).also {
        every { it.isConnected } returns isConnected
    }

    private fun newHandle(scope: CoroutineScope, state: ChatUiState = uiState()) =
        ChatStateHandle(stateFlow = MutableStateFlow(state), scope = scope)

    /** Builds a [ChatUiState] from the flat selection fields these tests set, wrapping them into
     *  the [ModelSelectionState] slice. */
    private fun uiState(
        conversationId: String? = null,
        selectedEndpoint: String = EndpointConstants.AGENTS,
        selectedModel: String? = null,
        agents: List<Agent> = emptyList(),
        error: String? = null,
    ) = ChatUiState(
        conversation = ConversationMetaState(conversationId = conversationId),
        error = error,
        selection = ModelSelectionState(
            selectedEndpoint = selectedEndpoint,
            selectedModel = selectedModel,
            agents = agents,
        ),
    )

    private fun newDelegate(
        handle: ChatStateHandle,
        initialAgentId: String? = null,
        initialEndpoint: String? = null,
        initialModel: String? = null,
    ) = ModelSelectionDelegate(
        handle = ModelSelectionHandle(handle),
        configRepository = configRepository,
        agentRepository = agentRepository,
        mcpRepository = mcpRepository,
        settingsDataStore = settingsDataStore,
        permissionGate = permissionGate,
        connectivityObserver = connectivityObserver,
        initialAgentId = initialAgentId,
        initialEndpoint = initialEndpoint,
        initialModel = initialModel,
    )

    private fun allowAgents() {
        coEvery { permissionGate.awaitRole() } returns UserRolePermissions(name = "user")
    }

    private fun denyAgents() {
        coEvery { permissionGate.awaitRole() } returns
            UserRolePermissions(name = "user", permissions = mapOf("AGENTS" to mapOf("USE" to false)))
    }

    // ── Group A: refilterModels validation (existing conversations) ──────────
    // refilterModels owns selection correction ONLY for existing conversations
    // (new-chat seeding moved to seedInitialSelection). Each test drives the
    // existing-conversation path: isNewConversation = false, conversationModelLoaded = true.

    @Test
    fun refilterValidSelectionIsNoOp() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = "gpt-4o"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
        assertThat(handle.state.availableModels).containsExactly("openAI", listOf("gpt-4o"))
    }

    @Test
    fun refilterInvalidSelectionWithValidLastUsedRestoresLastUsed() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = "ghost"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        delegate.cachedLastUsedEndpoint = "anthropic"
        delegate.cachedLastUsedModel = "claude-3"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"), "anthropic" to listOf("claude-3"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
    }

    @Test
    fun refilterInvalidSelectionNoLastUsedPicksFirstConfigModel() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = "ghost"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        availableModels.value = linkedMapOf("openAI" to listOf("gpt-4o"), "anthropic" to listOf("claude-3"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun refilterStaleNonAgentModelOnAgentsEndpointIsCorrected() = runTest {
        // A real model name carried into the AGENTS endpoint must be treated invalid
        // (it would be sent as a bogus agent_id and the server would reject the chat).
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = EndpointConstants.AGENTS, selectedModel = "gpt-4o"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        availableModels.value = linkedMapOf(
            EndpointConstants.AGENTS to listOf("agent_abc"),
            "openAI" to listOf("gpt-4o"),
        )

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        // Corrected away from the stale ("agents", "gpt-4o") pair to a real entry.
        assertThat(handle.state.selectedModel).isNotEqualTo("gpt-4o")
        val pair = handle.state.selectedEndpoint to handle.state.selectedModel
        assertThat(handle.state.availableModels[pair.first]).contains(pair.second)
    }

    @Test
    fun refilterAgentsModelsNotLoadedSkipsValidationNoClobber() = runTest {
        // modelsForEndpoint == null (agents list absent from availableModels) → don't clobber.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = EndpointConstants.AGENTS, selectedModel = "agent_abc"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_abc")
    }

    @Test
    fun refilterExistingConversationNoFallbackUntilConversationModelLoaded() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = null),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = false
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        // Gate held: no fallback ran.
        assertThat(handle.state.selectedModel).isNull()

        // Release the gate → fallback now corrects.
        delegate.conversationModelLoaded = true
        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun refilterFallbackDoesNotPersistLastUsed() = runTest {
        // Locks PR #108's removal of fallback persistence — refilter must never
        // write last-used. Only an explicit onModelSelected may.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = "ghost"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o") // fallback fired
        coVerify(exactly = 0) { settingsDataStore.setLastUsedModel(any(), any()) }
    }

    @Test
    fun refilterEmptyModelsIsNoOpNoFallback() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = "ghost"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        availableModels.value = emptyMap()

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedModel).isEqualTo("ghost")
        coVerify(exactly = 0) { settingsDataStore.setLastUsedModel(any(), any()) }
    }

    @Test
    fun refilterNewConversationDoesNotApplyFallback() = runTest {
        // For new chats, refilter is validation-only: it never seeds/fallbacks —
        // seedInitialSelection owns the selection. An invalid selection is left as-is.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope, uiState(selectedEndpoint = "openAI", selectedModel = "ghost"))
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true // even so, new-chat path must not fallback
        delegate.cachedLastUsedEndpoint = "anthropic"
        delegate.cachedLastUsedModel = "claude-3"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"), "anthropic" to listOf("claude-3"))

        delegate.refilterModels(isNewConversation = true)
        advanceUntilIdle()

        // Untouched by refilter (the seeder would handle it, but it isn't running here).
        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("ghost")
        // availableModels still gets filtered/published.
        assertThat(handle.state.availableModels).containsKey("openAI")
    }

    // ── Group B: loadAgents (no auto-select; sets agentsLoaded everywhere) ────

    @Test
    fun loadAgentsSuccessPopulatesAgentsSetsFlagNoSelection() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns
            Result.Success(listOf(Agent(id = "agent_1"), Agent(id = "agent_2")))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.agents).hasSize(2)
        assertThat(delegate.agentsLoaded.value).isTrue()
        // loadAgents no longer auto-selects — the seeder owns selection.
        assertThat(handle.state.selectedModel).isNull()
        coVerify(exactly = 0) { settingsDataStore.setLastUsedModel(any(), any()) }
    }

    @Test
    fun loadAgentsPermissionDeniedSkipsFetchButSetsFlag() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        denyAgents()

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { agentRepository.getAgents() }
        assertThat(handle.state.agents).isEmpty()
        assertThat(handle.state.selectedModel).isNull()
        assertThat(handle.state.error).isNull()
        // Must still flip so the seeder doesn't wait forever on the agents tier.
        assertThat(delegate.agentsLoaded.value).isTrue()
    }

    @Test
    fun loadAgentsErrorSurfacesErrorSetsFlagNoSelection() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Error(RuntimeException("boom"))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.error).isEqualTo("Could not load available agents")
        assertThat(handle.state.selectedModel).isNull()
        assertThat(handle.state.agents).isEmpty()
        assertThat(delegate.agentsLoaded.value).isTrue()
    }

    @Test
    fun retryAgentsIfFailedRefetchesAfterErrorThenPopulates() = runTest {
        // The cold-start-failure fix: a transient getAgents() error leaves the list empty.
        // retryAgentsIfFailed (wired to opening the selector) must re-fetch and fill it.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Error(RuntimeException("boom"))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()
        assertThat(handle.state.agents).isEmpty()

        // Network recovers; the retry re-hits it (the error was never cached) and populates.
        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))
        delegate.retryAgentsIfFailed(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.agents).hasSize(1)
        coVerify(exactly = 2) { agentRepository.getAgents() }
    }

    @Test
    fun retryAgentsSuccessClearsFailureBanner() = runTest {
        // A successful retry must clear the "Could not load available agents" banner
        // the failed attempt published — otherwise the sheet shows the populated list
        // alongside a stale error.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Error(RuntimeException("boom"))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()
        assertThat(handle.state.error).isEqualTo("Could not load available agents")

        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))
        delegate.retryAgentsIfFailed(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.error).isNull()
        assertThat(handle.state.agents).hasSize(1)
    }

    @Test
    fun loadAgentsSuccessPreservesUnrelatedError() = runTest {
        // The error slot is shared with other delegates — a successful agent load must
        // clear only its own failure banner, never someone else's message.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope, uiState(error = "Failed to rename conversation"))
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.error).isEqualTo("Failed to rename conversation")
    }

    @Test
    fun retryAgentsIfFailedIsNoOpAfterSuccess() = runTest {
        // A successful load must not be re-fetched on selector open (no needless network).
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()
        delegate.retryAgentsIfFailed(isNewConversation = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { agentRepository.getAgents() }
    }

    @Test
    fun retryAgentsIfFailedIsNoOpForCleanEmptyAccount() = runTest {
        // A cleanly-resolved zero-agent account (success, empty list) must not re-fetch —
        // only genuine errors are retried.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Success(emptyList())

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()
        delegate.retryAgentsIfFailed(isNewConversation = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { agentRepository.getAgents() }
    }

    @Test
    fun agentsLoadFailedRetriesOnConnectivityRegained() = runTest {
        // The cold-start-failure fix: a failed load auto-retries on an offline→online
        // transition, so the user doesn't have to open the selector to recover.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        isConnected.value = true
        coEvery { agentRepository.getAgents() } returns Result.Error(RuntimeException("boom"))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()
        assertThat(handle.state.agents).isEmpty()

        // Network drops then recovers; the offline→online transition re-fetches.
        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))
        isConnected.value = false
        advanceUntilIdle()
        isConnected.value = true
        advanceUntilIdle()

        assertThat(handle.state.agents).hasSize(1)
        assertThat(handle.state.error).isNull()
        coVerify(exactly = 2) { agentRepository.getAgents() }
    }

    @Test
    fun agentsLoadFailedDoesNotRetryWhileStayingConnected() = runTest {
        // Transition-detection guard: a failure while already online (server unreachable /
        // DNS broken) must NOT retry without an offline→online transition — otherwise a
        // persistently-connected-but-failing device would spin a tight retry loop.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        isConnected.value = true
        coEvery { agentRepository.getAgents() } returns Result.Error(RuntimeException("boom"))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        // Stays connected the whole time — no transition, so no auto-retry.
        isConnected.value = true
        advanceUntilIdle()

        coVerify(exactly = 1) { agentRepository.getAgents() }
    }

    @Test
    fun successfulLoadDoesNotRetryOnLaterReconnect() = runTest {
        // A successful load must not leave a reconnect observer running — a later
        // offline→online transition must not trigger a needless re-fetch.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        allowAgents()
        isConnected.value = true
        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        isConnected.value = false
        advanceUntilIdle()
        isConnected.value = true
        advanceUntilIdle()

        coVerify(exactly = 1) { agentRepository.getAgents() }
    }

    // ── Group C: seedInitialSelection precedence + race determinism ──────────

    @Test
    fun seedModelOverrideWinsOverLastUsed() = runTest {
        // A home-screen model shortcut passes an explicit (endpoint, model) that must win over a
        // stored last-used, mirroring the agent override's tier-0 precedence.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle, initialEndpoint = "openAI", initialModel = "gpt-4o")
        lastUsedEndpoint.value = "anthropic"
        lastUsedModel.value = "claude-3.5-sonnet"
        availableModels.value = mapOf(
            "openAI" to listOf("gpt-4o"),
            "anthropic" to listOf("claude-3.5-sonnet"),
        )

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")

        // A later agents-loaded emission must not clobber the explicitly-launched model.
        delegate.agentsLoaded.value = true
        advanceUntilIdle()
        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun seedLastUsedValidSeededWhenModelsArriveFirst() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"), EndpointConstants.AGENTS to listOf("agent_1"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        // Models present, agents arrive later — last-used must win and stay.
        delegate.agentsLoaded.value = true
        handle.update { copy(selection = selection.copy(agents = listOf(Agent(id = "agent_1")))) }
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun seedLastUsedValidWaitsForModelsWhenAgentsArriveFirst() = runTest {
        // The determinism proof: even if agents load first, a config-endpoint
        // last-used wins once its models arrive — tier 1 WAITs rather than letting
        // tier 2 grab the first agent.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        // Agents arrive first, models still pending → must NOT pick the first agent.
        delegate.agentsLoaded.value = true
        handle.update { copy(selection = selection.copy(agents = listOf(Agent(id = "agent_1")))) }
        advanceUntilIdle()
        assertThat(handle.state.selectedModel).isNull()

        // Now models arrive → last-used resolves and wins.
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))
        advanceUntilIdle()
        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun seedNoLastUsedAgentsDefaultPrefersFirstAgentOverConfigModel() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope) // default endpoint = AGENTS
        val delegate = newDelegate(handle)
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        // Config model present but agents pending → tier 2 WAITs, no config model grabbed.
        assertThat(handle.state.selectedModel).isNull()

        delegate.agentsLoaded.value = true
        handle.update { copy(selection = selection.copy(agents = listOf(Agent(id = "agent_1")))) }
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_1")
    }

    @Test
    fun seedNoLastUsedAgentsEmptyFallsToFirstConfigModelNoDeadlock() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope) // default endpoint = AGENTS
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true // agents loaded and empty (e.g. denied / none configured)
        availableModels.value = linkedMapOf("openAI" to listOf("gpt-4o"), "anthropic" to listOf("claude-3"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun seedLastUsedInvalidDoesNotSeedStaleFallsThroughPrecedence() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope) // default endpoint = AGENTS
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-OLD" // no longer offered
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))
        handle.update { copy(selection = selection.copy(agents = listOf(Agent(id = "agent_1")))) }

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedModel).isNotEqualTo("gpt-OLD")
        // AGENTS is the active default → falls through to the first-agent tier.
        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_1")
    }

    @Test
    fun seedRetainedLandingReappliesLastUsedWhenConversationIdNull() = runTest {
        // Locks PR #110: a changed last-used re-applies on the retained blank landing.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o", "gpt-4o-mini"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")

        // User picked a different model elsewhere → last-used changes.
        lastUsedModel.value = "gpt-4o-mini"
        advanceUntilIdle()

        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o-mini")
    }

    @Test
    fun seedStartedConversationDoesNotOverrideOnLastUsedChange() = runTest {
        // Locks the #110 gate: once a conversation has started (conversationId != null),
        // a later last-used change must not override the in-conversation selection.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o", "gpt-4o-mini"), "anthropic" to listOf("claude-3"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")

        // Conversation starts with its own model.
        handle.update { copy(conversation = conversation.copy(conversationId = "conv_new"), selection = selection.copy(selectedEndpoint = "anthropic", selectedModel = "claude-3")) }
        advanceUntilIdle()

        // Last-used changes afterwards → must NOT override the started conversation.
        lastUsedModel.value = "gpt-4o-mini"
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
    }

    @Test
    fun seedDoesNotPersistLastUsed() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
        coVerify(exactly = 0) { settingsDataStore.setLastUsedModel(any(), any()) }
    }

    @Test
    fun seedNoOpForExistingConversation() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope, uiState(conversationId = "c1", selectedModel = "claude-3", selectedEndpoint = "anthropic"))
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = false)
        advanceUntilIdle()

        // Existing conversation: loadConversationModel owns the model; seeder no-ops.
        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
    }

    @Test
    fun seedAgentsDeniedFallsThroughToFirstConfigModel() = runTest {
        // Regression guard: with AGENTS denied, loadAgents flips agentsLoaded with an
        // empty agent list (no published state change). Because agentsLoaded is a
        // combine arm, the seeder re-fires and falls through the agents tier to the
        // first config model instead of waiting on it forever (no model on landing).
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope) // default endpoint = AGENTS
        val delegate = newDelegate(handle)
        denyAgents()
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = true)
        delegate.loadAgents(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun seedAgentsErrorFallsThroughToFirstConfigModel() = runTest {
        // Regression guard: a failed getAgents() flips agentsLoaded but only changes
        // state.error (agents stays empty). The seeder must still re-fire (agentsLoaded
        // is a combine arm, not filtered out by the agents distinctUntilChanged) and
        // fall through to the first config model.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope) // default endpoint = AGENTS
        val delegate = newDelegate(handle)
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Error(RuntimeException("boom"))
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = true)
        delegate.loadAgents(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun seedRemovedEndpointSelectionFallsThroughToConfigModel() = runTest {
        // An endpoint absent from a loaded endpointConfigs is treated as removed
        // (INVALID), not still-loading (PENDING), so the seeder corrects a stale
        // selection instead of pinning a non-functional one forever.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope, uiState(selectedEndpoint = "openAI", selectedModel = "gpt-4o"))
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        endpointConfigs.value = mapOf("anthropic" to EndpointConfig())
        availableModels.value = mapOf("anthropic" to listOf("claude-3"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
    }

    @Test
    fun seedExistingConversationCachesLastUsedForFallback() = runTest {
        // The seeder's collector runs for existing conversations too — only to keep
        // cachedLastUsed* fresh for refilterModels' corrective fallback. It must not
        // touch the selection (loadConversationModel owns that), but must populate the
        // cache the old eager read used to fill for every conversation.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "anthropic", selectedModel = "claude-3"),
        )
        val delegate = newDelegate(handle)
        delegate.agentsLoaded.value = true
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
        assertThat(delegate.cachedLastUsedEndpoint).isEqualTo("openAI")
        assertThat(delegate.cachedLastUsedModel).isEqualTo("gpt-4o")
    }

    // ── Group D: onModelSelected (the one legitimate writer) ─────────────────

    @Test
    fun onModelSelectedUpdatesStatePersistsAndSyncsCache() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle)

        delegate.onModelSelected("anthropic", "claude-3")
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
        assertThat(delegate.cachedLastUsedEndpoint).isEqualTo("anthropic")
        assertThat(delegate.cachedLastUsedModel).isEqualTo("claude-3")
        coVerify(exactly = 1) { settingsDataStore.setLastUsedModel("anthropic", "claude-3") }
    }

    // ── Group D: Tier-0 explicit agent override (start chat from an agent) ──

    @Test
    fun seedTier0AgentOverrideBeatsLastUsed() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle, initialAgentId = "agent_X")
        // A NON-agents last-used (the precondition that caused the original clobber bug).
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"), EndpointConstants.AGENTS to listOf("agent_X"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        // First emission: the explicit agent override wins over the valid last-used.
        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_X")
    }

    @Test
    fun seedTier0AgentOverrideNotClobberedWhenAgentsListLoadsLater() = runTest {
        // Regression for the override-clobber defect: after the override applies the
        // agent, the agents list finishing loading re-emits the seeder; the last-used
        // re-sync must NOT swap the agent back to the (non-agent) last-used model.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle, initialAgentId = "agent_X")
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"), EndpointConstants.AGENTS to listOf("agent_X"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        // SECOND emission: agents list loads (post-create refetch surfaces the agent).
        delegate.agentsLoaded.value = true
        handle.update { copy(selection = selection.copy(agents = listOf(Agent(id = "agent_X")))) }
        advanceUntilIdle()

        // The override must STILL hold — not clobbered back to last-used gpt-4o.
        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_X")
    }

    @Test
    fun seedTier0OverrideIsOneShotThenFollowsNewLastUsed() = runTest {
        // The override pins the CURRENT last-used so the re-sync no-ops; but a genuinely
        // NEW last-used picked later (different value) must still re-sync (retained-landing).
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle, initialAgentId = "agent_X")
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o", "gpt-4.1"), EndpointConstants.AGENTS to listOf("agent_X"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()
        assertThat(handle.state.selectedModel).isEqualTo("agent_X")

        // User picks a DIFFERENT model elsewhere → last-used changes → re-sync follows it.
        lastUsedModel.value = "gpt-4.1"
        advanceUntilIdle()
        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4.1")
    }

    @Test
    fun seedTier0AgentSurvivesTransientEmptyAgentsListEmission() = runTest {
        // The transient-empty-list bug: Tier-0 applies the agent, the one-shot override
        // clears, then the agents flow emits (agentsLoaded=true, count=0) BEFORE the
        // real list. A loaded-but-empty list must NOT be treated as "agent deleted" and
        // clobber the selection down to a default model — it's "not ready" (PENDING).
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = newDelegate(handle, initialAgentId = "agent_X")
        lastUsedEndpoint.value = "openAI"
        lastUsedModel.value = "gpt-4o"
        // Config models present; agents endpoint key present but NOT yet populated in state.
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"), EndpointConstants.AGENTS to listOf("agent_X"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()
        // Tier-0 applied the agent.
        assertThat(handle.state.selectedModel).isEqualTo("agent_X")

        // TRANSIENT empty-list emission: agentsLoaded flips true with an EMPTY agents list.
        delegate.agentsLoaded.value = true
        handle.update { copy(selection = selection.copy(agents = emptyList())) }
        advanceUntilIdle()
        // Must STILL be the agent (today's bug flipped it to openAI/gpt-4o via Tier-3/last-used).
        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_X")

        // Real list arrives (contains the agent) → selection holds.
        handle.update { copy(selection = selection.copy(agents = listOf(Agent(id = "agent_X"), Agent(id = "agent_Y")))) }
        advanceUntilIdle()
        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_X")
    }

    @Test
    fun seedZeroAgentLandingResolvesToConfigModelNotModelLess() = runTest {
        // Regression guard: the empty-but-loaded agents list is now PENDING (hold),
        // but a GENUINELY zero-agent account (NO Tier-0 override, stale last-used pointed
        // at AGENTS) must NOT hold forever and sit model-less — it must fall through to a
        // config model. Distinguished from the hold case by holdAgentForPopulate being false.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        // Start already on the agents endpoint with a stale agent id (as a retained landing
        // whose last-used resolved to agents before the account's agents were all removed).
        val handle = newHandle(
            scope,
            uiState(selectedEndpoint = EndpointConstants.AGENTS, selectedModel = "agent_stale"),
        )
        val delegate = newDelegate(handle) // NO initialAgentId → no hold
        lastUsedEndpoint.value = EndpointConstants.AGENTS
        lastUsedModel.value = "agent_stale"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.seedInitialSelection(isNewConversation = true)
        advanceUntilIdle()

        // Agents finish loading and the list is genuinely empty.
        delegate.agentsLoaded.value = true
        handle.update { copy(selection = selection.copy(agents = emptyList())) }
        advanceUntilIdle()

        // Must NOT be stranded on the (nonexistent) agent — resolve to a config model.
        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    // ── Group E: conversation-model resolution + agents corrective fallback ───
    // Covers the new-chat-display-race fix (the handoff applies via
    // applyResolvedConversationModel) and the agents hole in the existing-
    // conversation corrective fallback.

    @Test
    fun applyResolvedConversationModelSetsSelectionAndFlagsAndRefilterDoesNotClobber() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope, uiState(conversationId = "c1"))
        val delegate = newDelegate(handle)

        delegate.applyResolvedConversationModel("anthropic", "claude-3")

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
        assertThat(delegate.conversationModelLoaded).isTrue()
        assertThat(delegate.conversationModelResolved).isTrue()

        // A subsequent existing-conversation refilter must leave the resolved selection alone.
        availableModels.value = mapOf("anthropic" to listOf("claude-3"), "openAI" to listOf("gpt-4o"))
        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("anthropic")
        assertThat(handle.state.selectedModel).isEqualTo("claude-3")
        coVerify(exactly = 0) { settingsDataStore.setLastUsedModel(any(), any()) }
    }

    @Test
    fun refilterCorrectiveFallbackRestoresAgentsLastUsedWhenPresent() = runTest {
        // Regression for the dead agents branch: an agents last-used was validated against
        // filtered["agents"] (always null) and silently skipped to a config model. It must
        // now validate against the loaded agents list and be restored.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(
                conversationId = "c1",
                selectedEndpoint = "openAI",
                selectedModel = "ghost",
                agents = listOf(Agent(id = "agent_1")),
            ),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        delegate.agentsLoaded.value = true
        delegate.cachedLastUsedEndpoint = EndpointConstants.AGENTS
        delegate.cachedLastUsedModel = "agent_1"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_1")
    }

    @Test
    fun refilterCorrectiveFallbackAgentsLastUsedAbsentPicksFirstConfigModel() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(
                conversationId = "c1",
                selectedEndpoint = "openAI",
                selectedModel = "ghost",
                agents = listOf(Agent(id = "agent_1")),
            ),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        delegate.agentsLoaded.value = true
        delegate.cachedLastUsedEndpoint = EndpointConstants.AGENTS
        delegate.cachedLastUsedModel = "agent_missing" // not in the loaded list
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("gpt-4o")
    }

    @Test
    fun refilterCorrectiveFallbackHoldsUntilAgentsLoadedThenRestores() = runTest {
        // Agents not loaded → the corrective fallback must HOLD (not pick a config model),
        // then loadAgents' re-run of refilter restores the agents last-used.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(
            scope,
            uiState(conversationId = "c1", selectedEndpoint = "openAI", selectedModel = "ghost"),
        )
        val delegate = newDelegate(handle)
        delegate.conversationModelLoaded = true
        delegate.cachedLastUsedEndpoint = EndpointConstants.AGENTS
        delegate.cachedLastUsedModel = "agent_1"
        availableModels.value = mapOf("openAI" to listOf("gpt-4o"))
        allowAgents()
        coEvery { agentRepository.getAgents() } returns Result.Success(listOf(Agent(id = "agent_1")))

        delegate.refilterModels(isNewConversation = false)
        advanceUntilIdle()
        // Held: agents unloaded, so neither last-used nor first-model was applied.
        assertThat(handle.state.selectedEndpoint).isEqualTo("openAI")
        assertThat(handle.state.selectedModel).isEqualTo("ghost")

        delegate.loadAgents(isNewConversation = false)
        advanceUntilIdle()

        assertThat(handle.state.selectedEndpoint).isEqualTo(EndpointConstants.AGENTS)
        assertThat(handle.state.selectedModel).isEqualTo("agent_1")
    }
}
