package com.garfiec.librechat.feature.skills.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.SkillUpdateResult
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.request.CreateSkillRequest
import com.garfiec.librechat.core.model.response.Category
import com.google.common.truth.Truth.assertThat
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
class SkillEditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val skillsRepository = mockk<SkillsRepository>(relaxed = true)
    private val configRepository = mockk<ConfigRepository>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads server categories into ui state on init (create mode)`() = runTest(testDispatcher) {
        coEvery { configRepository.getCategories() } returns Result.Success(
            listOf(Category(label = "com_ui_idea", value = "idea"), Category(label = "com_ui_code", value = "code")),
        )
        val vm = SkillEditorViewModel(skillsRepository, configRepository, initialSkillId = null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.availableCategories.mapNotNull { it.value })
            .containsExactly("idea", "code").inOrder()
    }

    @Test
    fun `category fetch failure is non-fatal — empty presets, no error`() = runTest(testDispatcher) {
        coEvery { configRepository.getCategories() } returns Result.Error(message = "offline")
        val vm = SkillEditorViewModel(skillsRepository, configRepository, initialSkillId = null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.availableCategories).isEmpty()
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `onCategoryChanged sets the persisted category value`() = runTest(testDispatcher) {
        coEvery { configRepository.getCategories() } returns Result.Success(emptyList())
        val vm = SkillEditorViewModel(skillsRepository, configRepository, initialSkillId = null)
        advanceUntilIdle()

        vm.onCategoryChanged("write")
        assertThat(vm.uiState.value.category).isEqualTo("write")

        // Selecting "None" clears it (sent as null on save via ifBlank).
        vm.onCategoryChanged("")
        assertThat(vm.uiState.value.category).isEmpty()
    }

    // --- alwaysApply toggle (v0.8.6) ---

    @Test
    fun `onAlwaysApplyChanged sets and clears the flag`() = runTest(testDispatcher) {
        coEvery { configRepository.getCategories() } returns Result.Success(emptyList())
        val vm = SkillEditorViewModel(skillsRepository, configRepository, initialSkillId = null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.alwaysApply).isFalse()
        vm.onAlwaysApplyChanged(true)
        assertThat(vm.uiState.value.alwaysApply).isTrue()
        vm.onAlwaysApplyChanged(false)
        assertThat(vm.uiState.value.alwaysApply).isFalse()
    }

    @Test
    fun `create sends the explicit alwaysApply boolean`() = runTest(testDispatcher) {
        coEvery { configRepository.getCategories() } returns Result.Success(emptyList())
        val reqSlot: CapturingSlot<CreateSkillRequest> = slot()
        coEvery { skillsRepository.createSkill(capture(reqSlot)) } returns
            Result.Success(Skill(id = "sk-1", name = "n", version = 1))

        val vm = SkillEditorViewModel(skillsRepository, configRepository, initialSkillId = null)
        advanceUntilIdle()
        vm.onNameChanged("my-skill")
        vm.onDescriptionChanged("d")
        vm.onBodyChanged("b")
        vm.onAlwaysApplyChanged(true)
        vm.save()
        advanceUntilIdle()

        coVerify { skillsRepository.createSkill(any()) }
        assertThat(reqSlot.captured.alwaysApply).isTrue()
    }

    @Test
    fun `edit hydrates alwaysApply from the loaded skill and re-sends it`() = runTest(testDispatcher) {
        coEvery { configRepository.getCategories() } returns Result.Success(emptyList())
        coEvery { skillsRepository.getSkill("sk-1") } returns
            Result.Success(Skill(id = "sk-1", name = "n", description = "d", body = "b", version = 3, alwaysApply = true))
        coEvery { skillsRepository.updateSkill(eq("sk-1"), any()) } returns
            SkillUpdateResult.Success(Skill(id = "sk-1", name = "n", version = 4))

        val vm = SkillEditorViewModel(skillsRepository, configRepository, initialSkillId = "sk-1")
        advanceUntilIdle()
        // Hydrated from the fetched skill (not reset to false).
        assertThat(vm.uiState.value.alwaysApply).isTrue()

        // Toggle OFF and save → the explicit false must be sent (so it persists).
        vm.onAlwaysApplyChanged(false)
        vm.save()
        advanceUntilIdle()
        coVerify {
            skillsRepository.updateSkill("sk-1", match { it.alwaysApply == false })
        }
    }
}
