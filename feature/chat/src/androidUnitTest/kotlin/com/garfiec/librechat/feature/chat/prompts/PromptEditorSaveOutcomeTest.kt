package com.garfiec.librechat.feature.chat.prompts

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A save is reported as successful by `saved`, which `PromptEditorScreen` pops the editor on — so
 * every call the save depends on has to be read before it flips.
 *
 * A prompt's body and its `/` command each ride on a *separate* follow-up request, and every
 * repository method is `safeApiCall`-wrapped: failure arrives as a returned `Result.Error`, never
 * as a throw. Discarding those results loses the user's edit behind a success animation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptEditorSaveOutcomeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val promptRepository = mockk<PromptRepository>(relaxed = true)

    private val createdGroup = PromptGroup(id = "g-1", name = "Summarize", author = "a", authorName = "A")
    private val existingPrompt =
        Prompt(id = "p-1", groupId = "g-1", author = "a", prompt = "old body", type = "text")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { promptRepository.revision } returns MutableStateFlow(0L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newEditor(groupId: String? = null) = PromptEditorViewModel(promptRepository, groupId)

    private fun loadedEditor(): PromptEditorViewModel {
        coEvery { promptRepository.getGroup("g-1") } returns Result.Success(
            createdGroup.copy(productionId = "p-1", prompts = listOf(existingPrompt)),
        )
        coEvery { promptRepository.getPromptsByGroupId("g-1") } returns Result.Success(listOf(existingPrompt))
        return newEditor("g-1")
    }

    @Test
    fun aFailedCommandUpdateDoesNotReportTheCreateAsSaved() = runTest(testDispatcher) {
        coEvery { promptRepository.create(any()) } returns Result.Success(createdGroup)
        coEvery { promptRepository.update(any(), any()) } returns Result.Error(message = "offline")

        val editor = newEditor()
        editor.updateName("Summarize")
        editor.updatePromptText("Summarize this: {{text}}")
        editor.updateCommand("summarize")
        editor.save()
        advanceUntilIdle()

        // The create route takes neither the oneliner nor the command, so a prompt saved without
        // this follow-up is not findable by the `/summarize` the user typed.
        val state = editor.uiState.value
        assertFalse(state.saved)
        assertNotNull(state.error)
        assertFalse(state.isSaving)
        // The group exists now: keeping its id is what makes a retry an update rather than a
        // second copy of the same prompt.
        assertEquals("g-1", state.groupId)
    }

    @Test
    fun aCreateWithNoMetadataStillSaves() = runTest(testDispatcher) {
        coEvery { promptRepository.create(any()) } returns Result.Success(createdGroup)

        val editor = newEditor()
        editor.updateName("Summarize")
        editor.updatePromptText("Summarize this")
        editor.save()
        advanceUntilIdle()

        // No oneliner and no command means no follow-up call to fail; the create alone is the save.
        assertTrue(editor.uiState.value.saved)
        assertNull(editor.uiState.value.error)
        coVerify(exactly = 0) { promptRepository.update(any(), any()) }
    }

    @Test
    fun aFailedBodyUpdateDoesNotReportTheEditAsSaved() = runTest(testDispatcher) {
        val editor = loadedEditor()
        advanceUntilIdle()
        coEvery { promptRepository.update(any(), any()) } returns Result.Success(createdGroup)
        coEvery { promptRepository.addPromptToGroup(any(), any()) } returns Result.Error(message = "offline")

        editor.updatePromptText("new body")
        editor.save()
        advanceUntilIdle()

        // addPromptToGroup is the call that carries the body — the metadata update above does not.
        val state = editor.uiState.value
        assertFalse(state.saved)
        assertNotNull(state.error)
        assertFalse(state.isSaving)
        // Nothing was written, so there is no version to promote.
        coVerify(exactly = 0) { promptRepository.updatePromptProductionTag(any()) }
    }

    @Test
    fun anAcceptedBodyUpdatePromotesTheNewVersionAndReportsSaved() = runTest(testDispatcher) {
        val editor = loadedEditor()
        advanceUntilIdle()
        coEvery { promptRepository.update(any(), any()) } returns Result.Success(createdGroup)
        coEvery { promptRepository.addPromptToGroup(any(), any()) } returns
            Result.Success(existingPrompt.copy(id = "p-2", prompt = "new body"))
        coEvery { promptRepository.updatePromptProductionTag("p-2") } returns Result.Success(Unit)

        editor.updatePromptText("new body")
        editor.save()
        advanceUntilIdle()

        // Adding a version does not move the group's productionId. Without the promotion the edit
        // is stored where nothing reads it, so the user's change looks discarded.
        coVerify(exactly = 1) { promptRepository.updatePromptProductionTag("p-2") }
        assertTrue(editor.uiState.value.saved)
        assertNull(editor.uiState.value.error)
    }

    @Test
    fun aFailedPromotionDoesNotReportTheEditAsSaved() = runTest(testDispatcher) {
        val editor = loadedEditor()
        advanceUntilIdle()
        coEvery { promptRepository.update(any(), any()) } returns Result.Success(createdGroup)
        coEvery { promptRepository.addPromptToGroup(any(), any()) } returns
            Result.Success(existingPrompt.copy(id = "p-2", prompt = "new body"))
        coEvery { promptRepository.updatePromptProductionTag("p-2") } returns Result.Error(message = "offline")

        editor.updatePromptText("new body")
        editor.save()
        advanceUntilIdle()

        // The version exists but is not live, so the edit is not in effect anywhere the user can
        // see. Popping the editor here would animate success over a prompt that still answers with
        // the old body.
        val state = editor.uiState.value
        assertFalse(state.saved)
        assertNotNull(state.error)
        assertFalse(state.isSaving)
    }

    @Test
    fun aNewVersionWithNoIdIsNotReportedAsSaved() = runTest(testDispatcher) {
        val editor = loadedEditor()
        advanceUntilIdle()
        coEvery { promptRepository.update(any(), any()) } returns Result.Success(createdGroup)
        coEvery { promptRepository.addPromptToGroup(any(), any()) } returns
            Result.Success(existingPrompt.copy(id = null, prompt = "new body"))

        editor.updatePromptText("new body")
        editor.save()
        advanceUntilIdle()

        // The response is the only place the new version's id appears, so no id means no promotion
        // — and an unpromoted version is an edit nothing will read.
        coVerify(exactly = 0) { promptRepository.updatePromptProductionTag(any()) }
        assertFalse(editor.uiState.value.saved)
        assertNotNull(editor.uiState.value.error)
    }

    @Test
    fun anUnchangedBodyWritesNoVersionAndSaves() = runTest(testDispatcher) {
        val editor = loadedEditor()
        advanceUntilIdle()
        coEvery { promptRepository.update(any(), any()) } returns Result.Success(createdGroup)

        editor.updateCommand("summarize")
        editor.save()
        advanceUntilIdle()

        // Renaming or re-commanding a prompt must not mint a version identical to the live one —
        // the version history is the user's, and every save would otherwise add a row to it.
        coVerify(exactly = 0) { promptRepository.addPromptToGroup(any(), any()) }
        coVerify(exactly = 0) { promptRepository.updatePromptProductionTag(any()) }
        assertTrue(editor.uiState.value.saved)
    }
}
