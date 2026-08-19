package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.request.UpdateAgentRequest
import com.garfiec.librechat.core.model.response.AgentListResponse
import com.garfiec.librechat.core.network.api.AgentsApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Carrying the LIST route's `isEditable` to the screens that need it.
 *
 * Upstream stamps the field in `getListAgents` alone — `GET /api/agents/:id` and `/expanded` never
 * carry it — so a detail screen can only learn the answer if the list read remembered it. Nothing
 * fails loudly when it does not: the verdict is simply null forever and every consumer's "narrow on
 * an explicit false" reduces to a no-op.
 */
class AgentRepositoryEditVerdictTest {

    private val agentsApi = mockk<AgentsApi>()

    private fun repository(provider: InMemoryActiveAccountProvider = InMemoryActiveAccountProvider()) =
        AgentRepositoryImpl(agentsApi = agentsApi, activeAccountProvider = provider)

    @Test
    fun `a paginated list read records the verdict its rows carry`() = runTest {
        coEvery { agentsApi.getAgentsPaginated(any(), any(), any(), any()) } returns AgentListResponse(
            data = listOf(
                Agent(id = "a1", isEditable = true),
                Agent(id = "a2", isEditable = false),
            ),
        )
        val repository = repository()

        repository.getAgentsPaginated(page = 1, limit = 10)

        assertThat(repository.listedEditVerdict("a1")).isTrue()
        assertThat(repository.listedEditVerdict("a2")).isFalse()
    }

    @Test
    fun `the unpaginated list read records them too`() = runTest {
        coEvery { agentsApi.getAgents(null) } returns
            AgentListResponse(data = listOf(Agent(id = "a1", isEditable = false)))
        val repository = repository()

        repository.getAgents(category = null)

        assertThat(repository.listedEditVerdict("a1")).isFalse()
    }

    /** An older server sends no field. Recording `false` there would deny edit to every owner. */
    @Test
    fun `a row without the field records nothing`() = runTest {
        coEvery { agentsApi.getAgents(null) } returns AgentListResponse(data = listOf(Agent(id = "a1")))
        val repository = repository()

        repository.getAgents(category = null)

        assertThat(repository.listedEditVerdict("a1")).isNull()
    }

    @Test
    fun `an agent never listed has no verdict`() = runTest {
        assertThat(repository().listedEditVerdict("a1")).isNull()
    }

    /** A mutation can carry an ACL change, so the previous list's answers stop being evidence. */
    @Test
    fun `a mutation drops the recorded verdicts`() = runTest {
        coEvery { agentsApi.getAgents(null) } returns
            AgentListResponse(data = listOf(Agent(id = "a1", isEditable = false)))
        coEvery { agentsApi.updateAgent("a1", any()) } returns Agent(id = "a1")
        val repository = repository()
        repository.getAgents(category = null)

        repository.updateAgent("a1", UpdateAgentRequest())

        assertThat(repository.listedEditVerdict("a1")).isNull()
    }

    /**
     * Agents have no Room/accountId scoping, so this map is an isolation tier of its own: account
     * B reading A's verdict would be told it may edit an agent it cannot touch.
     */
    @Test
    fun `a verdict recorded for one account is not served to another`() = runTest {
        coEvery { agentsApi.getAgents(null) } returns
            AgentListResponse(data = listOf(Agent(id = "a1", isEditable = true)))
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:userA")))
        val repository = repository(provider)
        repository.getAgents(category = null)

        provider.set(AccountId("srv:userB"))

        assertThat(repository.listedEditVerdict("a1")).isNull()
    }
}
