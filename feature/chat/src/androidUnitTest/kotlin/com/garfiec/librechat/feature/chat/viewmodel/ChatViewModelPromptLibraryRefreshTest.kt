package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A prompt authored while this chat is on the back stack must reach the composer's `/` picker (#317).
 *
 * These drive the real ViewModel rather than the delegate, so the permission gate and the wiring
 * are covered too. `refreshPromptsIfStale()` stands in for the chat screen's composition, which
 * drives it keyed on `promptLibraryRevision` (`ChatRoot`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPromptLibraryRefreshTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fixture = ChatViewModelTestFixture()
    private val agentRepository get() = fixture.agentRepository
    private val messageRepository get() = fixture.messageRepository
    private val configRepository get() = fixture.configRepository
    private val conversationRepository get() = fixture.conversationRepository
    private val favoritesRepository get() = fixture.favoritesRepository
    private val keyRepository get() = fixture.keyRepository
    private val promptRepository get() = fixture.promptRepository
    private val roleRepository get() = fixture.roleRepository
    private val permissionGate get() = fixture.permissionGate
    private val serverDataStore get() = fixture.serverDataStore
    private val settingsDataStore get() = fixture.settingsDataStore
    private val platformDelegateFactory get() = fixture.platformDelegateFactory
    private val serverFileSelectionHandoff get() = fixture.serverFileSelectionHandoff

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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fixture.stubDefaults()

        // A relaxed mock hands back a chained mock whose `hasAccess` is false, which would gate the
        // prompt load off for the wrong reason. Stub the ordinary permissive role instead.
        coEvery { permissionGate.awaitRole() } returns UserRolePermissions(name = "USER")

        every { messageRepository.observeMessages(any()) } returns flowOf(emptyList())
        coEvery { messageRepository.getMessages(any()) } returns Result.Success(emptyList())
        coEvery { conversationRepository.getConversation(any(), any()) } returns Result.Error(message = "test")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aPromptSavedWhileTheChatIsRetainedReachesThePicker() = runTest(testDispatcher) {
        coEvery { promptRepository.getAllGroups() } returns Result.Success(listOf(group("g-1", "First")))

        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(listOf("First"), viewModel.uiState.value.availablePrompts.map { it.name })

        // The editor's save, from a nav entry pushed on top of this one.
        coEvery { promptRepository.getAllGroups() } returns
            Result.Success(listOf(group("g-1", "First"), group("g-2", "Just authored")))
        fixture.promptRevision.value += 1
        advanceUntilIdle()

        // Nothing yet: the chat is off screen, and its picker cannot be read from there.
        coVerify(exactly = 1) { promptRepository.getAllGroups() }

        viewModel.refreshPromptsIfStale()
        advanceUntilIdle()

        assertEquals(
            listOf("First", "Just authored"),
            viewModel.uiState.value.availablePrompts.map { it.name },
        )
    }

    @Test
    fun returningWithNothingChangedFetchesNothing() = runTest(testDispatcher) {
        coEvery { promptRepository.getAllGroups() } returns Result.Success(listOf(group("g-1", "First")))

        val viewModel = newViewModel()
        advanceUntilIdle()

        // Every re-entry drives this; without the revision check the picker would re-download every
        // prompt group each time the user backed out of the library.
        viewModel.refreshPromptsIfStale()
        viewModel.refreshPromptsIfStale()
        advanceUntilIdle()

        coVerify(exactly = 1) { promptRepository.getAllGroups() }
    }

    @Test
    fun severalSavesWhileAwayCostOneReload() = runTest(testDispatcher) {
        coEvery { promptRepository.getAllGroups() } returns Result.Success(listOf(group("g-1", "First")))

        val viewModel = newViewModel()
        advanceUntilIdle()

        // Flipping through versions in the editor bumps the revision per tap.
        repeat(4) { fixture.promptRevision.value += 1 }
        advanceUntilIdle()
        viewModel.refreshPromptsIfStale()
        advanceUntilIdle()

        coVerify(exactly = 2) { promptRepository.getAllGroups() }
    }

    @Test
    fun aFirstCompositionDoesNotDuplicateTheInitialLoad() = runTest(testDispatcher) {
        coEvery { promptRepository.getAllGroups() } returns Result.Success(listOf(group("g-1", "First")))

        val viewModel = newViewModel()
        // Before `advanceUntilIdle`, so the initial load is still in flight — which is exactly when
        // the screen's first composition fires this.
        viewModel.refreshPromptsIfStale()
        advanceUntilIdle()

        coVerify(exactly = 1) { promptRepository.getAllGroups() }
    }

    @Test
    fun aUserWithoutPromptAccessNeverFetches() = runTest(testDispatcher) {
        coEvery { permissionGate.awaitRole() } returns UserRolePermissions(
            name = "USER",
            permissions = mapOf(
                PermissionType.PROMPTS.serverKey to mapOf(Permission.USE.serverKey to false),
            ),
        )
        coEvery { promptRepository.getAllGroups() } returns Result.Success(listOf(group("g-1", "First")))

        val viewModel = newViewModel()
        advanceUntilIdle()
        fixture.promptRevision.value += 1
        viewModel.refreshPromptsIfStale()
        advanceUntilIdle()

        // The refresh sits behind the permission gate, so a denied user's picker stays silent
        // rather than issuing a request the server would 403.
        coVerify(exactly = 0) { promptRepository.getAllGroups() }
    }

    private fun newViewModel(): ChatViewModel =
        fixture.build(
            defaultDispatcher = testDispatcher,
            initialConversationId = "conv-1",
        )
}
