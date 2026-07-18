package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.response.ConversationListResponse
import com.garfiec.librechat.core.network.api.ConversationsApi
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryImplTest {

    private val conversationsApi = mockk<ConversationsApi>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val roster = mockk<AccountRoster>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val account = AccountId("srv:user-1")
    private val activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(account))

    private lateinit var repository: ConversationRepositoryImpl

    @Before
    fun setup() {
        repository = ConversationRepositoryImpl(
            conversationsApi = conversationsApi,
            conversationDao = conversationDao,
            messageDao = messageDao,
            activeAccountProvider = activeAccountProvider,
            roster = roster,
            json = json,
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun entity(id: String, tagsJson: String) = ConversationEntity(
        conversationId = id,
        title = "Title $id",
        user = "user-1",
        endpoint = null,
        endpointType = null,
        model = null,
        agentId = null,
        isArchived = false,
        tags = tagsJson,
        iconURL = null,
        greeting = null,
        modelParams = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastSyncedAt = 0L,
    )

    @Test
    fun `loadNextPage delegates server entities to upsertPreservingTags`() = runTest {
        val serverConvo = Conversation(conversationId = "convo-1", title = "Server Title")
        coEvery { conversationsApi.getConversations(any(), any(), any(), any(), any(), any(), any()) } returns
            ConversationListResponse(conversations = listOf(serverConvo), nextCursor = null)

        val captured = slot<List<ConversationEntity>>()
        coEvery { conversationDao.upsertPreservingTags(account.value, capture(captured)) } answers {}

        val result = repository.loadNextPage(cursor = null)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val entity = captured.captured.single()
        assertThat(entity.conversationId).isEqualTo("convo-1")
        assertThat(entity.title).isEqualTo("Server Title")
    }

    @Test
    fun `delete cascades to the conversation's cached messages`() = runTest {
        coEvery { conversationsApi.deleteConversation("convo-1") } just Runs

        val result = repository.delete("convo-1")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // Room has no FK cascade, so the row-only delete would strand the messages and let a bound
        // chat pane keep rendering the deleted thread. Both deletes must be account-scoped.
        coVerify(exactly = 1) { conversationDao.deleteById("convo-1", account.value) }
        coVerify(exactly = 1) { messageDao.deleteAllForConversation("convo-1", account.value) }
    }

    @Test
    fun `delete still succeeds when the message cascade fails`() = runTest {
        coEvery { conversationsApi.deleteConversation("convo-1") } just Runs
        // Server row + conversation row are already gone; a Room failure purging the cached
        // messages must not flip delete() to Error (that would suppress the drawer's navigate-off).
        coEvery { messageDao.deleteAllForConversation("convo-1", account.value) } throws RuntimeException("disk I/O")

        val result = repository.delete("convo-1")

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `delete keeps local rows when no account is resolved`() = runTest {
        activeAccountProvider.clear() // Resolved(null) — currentAccountId() is null
        coEvery { conversationsApi.deleteConversation("convo-1") } just Runs

        repository.delete("convo-1")

        // Unresolved account: the by-PK local deletes can't be safely scoped, so neither runs.
        coVerify(exactly = 0) { conversationDao.deleteById(any(), any()) }
        coVerify(exactly = 0) { messageDao.deleteAllForConversation(any(), any()) }
    }

    @Test
    fun `saveConversation delegates to upsertPreservingTags`() = runTest {
        val streamingConvo = Conversation(conversationId = "convo-1", title = "Updated mid-stream")

        val captured = slot<ConversationEntity>()
        coEvery { conversationDao.upsertPreservingTags(account.value, capture(captured)) } answers {}

        repository.saveConversation(streamingConvo, originAccount = null)

        assertThat(captured.captured.conversationId).isEqualTo("convo-1")
        assertThat(captured.captured.title).isEqualTo("Updated mid-stream")
    }

    @Test
    fun `saveConversation no-op for blank conversationId`() = runTest {
        val blank = Conversation(conversationId = "", title = "Blank")

        repository.saveConversation(blank, originAccount = null)

        coVerify(exactly = 0) { conversationDao.upsertPreservingTags(any(), any<ConversationEntity>()) }
    }

    @Test
    fun `saveConversation stamps the captured origin account, not the live active one`() = runTest {
        // The stream started under account A; the user has since switched so B is now active.
        val origin = AccountId("srv:origin-A")
        activeAccountProvider.set(AccountId("srv:active-B"))
        coEvery { roster.contains(origin.value) } returns true // A is still a known account
        val captured = slot<ConversationEntity>()
        coEvery { conversationDao.upsertPreservingTags(origin.value, capture(captured)) } answers {}

        repository.saveConversation(Conversation(conversationId = "c1", title = "A's reply"), originAccount = origin)

        // Row is attributed to A (the origin), never to the live active B.
        assertThat(captured.captured.accountId).isEqualTo(origin.value)
        coVerify(exactly = 0) { conversationDao.upsertPreservingTags("srv:active-B", any<ConversationEntity>()) }
    }

    @Test
    fun `saveConversation skips when the captured origin account was removed since capture`() = runTest {
        val origin = AccountId("srv:removed-A")
        coEvery { roster.contains(origin.value) } returns false // A was removed (logout / remove-account)

        repository.saveConversation(Conversation(conversationId = "c1", title = "orphan"), originAccount = origin)

        // Don't resurrect a removed account's purged rows.
        coVerify(exactly = 0) { conversationDao.upsertPreservingTags(any(), any<ConversationEntity>()) }
    }

    @Test
    fun `syncFavoritesFromServer adds SAVED_TAG to existing rows server reports as favorited`() = runTest {
        val serverConvo = Conversation(conversationId = "convo-1", title = "Cross-client fav")
        coEvery {
            conversationsApi.getConversations(any(), any(), any(), any(), any(), any(), any())
        } returns ConversationListResponse(conversations = listOf(serverConvo), nextCursor = null)
        coEvery { conversationDao.getByIdForAccount("convo-1", account.value) } returns entity("convo-1", """["work"]""")
        coEvery { conversationDao.observeConversationsForAccount(account.value, false) } returns flowOf(emptyList())
        coEvery { conversationDao.updateTags(any(), any(), any(), any()) } just Runs

        repository.syncFavoritesFromServer()

        val tagsCaptor = slot<String>()
        coVerify(exactly = 1) {
            conversationDao.updateTags("convo-1", capture(tagsCaptor), any(), any())
        }
        assertThat(json.decodeFromString<List<String>>(tagsCaptor.captured))
            .containsExactly("work", SAVED_TAG).inOrder()
    }

    @Test
    fun `syncFavoritesFromServer removes SAVED_TAG from rows server no longer considers favorited`() = runTest {
        coEvery {
            conversationsApi.getConversations(any(), any(), any(), any(), any(), any(), any())
        } returns ConversationListResponse(conversations = emptyList(), nextCursor = null)
        val staleFav = entity("convo-stale", """["work","Saved"]""")
        coEvery { conversationDao.observeConversationsForAccount(account.value, false) } returns flowOf(listOf(staleFav))
        coEvery { conversationDao.updateTags(any(), any(), any(), any()) } just Runs

        repository.syncFavoritesFromServer()

        val tagsCaptor = slot<String>()
        coVerify(exactly = 1) {
            conversationDao.updateTags("convo-stale", capture(tagsCaptor), any(), any())
        }
        assertThat(json.decodeFromString<List<String>>(tagsCaptor.captured))
            .containsExactly("work")
    }

    @Test
    fun `syncFavoritesFromServer upserts missing conversations with SAVED_TAG preset`() = runTest {
        val serverConvo = Conversation(conversationId = "convo-new", title = "From web")
        coEvery {
            conversationsApi.getConversations(any(), any(), any(), any(), any(), any(), any())
        } returns ConversationListResponse(conversations = listOf(serverConvo), nextCursor = null)
        coEvery { conversationDao.getByIdForAccount("convo-new", account.value) } returns null
        coEvery { conversationDao.observeConversationsForAccount(account.value, false) } returns flowOf(emptyList())

        val upsertCaptor = slot<ConversationEntity>()
        coEvery { conversationDao.upsert(capture(upsertCaptor)) } answers {}

        repository.syncFavoritesFromServer()

        assertThat(upsertCaptor.captured.conversationId).isEqualTo("convo-new")
        assertThat(json.decodeFromString<List<String>>(upsertCaptor.captured.tags))
            .containsExactly(SAVED_TAG)
    }

    @Test
    fun `syncFavoritesFromServer paginates through multiple pages`() = runTest {
        val page1 = ConversationListResponse(
            conversations = listOf(Conversation(conversationId = "c1")),
            nextCursor = "cursor-2",
        )
        val page2 = ConversationListResponse(
            conversations = listOf(Conversation(conversationId = "c2")),
            nextCursor = null,
        )
        coEvery {
            conversationsApi.getConversations(null, any(), any(), any(), any(), any(), any())
        } returns page1
        coEvery {
            conversationsApi.getConversations("cursor-2", any(), any(), any(), any(), any(), any())
        } returns page2
        coEvery { conversationDao.getByIdForAccount(any(), any()) } returns null
        coEvery { conversationDao.observeConversationsForAccount(account.value, false) } returns flowOf(emptyList())
        coEvery { conversationDao.upsert(any()) } answers {}

        repository.syncFavoritesFromServer()

        coVerify(exactly = 1) {
            conversationsApi.getConversations(null, any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            conversationsApi.getConversations("cursor-2", any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 2) { conversationDao.upsert(any()) }
    }

    @Test
    fun `syncFavoritesFromServer no-op when local and server match`() = runTest {
        val serverConvo = Conversation(conversationId = "convo-1")
        coEvery {
            conversationsApi.getConversations(any(), any(), any(), any(), any(), any(), any())
        } returns ConversationListResponse(conversations = listOf(serverConvo), nextCursor = null)
        coEvery { conversationDao.getByIdForAccount("convo-1", account.value) } returns entity("convo-1", """["Saved"]""")
        coEvery {
            conversationDao.observeConversationsForAccount(account.value, false)
        } returns flowOf(listOf(entity("convo-1", """["Saved"]""")))

        repository.syncFavoritesFromServer()

        coVerify(exactly = 0) { conversationDao.updateTags(any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationDao.upsert(any()) }
    }
}
