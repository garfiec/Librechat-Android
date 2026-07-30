package com.garfiec.librechat.feature.agents.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.response.SkillListResponse
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorUiState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The agent editor's skill catalog must be walked to the end, not sampled. The list it produces
 * also resolves chip names, so a saved skill sitting past page one would render as a raw id on an
 * agent the user already configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCapabilitiesSkillPagingTest {

    private val skillsRepository = mockk<SkillsRepository>()

    private fun page(ids: List<String>, after: String?, hasMore: Boolean) = Result.Success(
        SkillListResponse(
            skills = ids.map { SkillSummary(id = it, name = "skill-$it") },
            after = after,
            hasMore = hasMore,
        ),
    )

    /** Serves each cursor its page; an unexpected cursor fails loudly rather than as an empty list. */
    private fun serving(pages: Map<String?, Result<SkillListResponse>>) {
        coEvery { skillsRepository.listSkills(any(), any(), any(), any()) } answers {
            val cursor = arg<String?>(3)
            pages[cursor] ?: error("unexpected cursor: $cursor")
        }
    }

    /**
     * Drives the delegate through its public entry point with the skills gate open.
     *
     * The availability observers collect StateFlows that never complete, so the delegate gets a
     * scope of its own: on the test's own scope `runTest` fails the test over the still-live
     * collectors, and `TestScope.backgroundScope` is not drained by `advanceUntilIdle` here.
     * Sharing the scheduler is what matters; the scope is cancelled before returning.
     */
    private fun loadedSkillIds(scope: TestScope): List<String> {
        val flow = MutableStateFlow(AgentEditorUiState())
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        every { configRepository.endpointConfigs } returns
            MutableStateFlow(mapOf("agents" to EndpointConfig(capabilities = listOf("skills"))))
        every { configRepository.detectedBackendVersion } returns MutableStateFlow(null)
        val roleRepository = mockk<RoleRepository>(relaxed = true)
        every { roleRepository.userPermissions } returns MutableStateFlow<UserRolePermissions?>(null)

        val delegateScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
        AgentCapabilitiesDelegate(
            stateHandle = AgentEditorStateHandle(flow, delegateScope),
            configRepository = configRepository,
            roleRepository = roleRepository,
            skillsRepository = skillsRepository,
        ).observeAvailability()
        scope.advanceUntilIdle()
        delegateScope.cancel()
        return flow.value.availableSkills.map { it.id }
    }

    @Test
    fun `walks the cursor to the end of the catalog`() = runTest(StandardTestDispatcher()) {
        serving(
            mapOf(
                null to page(listOf("a", "b"), after = "cur-1", hasMore = true),
                "cur-1" to page(listOf("c", "d"), after = "cur-2", hasMore = true),
                "cur-2" to page(listOf("e"), after = null, hasMore = false),
            ),
        )

        assertThat(loadedSkillIds(this)).containsExactly("a", "b", "c", "d", "e").inOrder()
    }

    @Test
    fun `collapses ids repeated across overlapping pages`() = runTest(StandardTestDispatcher()) {
        serving(
            mapOf(
                null to page(listOf("a", "b"), after = "cur-1", hasMore = true),
                "cur-1" to page(listOf("b", "c"), after = null, hasMore = false),
            ),
        )

        assertThat(loadedSkillIds(this)).containsExactly("a", "b", "c").inOrder()
    }

    /**
     * A server that reports `has_more` while handing back the cursor it was given would loop
     * forever without the guard; the walk must stop and keep what it has.
     */
    @Test
    fun `stops on a cursor that does not advance`() = runTest(StandardTestDispatcher()) {
        serving(mapOf(null to page(listOf("a"), after = null, hasMore = true)))

        assertThat(loadedSkillIds(this)).containsExactly("a")
    }

    /**
     * A page that fails mid-walk keeps the earlier pages — a partial catalog resolves more chip
     * names than none.
     */
    @Test
    fun `keeps the pages that landed when a later page fails`() = runTest(StandardTestDispatcher()) {
        serving(
            mapOf(
                null to page(listOf("a", "b"), after = "cur-1", hasMore = true),
                "cur-1" to Result.Error(RuntimeException("boom"), "boom"),
            ),
        )

        assertThat(loadedSkillIds(this)).containsExactly("a", "b").inOrder()
    }
}
