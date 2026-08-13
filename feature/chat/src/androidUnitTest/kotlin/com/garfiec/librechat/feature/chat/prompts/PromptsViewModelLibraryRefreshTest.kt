package com.garfiec.librechat.feature.chat.prompts

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.response.PromptGroupListResponse
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The library list as the second consumer of `PromptRepository.revision` (#315).
 *
 * The revision is driven by hand here; that the repository bumps it on exactly the right calls is
 * `PromptRepositoryRevisionTest`'s job. `refreshIfStale()` stands in for the screen's composition,
 * which drives it keyed on `promptLibraryRevision`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptsViewModelLibraryRefreshTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val promptRepository = mockk<PromptRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val permissionGate = mockk<PermissionGate>(relaxed = true)
    private val revision = MutableStateFlow(0L)

    private fun group(id: String, name: String) = PromptGroup(
        id = id,
        name = name,
        author = "author-1",
        authorName = "Author",
        productionId = "$id-p",
        prompts = listOf(
            Prompt(id = "$id-p", groupId = id, author = "author-1", prompt = "body of $name", type = "text"),
        ),
    )

    private fun response(vararg groups: PromptGroup) =
        Result.Success(PromptGroupListResponse(promptGroups = groups.toList()))

    private fun newViewModel() = PromptsViewModel(promptRepository, roleRepository, permissionGate)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
        every { promptRepository.revision } returns revision
        // A relaxed mock returns a chained mock whose `hasAccess` is false, which would gate the
        // initial load off for the wrong reason. Stub the ordinary permissive role instead.
        coEvery { permissionGate.awaitRole() } returns UserRolePermissions(name = "USER")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSaveInTheEditorRefreshesTheRetainedList() = runTest(testDispatcher) {
        coEvery { promptRepository.getGroups(any(), any()) } returns response(group("g-1", "First"))

        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(listOf("First"), viewModel.uiState.value.groups.map { it.name })

        coEvery { promptRepository.getGroups(any(), any()) } returns
            response(group("g-1", "First"), group("g-2", "Just authored"))
        revision.value += 1
        advanceUntilIdle()

        // Still in the editor: nothing fetched yet.
        coVerify(exactly = 1) { promptRepository.getGroups(any(), any()) }

        viewModel.refreshIfStale()
        advanceUntilIdle()

        assertEquals(
            listOf("First", "Just authored"),
            viewModel.uiState.value.groups.map { it.name },
        )
    }

    @Test
    fun returningWithNothingChangedFetchesNothing() = runTest(testDispatcher) {
        coEvery { promptRepository.getGroups(any(), any()) } returns response(group("g-1", "First"))

        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.refreshIfStale()
        viewModel.refreshIfStale()
        advanceUntilIdle()

        // Every re-entry drives this; without the revision check, backing out of the editor would
        // re-fetch the list each time even when nothing changed.
        coVerify(exactly = 1) { promptRepository.getGroups(any(), any()) }
    }

    @Test
    fun aBackgroundReloadFailureIsNotShownToTheUser() = runTest(testDispatcher) {
        coEvery { promptRepository.getGroups(any(), any()) } returns response(group("g-1", "First"))

        val viewModel = newViewModel()
        advanceUntilIdle()

        coEvery { promptRepository.getGroups(any(), any()) } returns Result.Error(message = "offline")
        revision.value += 1
        viewModel.refreshIfStale()
        advanceUntilIdle()

        // The screen pops a snackbar off `error`. Nobody asked for this fetch, so a failure here
        // would greet the user with "Failed to refresh prompts" after a save that worked.
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("First"), viewModel.uiState.value.groups.map { it.name })
    }

    @Test
    fun aPullToRefreshFailureIsShownToTheUser() = runTest(testDispatcher) {
        coEvery { promptRepository.getGroups(any(), any()) } returns response(group("g-1", "First"))

        val viewModel = newViewModel()
        advanceUntilIdle()

        coEvery { promptRepository.getGroups(any(), any()) } returns Result.Error(message = "offline")
        viewModel.refresh()
        advanceUntilIdle()

        // The counterpart to the test above: silencing the gesture the user did make would be a
        // pull that spins and reports nothing.
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun aFailedDeleteReportsTheFailureAndKeepsTheDetailOpen() = runTest(testDispatcher) {
        coEvery { promptRepository.getGroups(any(), any()) } returns response(group("g-1", "First"))
        coEvery { promptRepository.getGroup(any()) } returns Result.Success(group("g-1", "First"))
        coEvery { promptRepository.getPromptsByGroupId(any()) } returns Result.Success(emptyList())

        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectGroup("g-1")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.selectedGroup)

        // `delete` REPORTS failure by returning Result.Error and never throws, so a try/catch
        // around it sees nothing: closing the detail view here tells the user a prompt is gone
        // while it is still on the server.
        coEvery { promptRepository.delete(any()) } returns Result.Error(message = "forbidden")
        viewModel.deleteGroup("g-1")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.selectedGroup)
    }

    @Test
    fun anAcceptedDeleteClosesTheDetailView() = runTest(testDispatcher) {
        coEvery { promptRepository.getGroups(any(), any()) } returns response(group("g-1", "First"))
        coEvery { promptRepository.getGroup(any()) } returns Result.Success(group("g-1", "First"))
        coEvery { promptRepository.getPromptsByGroupId(any()) } returns Result.Success(emptyList())

        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectGroup("g-1")
        advanceUntilIdle()

        coEvery { promptRepository.delete(any()) } returns Result.Success(Unit)
        viewModel.deleteGroup("g-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedGroup)
        assertNull(viewModel.uiState.value.error)
    }
}
