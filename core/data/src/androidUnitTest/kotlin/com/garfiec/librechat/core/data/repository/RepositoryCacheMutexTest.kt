package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.Preset
import com.garfiec.librechat.core.model.response.AgentListResponse
import com.garfiec.librechat.core.network.api.AgentsApi
import com.garfiec.librechat.core.network.api.PresetsApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryCacheMutexTest {

    @Test
    fun `AgentRepositoryImpl - two concurrent getAgents on cold cache hit the API exactly once`() =
        runTest {
            val agentsApi = mockk<AgentsApi>()
            val responseAgents = listOf(Agent(id = "a1"), Agent(id = "a2"))
            coEvery { agentsApi.getAgents(null) } coAnswers {
                delay(50)
                AgentListResponse(data = responseAgents)
            }

            val repository = AgentRepositoryImpl(
                agentsApi = agentsApi,
                activeAccountProvider = InMemoryActiveAccountProvider(),
            )

            val first = async { repository.getAgents(category = null) }
            val second = async { repository.getAgents(category = null) }
            advanceUntilIdle()

            val firstResult = first.await()
            val secondResult = second.await()

            assertThat(firstResult).isInstanceOf(Result.Success::class.java)
            assertThat(secondResult).isInstanceOf(Result.Success::class.java)
            assertThat((firstResult as Result.Success).data).isEqualTo(responseAgents)
            assertThat((secondResult as Result.Success).data).isEqualTo(responseAgents)
            coVerify(exactly = 1) { agentsApi.getAgents(null) }
        }

    @Test
    fun `AgentRepositoryImpl - second call after cache hydrated does not re-fetch`() = runTest {
        val agentsApi = mockk<AgentsApi>()
        val responseAgents = listOf(Agent(id = "a1"))
        coEvery { agentsApi.getAgents(null) } returns AgentListResponse(data = responseAgents)

        val repository = AgentRepositoryImpl(
                agentsApi = agentsApi,
                activeAccountProvider = InMemoryActiveAccountProvider(),
            )
        repository.getAgents(category = null)
        repository.getAgents(category = null)
        repository.getAgents(category = null)

        coVerify(exactly = 1) { agentsApi.getAgents(null) }
    }

    @Test
    fun `AgentRepositoryImpl - categorised getAgents bypasses cache and does not populate it`() =
        runTest {
            val agentsApi = mockk<AgentsApi>()
            val filteredAgents = listOf(Agent(id = "cat1"))
            val allAgents = listOf(Agent(id = "a1"), Agent(id = "a2"))
            coEvery { agentsApi.getAgents("writing") } returns AgentListResponse(data = filteredAgents)
            coEvery { agentsApi.getAgents(null) } returns AgentListResponse(data = allAgents)

            val repository = AgentRepositoryImpl(
                agentsApi = agentsApi,
                activeAccountProvider = InMemoryActiveAccountProvider(),
            )
            repository.getAgents(category = "writing")
            // Cache must still be cold — next call with category=null must hit the API.
            repository.getAgents(category = null)
            repository.getAgents(category = null)

            coVerify(exactly = 1) { agentsApi.getAgents("writing") }
            coVerify(exactly = 1) { agentsApi.getAgents(null) }
        }

    @Test
    fun `AgentRepositoryImpl - identity change makes next getAgents re-fetch (no stale cross-account serve)`() =
        runTest {
            val agentsApi = mockk<AgentsApi>()
            coEvery { agentsApi.getAgents(null) } returns AgentListResponse(data = listOf(Agent(id = "a1")))
            val provider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:userA")))

            val repository = AgentRepositoryImpl(
                agentsApi = agentsApi,
                activeAccountProvider = provider,
            )

            repository.getAgents(category = null) // populates cache under account A
            provider.set(AccountId("srv:userB")) // now active account is B
            // Read-time owner keying: the cache belongs to A, so B's read must NOT be served it; it
            // re-fetches synchronously (no async clear to wait on).
            repository.getAgents(category = null)

            coVerify(exactly = 2) { agentsApi.getAgents(null) }
        }

    @Test
    fun `PresetRepositoryImpl - two concurrent getAll on cold cache hit the API exactly once`() =
        runTest {
            val presetsApi = mockk<PresetsApi>()
            val responsePresets = listOf(
                Preset(presetId = "p1", title = "One"),
                Preset(presetId = "p2", title = "Two"),
            )
            coEvery { presetsApi.getPresets() } coAnswers {
                delay(50)
                responsePresets
            }

            val repository = PresetRepositoryImpl(
            presetsApi = presetsApi,
            activeAccountProvider = InMemoryActiveAccountProvider(),
        )

            val first = async { repository.getAll() }
            val second = async { repository.getAll() }
            advanceUntilIdle()

            val firstResult = first.await()
            val secondResult = second.await()

            assertThat(firstResult).isInstanceOf(Result.Success::class.java)
            assertThat(secondResult).isInstanceOf(Result.Success::class.java)
            assertThat((firstResult as Result.Success).data).isEqualTo(responsePresets)
            assertThat((secondResult as Result.Success).data).isEqualTo(responsePresets)
            coVerify(exactly = 1) { presetsApi.getPresets() }
        }

    @Test
    fun `PresetRepositoryImpl - mutation invalidates cache so next getAll re-fetches`() = runTest {
        val presetsApi = mockk<PresetsApi>()
        val first = listOf(Preset(presetId = "p1", title = "One"))
        val second = listOf(
            Preset(presetId = "p1", title = "One"),
            Preset(presetId = "p2", title = "Two"),
        )
        coEvery { presetsApi.getPresets() } returnsMany listOf(first, second)
        coEvery { presetsApi.createPreset(any()) } returns Preset(presetId = "p2", title = "Two")

        val repository = PresetRepositoryImpl(
            presetsApi = presetsApi,
            activeAccountProvider = InMemoryActiveAccountProvider(),
        )
        repository.getAll()
        repository.create(Preset(presetId = "p2", title = "Two"))
        val result = repository.getAll()

        assertThat((result as Result.Success).data).isEqualTo(second)
        coVerify(exactly = 2) { presetsApi.getPresets() }
    }
}
