package com.garfiec.librechat.feature.skills.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.Skill
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
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
class SkillDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repo = mockk<SkillsRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)

    private val skill = Skill(id = "sk-1", name = "n", description = "d", version = 1)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
        coEvery { repo.getSkill("sk-1") } returns Result.Success(skill)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun vm() = SkillDetailViewModel(repo, roleRepository, "sk-1")

    @Test
    fun `toggleActive does not persist when states never loaded (clobber guard)`() = runTest(testDispatcher) {
        // States fetch FAILS → snapshot stays null, Switch must not act.
        coEvery { repo.getSkillStates() } returns Result.Error(message = "boom")
        val viewModel = vm()
        viewModel.load()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activeStateLoaded).isFalse()

        viewModel.toggleActive()
        advanceUntilIdle()

        // The full-replace POST must NOT fire on a null snapshot.
        coVerify(exactly = 0) { repo.updateSkillStates(any()) }
    }

    @Test
    fun `toggleActive merges into loaded snapshot without clobbering other overrides`() = runTest(testDispatcher) {
        val others = mapOf("other-skill" to false, "sk-1" to true)
        coEvery { repo.getSkillStates() } returns Result.Success(others)
        coEvery { repo.updateSkillStates(any()) } answers {
            Result.Success(firstArg())
        }
        val viewModel = vm()
        viewModel.load()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.activeStateLoaded).isTrue()
        assertThat(viewModel.uiState.value.isActive).isTrue()

        viewModel.toggleActive()
        advanceUntilIdle()

        // Persisted map keeps the other skill's override and flips only sk-1.
        coVerify {
            repo.updateSkillStates(mapOf("other-skill" to false, "sk-1" to false))
        }
        assertThat(viewModel.uiState.value.isActive).isFalse()
    }
}
