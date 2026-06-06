package com.garfiec.librechat.feature.skills.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.response.SkillListResponse
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val skillsRepository = mockk<SkillsRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    private fun vm() = SkillsListViewModel(skillsRepository, roleRepository)

    private fun summary(id: String) = SkillSummary(id = id, name = "n-$id")

    @Test
    fun `loadFirstPage populates skills and paging state on success`() = runTest(testDispatcher) {
        coEvery { skillsRepository.listSkills(search = null, cursor = null) } returns
            Result.Success(SkillListResponse(skills = listOf(summary("a")), hasMore = true, after = "cur1"))
        val viewModel = vm()
        viewModel.loadFirstPage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.skills.map { it.id }).containsExactly("a")
        assertThat(state.hasMore).isTrue()
        assertThat(state.cursor).isEqualTo("cur1")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `loadFirstPage surfaces error on failure`() = runTest(testDispatcher) {
        coEvery { skillsRepository.listSkills(search = null, cursor = null) } returns
            Result.Error(message = "boom")
        val viewModel = vm()
        viewModel.loadFirstPage()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("boom")
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `loadMore appends the next page using the cursor`() = runTest(testDispatcher) {
        coEvery { skillsRepository.listSkills(search = null, cursor = null) } returns
            Result.Success(SkillListResponse(skills = listOf(summary("a")), hasMore = true, after = "cur1"))
        coEvery { skillsRepository.listSkills(search = null, cursor = "cur1") } returns
            Result.Success(SkillListResponse(skills = listOf(summary("b")), hasMore = false, after = null))
        val viewModel = vm()
        viewModel.loadFirstPage()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.skills.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(viewModel.uiState.value.hasMore).isFalse()
    }

    @Test
    fun `refresh replaces the list`() = runTest(testDispatcher) {
        coEvery { skillsRepository.listSkills(search = null, cursor = null) } returns
            Result.Success(SkillListResponse(skills = listOf(summary("a"))))
        val viewModel = vm()
        viewModel.loadFirstPage()
        advanceUntilIdle()

        coEvery { skillsRepository.listSkills(search = null, cursor = null) } returns
            Result.Success(SkillListResponse(skills = listOf(summary("z"))))
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.skills.map { it.id }).containsExactly("z")
        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
    }

    @Test
    fun `canCreate is false for null role and true only when SKILLS CREATE granted`() =
        runTest(testDispatcher) {
            val denied = vm()
            advanceUntilIdle()
            assertThat(denied.uiState.value.canCreate).isFalse()

            every { roleRepository.userPermissions } returns MutableStateFlow(
                UserRolePermissions(name = "USER", permissions = mapOf("SKILLS" to mapOf("CREATE" to true))),
            )
            val granted = vm()
            advanceUntilIdle()
            assertThat(granted.uiState.value.canCreate).isTrue()
        }

    @Test
    fun `refreshOnReturn does the initial load when list empty`() = runTest(testDispatcher) {
        coEvery { skillsRepository.listSkills(search = null, cursor = null) } returns
            Result.Success(SkillListResponse(skills = listOf(summary("a"))))
        val viewModel = vm()
        viewModel.refreshOnReturn()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.skills.map { it.id }).containsExactly("a")
    }
}
