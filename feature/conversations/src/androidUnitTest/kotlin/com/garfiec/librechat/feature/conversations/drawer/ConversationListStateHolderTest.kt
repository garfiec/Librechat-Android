package com.garfiec.librechat.feature.conversations.drawer

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.model.Conversation
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
class ConversationListStateHolderTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope

    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)

    private val convos = listOf(
        Conversation(conversationId = "c1", title = "Alpha One", updatedAt = Instant.parse("2026-02-19T10:00:00.000Z")),
        Conversation(conversationId = "c2", title = "Alpha Two", updatedAt = Instant.parse("2026-02-19T09:00:00.000Z")),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        scope = CoroutineScope(testDispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun visibleIds(holder: ConversationListStateHolder) =
        holder.groupedConversations.value
            .flatMap { it.second }
            .mapNotNull { it.conversationId }

    @Test
    fun `delete during active drawer search removes the row without a query change`() = runTest {
        // Punted Bug #25 (drawer side): the drawer search is a client-side filter over live Room
        // data, so a delete (Room re-emits) must vanish from the visible results immediately, not
        // wait for the next keystroke.
        val roomFlow = MutableStateFlow<Result<List<Conversation>>>(Result.Success(convos))
        every { conversationRepository.observeConversations(any()) } returns roomFlow

        val holder = ConversationListStateHolder(conversationRepository, scope, dayBoundaries = emptyFlow())
        advanceUntilIdle()

        holder.onSearchQueryChanged("Alpha")
        advanceUntilIdle() // clears the search debounce
        assertThat(visibleIds(holder)).containsExactly("c1", "c2")

        // c1 deleted from the drawer: Room re-emits without it while the search is still active.
        roomFlow.value = Result.Success(convos.filter { it.conversationId == "c2" })
        advanceUntilIdle()

        assertThat(visibleIds(holder)).containsExactly("c2")
    }

    @Test
    fun `rename during active drawer search updates the visible title`() = runTest {
        val roomFlow = MutableStateFlow<Result<List<Conversation>>>(Result.Success(convos))
        every { conversationRepository.observeConversations(any()) } returns roomFlow

        val holder = ConversationListStateHolder(conversationRepository, scope, dayBoundaries = emptyFlow())
        advanceUntilIdle()

        holder.onSearchQueryChanged("Alpha")
        advanceUntilIdle()

        roomFlow.value = Result.Success(
            convos.map { if (it.conversationId == "c1") it.copy(title = "Alpha One Renamed") else it },
        )
        advanceUntilIdle()

        val titles = holder.groupedConversations.value.flatMap { it.second }
            .associate { it.conversationId to it.title }
        assertThat(titles["c1"]).isEqualTo("Alpha One Renamed")
        assertThat(titles.keys).containsExactly("c1", "c2")
    }
}
