package com.garfiec.librechat.feature.agents.viewmodel

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.model.Agent
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val agentRepository = mockk<AgentRepository>(relaxed = true)
    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)

    private val agent = Agent(id = "agent-1", name = "Coding Assistant")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Stubbed explicitly because a relaxed mock answers `false` for a nullable Boolean, which
        // this screen reads as the list denying edit — the opposite of "no list answer".
        coEvery { agentRepository.listedEditVerdict(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun forbidden() = Result.Error(exception = ApiException(statusCode = 403, message = "Forbidden"))

    private fun createViewModel() = AgentDetailViewModel(
        agentRepository = agentRepository,
        serverDataStore = serverDataStore,
        initialAgentId = "agent-1",
    )

    @Test
    fun `canEdit is true when the edit-gated expanded fetch succeeds`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns Result.Success(agent)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.agent).isNotNull()
        assertThat(state.canEdit).isTrue()
    }

    @Test
    fun `canEdit is false when the edit endpoint returns a 403 and view fetch succeeds`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns forbidden()
        coEvery { agentRepository.getAgent("agent-1") } returns Result.Success(agent)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.agent).isNotNull()
        assertThat(state.canEdit).isFalse()
    }

    @Test
    fun `canEdit stays true when the edit endpoint fails transiently but view fetch succeeds`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns
            Result.Error(exception = ApiException(statusCode = 500, message = "Server error"))
        coEvery { agentRepository.getAgent("agent-1") } returns Result.Success(agent)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.agent).isNotNull()
        assertThat(state.canEdit).isTrue()
    }

    /**
     * The list is the only place `isEditable` is stamped (upstream `getListAgents`), so the verdict
     * has to reach the detail screen through the repository — reading it off the agent this screen
     * fetched leaves the conjunct permanently null and narrows nothing on any server.
     */
    @Test
    fun `canEdit is false when the list said this agent is not editable`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns Result.Success(agent)
        coEvery { agentRepository.listedEditVerdict("agent-1") } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.agent).isNotNull()
        assertThat(viewModel.uiState.value.canEdit).isFalse()
    }

    /**
     * Absence is UNKNOWN, not permission — but it is also not denial: every server that predates
     * the field answers null for every agent, so the probe has to stay in charge there.
     */
    @Test
    fun `an unknown list verdict leaves the probe in charge`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns Result.Success(agent)
        coEvery { agentRepository.listedEditVerdict("agent-1") } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.canEdit).isTrue()
    }

    /** A list verdict cannot GRANT edit either: the 403 probe still decides against it. */
    @Test
    fun `a list verdict of true does not override a forbidden probe`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns forbidden()
        coEvery { agentRepository.getAgent("agent-1") } returns Result.Success(agent)
        coEvery { agentRepository.listedEditVerdict("agent-1") } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.canEdit).isFalse()
    }

    /** `getAgent` can serve a list-projection row from the cache, which carries its own answer. */
    @Test
    fun `the agent's own field wins over the recorded list verdict`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns
            Result.Success(agent.copy(isEditable = false))
        coEvery { agentRepository.listedEditVerdict("agent-1") } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.canEdit).isFalse()
    }

    @Test
    fun `canEdit is false when the agent fails to load entirely`() = runTest(testDispatcher) {
        coEvery { agentRepository.getAgentForEditing("agent-1") } returns forbidden()
        coEvery { agentRepository.getAgent("agent-1") } returns Result.Error(message = "Not found")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.agent).isNull()
        assertThat(state.error).isNotNull()
        assertThat(state.canEdit).isFalse()
    }
}
