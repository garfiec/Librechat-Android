package com.garfiec.librechat.feature.conversations.viewmodel

import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SearchRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.ConversationTag
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.feature.conversations.export.ConversationExporter
import com.garfiec.librechat.feature.conversations.export.ConversationImporter
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val tagRepository = mockk<TagRepository>(relaxed = true)
    private val searchRepository = mockk<SearchRepository>(relaxed = true)
    private val shareRepository = mockk<ShareRepository>(relaxed = true)
    private val conversationExporter = mockk<ConversationExporter>(relaxed = true)
    private val conversationImporter = mockk<ConversationImporter>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val endpointConfigsFlow = MutableStateFlow<Map<String, EndpointConfig>>(emptyMap())
    private val configRepository = mockk<ConfigRepository>(relaxed = true) {
        every { endpointConfigs } returns endpointConfigsFlow
    }

    private lateinit var viewModel: ConversationListViewModel

    private val testConversations = listOf(
        Conversation(
            conversationId = "convo-1",
            title = "First Conversation",
            updatedAt = Instant.parse("2026-02-19T10:00:00.000Z"),
        ),
        Conversation(
            conversationId = "convo-2",
            title = "Second Conversation",
            updatedAt = Instant.parse("2026-02-19T09:00:00.000Z"),
        ),
    )

    private val testTags = listOf(
        ConversationTag(tag = "work", count = 3),
        ConversationTag(tag = "personal", count = 2),
        ConversationTag(tag = "empty", count = 0),
        ConversationTag(tag = SAVED_TAG, count = 5),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        every { conversationRepository.observeConversations(any()) } returns flowOf(
            Result.Success(testConversations),
        )
        coEvery { conversationRepository.loadNextPage(any(), tags = any()) } returns Result.Success(null)
        coEvery { conversationRepository.syncFavoritesFromServer() } returns Result.Success(Unit)
        every { tagRepository.observeTags() } returns flowOf(testTags)
        coEvery { tagRepository.refreshTags() } returns Result.Success(Unit)
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ConversationListViewModel(
        conversationRepository = conversationRepository,
        tagRepository = tagRepository,
        searchRepository = searchRepository,
        shareRepository = shareRepository,
        conversationExporter = conversationExporter,
        conversationImporter = conversationImporter,
        roleRepository = roleRepository,
        configRepository = configRepository,
        // Single emission. The production flow re-arms at each midnight and never completes, which
        // would make advanceUntilIdle() advance virtual time forever. `combine` needs at least one
        // emission from every source, so this can't be emptyFlow().
        dayBoundaries = flowOf(RelativeTimeReference.current()),
    )

    @Test
    fun `initial state loads conversations from Room observation`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.conversationCount).isEqualTo(2)
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `initial state loads tags with non-zero counts and excludes SAVED_TAG`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.tags).hasSize(2)
        assertThat(state.tags.map { it.tag }).containsExactly("work", "personal")
    }

    @Test
    fun `loadConversations sets isLoading then clears on success`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns Result.Success("next-cursor")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.nextCursor).isEqualTo("next-cursor")
        assertThat(state.hasMore).isTrue()
    }

    @Test
    fun `loadConversations with no more pages sets hasMore false`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns Result.Success(null)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasMore).isFalse()
    }

    @Test
    fun `loadConversations error surfaces error message`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns
            Result.Error(message = "Network error")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Network error")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `loadMore fetches next page using cursor`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns Result.Success("cursor-1")
        coEvery { conversationRepository.loadNextPage("cursor-1", tags = any()) } returns Result.Success("cursor-2")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.nextCursor).isEqualTo("cursor-2")
        coVerify { conversationRepository.loadNextPage("cursor-1", tags = any()) }
    }

    @Test
    fun `loadMore is no-op when no cursor`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns Result.Success(null)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepository.loadNextPage(any(), tags = any()) }
    }

    @Test
    fun `toggleTag adds tag to selection and reloads`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTag("work")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedTags).containsExactly("work")
    }

    @Test
    fun `toggleTag removes already selected tag`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTag("work")
        advanceUntilIdle()
        viewModel.toggleTag("work")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedTags).isEmpty()
    }

    @Test
    fun `clearTagFilter clears all selected tags`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTag("work")
        viewModel.toggleTag("personal")
        advanceUntilIdle()

        viewModel.clearTagFilter()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedTags).isEmpty()
    }

    @Test
    fun `onSearchQueryChanged with blank query clears search mode`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isSearching).isFalse()
        assertThat(viewModel.uiState.value.searchQuery).isEmpty()
    }

    @Test
    fun `onSearchQueryChanged with query triggers search after debounce`() = runTest {
        val searchResults = listOf(
            Conversation(conversationId = "search-1", title = "Search Result"),
        )
        coEvery { searchRepository.search("hello") } returns Result.Success(searchResults)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.searchQuery).isEqualTo("hello")
        assertThat(state.isSearching).isFalse()
        assertThat(state.hasMore).isFalse()
        assertThat(state.conversationCount).isEqualTo(1)
    }

    @Test
    fun `search error surfaces error message`() = runTest {
        coEvery { searchRepository.search(any()) } returns Result.Error(message = "Search failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("test")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Search failed")
    }

    @Test
    fun `deleteConversation calls repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteConversation("convo-1")
        advanceUntilIdle()

        coVerify { conversationRepository.delete("convo-1") }
    }

    @Test
    fun `archiveConversation calls repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.archiveConversation("convo-1")
        advanceUntilIdle()

        coVerify { conversationRepository.archive("convo-1", true) }
    }

    @Test
    fun `renameConversation calls repository`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.renameConversation("convo-1", "New Title")
        advanceUntilIdle()

        coVerify { conversationRepository.updateTitle("convo-1", "New Title") }
    }

    @Test
    fun `shareConversation emits ShareLinkCopied event on success`() = runTest {
        coEvery { shareRepository.createShareLink("convo-1") } returns
            Result.Success("https://example.com/share/abc")

        viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<ConversationListEvent>()
        val job = launch {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.shareConversation("convo-1")
        advanceUntilIdle()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(ConversationListEvent.ShareLinkCopied::class.java)
        assertThat((events[0] as ConversationListEvent.ShareLinkCopied).url)
            .isEqualTo("https://example.com/share/abc")

        job.cancel()
    }

    @Test
    fun `shareConversation emits ShowError on failure`() = runTest {
        coEvery { shareRepository.createShareLink("convo-1") } returns
            Result.Error(message = "Share failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<ConversationListEvent>()
        val job = launch {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.shareConversation("convo-1")
        advanceUntilIdle()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(ConversationListEvent.ShowError::class.java)

        job.cancel()
    }

    @Test
    fun `dismissError clears error state`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns
            Result.Error(message = "Error")

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()

        viewModel.dismissError()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `refresh resets and reloads conversations`() = runTest {
        coEvery { conversationRepository.loadNextPage(null, tags = any()) } returns Result.Success(null)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isRefreshing).isFalse()
    }

    @Test
    fun `getConversation returns matching conversation`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = viewModel.getConversation("convo-1")
        assertThat(convo).isNotNull()
        assertThat(convo?.title).isEqualTo("First Conversation")
    }

    @Test
    fun `getConversation returns null for unknown id`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.getConversation("nonexistent")).isNull()
    }

    @Test
    fun `forkConversation emits NavigateToConversation on success`() = runTest {
        val forkedConvo = Conversation(conversationId = "forked-1", title = "Forked")
        coEvery { conversationRepository.forkConversation("convo-1", "msg-1") } returns
            Result.Success(forkedConvo)

        viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<ConversationListEvent>()
        val job = launch {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.forkConversation("convo-1", "msg-1")
        advanceUntilIdle()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(ConversationListEvent.NavigateToConversation::class.java)
        assertThat((events[0] as ConversationListEvent.NavigateToConversation).conversationId)
            .isEqualTo("forked-1")

        job.cancel()
    }

    @Test
    fun `duplicateConversation emits NavigateToConversation on success`() = runTest {
        val duplicatedConvo = Conversation(conversationId = "dup-1", title = "Copy of First")
        coEvery { conversationRepository.duplicateConversation("convo-1", "Copy") } returns
            Result.Success(duplicatedConvo)

        viewModel = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<ConversationListEvent>()
        val job = launch {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.duplicateConversation("convo-1", "Copy")
        advanceUntilIdle()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(ConversationListEvent.NavigateToConversation::class.java)

        job.cancel()
    }

    @Test
    fun `updateConversationTags persists new tag list when conversation was not favorited`() = runTest {
        coEvery { tagRepository.setConversationTags("convo-1", listOf("work")) } returns
            Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = Conversation(conversationId = "convo-1", tags = listOf("old"))
        viewModel.updateConversationTags(convo, listOf("work"))
        advanceUntilIdle()

        coVerify { tagRepository.setConversationTags("convo-1", listOf("work")) }
    }

    @Test
    fun `updateConversationTags preserves SAVED_TAG when conversation was favorited`() = runTest {
        coEvery { tagRepository.setConversationTags(any(), any()) } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = Conversation(conversationId = "convo-1", tags = listOf("work", SAVED_TAG))
        // Picker submits the user-tag list WITHOUT SAVED_TAG (the screen strips it).
        viewModel.updateConversationTags(convo, listOf("projects"))
        advanceUntilIdle()

        coVerify { tagRepository.setConversationTags("convo-1", listOf("projects", SAVED_TAG)) }
    }

    @Test
    fun `updateConversationTags strips SAVED_TAG from picker submission when not favorited`() = runTest {
        coEvery { tagRepository.setConversationTags(any(), any()) } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = Conversation(conversationId = "convo-1", tags = listOf("work"))
        // Defensive: if the picker ever submits SAVED_TAG on an unfavorited convo, drop it.
        viewModel.updateConversationTags(convo, listOf("projects", SAVED_TAG))
        advanceUntilIdle()

        coVerify { tagRepository.setConversationTags("convo-1", listOf("projects")) }
    }

    @Test
    fun `toggleFavorite on unfavorited convo delegates to repo`() = runTest {
        coEvery {
            tagRepository.toggleFavorite(any(), any())
        } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = Conversation(conversationId = "convo-1", tags = listOf("work"))
        viewModel.toggleFavorite(convo)
        advanceUntilIdle()

        coVerify {
            tagRepository.toggleFavorite(
                conversationId = "convo-1",
                currentTags = listOf("work"),
            )
        }
    }

    @Test
    fun `toggleFavorite on favorited convo delegates to repo`() = runTest {
        coEvery {
            tagRepository.toggleFavorite(any(), any())
        } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = Conversation(
            conversationId = "convo-1",
            tags = listOf("work", SAVED_TAG),
        )
        viewModel.toggleFavorite(convo)
        advanceUntilIdle()

        coVerify {
            tagRepository.toggleFavorite(
                conversationId = "convo-1",
                currentTags = listOf("work", SAVED_TAG),
            )
        }
    }

    @Test
    fun `toggleFavorite with null conversationId is a no-op`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val convo = Conversation(conversationId = null, tags = listOf("work"))
        viewModel.toggleFavorite(convo)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            tagRepository.toggleFavorite(any(), any())
        }
    }

    @Test
    fun `delete from Room during active search removes the row from visible results`() = runTest {
        // Regression (Punted Bug #25): a drawer-side delete used to stay visible in the list
        // screen's active search until search was cleared, because Room emissions were dropped
        // wholesale during search. Now a present -> absent transition subtracts the deleted row.
        val roomFlow = MutableStateFlow<Result<List<Conversation>>>(Result.Success(testConversations))
        every { conversationRepository.observeConversations(any()) } returns roomFlow
        coEvery { searchRepository.search("q") } returns Result.Success(testConversations)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("q")
        advanceUntilIdle()
        assertThat(
            viewModel.uiState.value.groupedConversations.flatMap { it.second }.map { it.conversationId },
        ).containsExactly("convo-1", "convo-2")

        // Room re-emits without convo-1 (deleted from the drawer) while search is still active.
        roomFlow.value = Result.Success(testConversations.filter { it.conversationId == "convo-2" })
        advanceUntilIdle()

        assertThat(
            viewModel.uiState.value.groupedConversations.flatMap { it.second }.map { it.conversationId },
        ).containsExactly("convo-2")
    }

    @Test
    fun `rename in Room during active search refreshes the visible row`() = runTest {
        val roomFlow = MutableStateFlow<Result<List<Conversation>>>(Result.Success(testConversations))
        every { conversationRepository.observeConversations(any()) } returns roomFlow
        coEvery { searchRepository.search("q") } returns Result.Success(testConversations)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("q")
        advanceUntilIdle()

        // Room re-emits convo-1 with a new title (drawer rename); the visible search row updates
        // in place, and rows Room no longer holds are not pulled in.
        val renamed = testConversations.map {
            if (it.conversationId == "convo-1") it.copy(title = "Renamed First") else it
        }
        roomFlow.value = Result.Success(renamed)
        advanceUntilIdle()

        val titles = viewModel.uiState.value.groupedConversations.flatMap { it.second }
            .associate { it.conversationId to it.title }
        assertThat(titles["convo-1"]).isEqualTo("Renamed First")
        assertThat(titles.keys).containsExactly("convo-1", "convo-2")
    }

    @Test
    fun `stale Room row during active search does not clobber a server-fresher search hit`() = runTest {
        // Server search returns a fresher copy (newer updatedAt) than the local Room cache holds —
        // e.g. the title was changed on another device and hasn't synced down yet. A Room re-emission
        // during the search must NOT revert the visible row to the stale cached title.
        val freshHit = Conversation(
            conversationId = "convo-1",
            title = "Fresh Title",
            updatedAt = Instant.parse("2026-02-19T12:00:00.000Z"),
        )
        val staleRoom = Conversation(
            conversationId = "convo-1",
            title = "Stale Title",
            updatedAt = Instant.parse("2026-02-19T10:00:00.000Z"),
        )
        val roomFlow = MutableStateFlow<Result<List<Conversation>>>(Result.Success(listOf(staleRoom)))
        every { conversationRepository.observeConversations(any()) } returns roomFlow
        coEvery { searchRepository.search("q") } returns Result.Success(listOf(freshHit))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("q")
        advanceUntilIdle()

        // Room re-emits the stale copy while search is active.
        roomFlow.value = Result.Success(listOf(staleRoom.copy()))
        advanceUntilIdle()

        val titles = viewModel.uiState.value.groupedConversations.flatMap { it.second }
            .associate { it.conversationId to it.title }
        assertThat(titles["convo-1"]).isEqualTo("Fresh Title")
    }

    @Test
    fun `empty Room emission during active search does not blank the results`() = runTest {
        // An empty emission is the account-scoped gate resetting on switch, not a per-row delete;
        // blanking the active search here would leave a stale query over an empty list.
        val roomFlow = MutableStateFlow<Result<List<Conversation>>>(Result.Success(testConversations))
        every { conversationRepository.observeConversations(any()) } returns roomFlow
        coEvery { searchRepository.search("q") } returns Result.Success(testConversations)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("q")
        advanceUntilIdle()

        roomFlow.value = Result.Success(emptyList())
        advanceUntilIdle()

        assertThat(
            viewModel.uiState.value.groupedConversations.flatMap { it.second }.map { it.conversationId },
        ).containsExactly("convo-1", "convo-2")
    }

    @Test
    fun `endpointConfigs arriving late re-groups rows with iconURL`() = runTest {
        // Conversation has no per-convo iconURL; relies on the endpoint config fallback.
        val convo = Conversation(
            conversationId = "convo-icon-1",
            title = "OpenRouter chat",
            endpoint = "OpenRouter",
            iconURL = null,
            updatedAt = Instant.parse("2026-02-19T10:00:00.000Z"),
        )
        every { conversationRepository.observeConversations(any()) } returns flowOf(
            Result.Success(listOf(convo)),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Initial frame: configs are empty; endpointIconUrl must be null.
        val initialFlat = viewModel.uiState.value.groupedConversations.flatMap { it.second }
        assertThat(initialFlat).hasSize(1)
        assertThat(initialFlat[0].endpointIconUrl).isNull()

        // Configs arrive after the first emission.
        endpointConfigsFlow.value = mapOf(
            "OpenRouter" to EndpointConfig(iconURL = "https://openrouter.ai/favicon.ico"),
        )
        advanceUntilIdle()

        val updatedFlat = viewModel.uiState.value.groupedConversations.flatMap { it.second }
        assertThat(updatedFlat).hasSize(1)
        assertThat(updatedFlat[0].endpointIconUrl).isEqualTo("https://openrouter.ai/favicon.ico")
    }
}
