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
