package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.db.dao.ConversationTagDao
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import com.garfiec.librechat.core.model.ConversationTag
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.network.api.TagsApi
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagRepositoryImplTest {

    private val tagsApi = mockk<TagsApi>(relaxed = true)
    private val tagDao = mockk<ConversationTagDao>(relaxed = true)
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val account = AccountId("srv:user-1")
    private val activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(account))

    private lateinit var repository: TagRepositoryImpl

    private val serverTags = listOf(
        ConversationTag(tag = "work", count = 3, position = 0),
        ConversationTag(tag = "personal", count = 2, position = 1),
        ConversationTag(tag = SAVED_TAG, count = 5, position = 2),
    )

    @Before
    fun setup() {
        repository = TagRepositoryImpl(
            tagsApi = tagsApi,
            tagDao = tagDao,
            conversationRepository = conversationRepository,
            activeAccountProvider = activeAccountProvider,
        )
    }

    @Test
    fun `refreshTags fetches from api and replaces this account's tags, stamping accountId`() = runTest {
        coEvery { tagsApi.getTags() } returns serverTags
        coEvery { tagDao.replaceAllForAccount(any(), any()) } just Runs

        val result = repository.refreshTags()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) { tagsApi.getTags() }
        coVerify(exactly = 1) {
            tagDao.replaceAllForAccount(
                account.value,
                match { entities ->
                    entities.size == 3 &&
                        entities.map { it.tag }.containsAll(listOf("work", "personal", SAVED_TAG)) &&
                        entities.all { it.accountId == account.value }
                },
            )
        }
    }

    @Test
    fun `refreshTags returns Error when api throws`() = runTest {
        coEvery { tagsApi.getTags() } throws RuntimeException("network down")

        val result = repository.refreshTags()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { tagDao.replaceAllForAccount(any(), any()) }
    }

    @Test
    fun `toggleFavorite adds SAVED_TAG when not present and preserves existing tags`() = runTest {
        coEvery { tagsApi.updateConversationTags(any(), any()) } just Runs
        coEvery { conversationRepository.updateConversationTagsLocal(any(), any()) } just Runs

        val result = repository.toggleFavorite(
            conversationId = "convo-1",
            currentTags = listOf("work"),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) {
            tagsApi.updateConversationTags("convo-1", listOf("work", SAVED_TAG))
        }
        coVerify(exactly = 1) {
            conversationRepository.updateConversationTagsLocal("convo-1", listOf("work", SAVED_TAG))
        }
    }

    @Test
    fun `toggleFavorite removes SAVED_TAG when already present`() = runTest {
        coEvery { tagsApi.updateConversationTags(any(), any()) } just Runs
        coEvery { conversationRepository.updateConversationTagsLocal(any(), any()) } just Runs

        val result = repository.toggleFavorite(
            conversationId = "convo-1",
            currentTags = listOf("work", SAVED_TAG),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) {
            tagsApi.updateConversationTags("convo-1", listOf("work"))
        }
        coVerify(exactly = 1) {
            conversationRepository.updateConversationTagsLocal("convo-1", listOf("work"))
        }
    }

    @Test
    fun `toggleFavorite removes SAVED_TAG when it is the only tag`() = runTest {
        coEvery { tagsApi.updateConversationTags(any(), any()) } just Runs
        coEvery { conversationRepository.updateConversationTagsLocal(any(), any()) } just Runs

        val result = repository.toggleFavorite(
            conversationId = "convo-1",
            currentTags = listOf(SAVED_TAG),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) {
            tagsApi.updateConversationTags("convo-1", emptyList())
        }
    }

    @Test
    fun `toggleFavorite returns Error when api throws and skips local update`() = runTest {
        coEvery {
            tagsApi.updateConversationTags(any(), any())
        } throws RuntimeException("boom")

        val result = repository.toggleFavorite(
            conversationId = "convo-1",
            currentTags = emptyList(),
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) {
            conversationRepository.updateConversationTagsLocal(any(), any())
        }
    }

    @Test
    fun `observeTags maps dao entities to domain models sorted by position`() = runTest {
        val entities = listOf(
            ConversationTagEntity(
                id = 1,
                tag = "work",
                user = "user-1",
                description = null,
                count = 3,
                position = 0,
                createdAt = 0L,
                updatedAt = 0L,
            ),
            ConversationTagEntity(
                id = 2,
                tag = "personal",
                user = "user-1",
                description = null,
                count = 2,
                position = 1,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        every { tagDao.observeTagsForAccount(account.value) } returns flowOf(entities)

        val result = repository.observeTags().first()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.tag }).containsExactly("work", "personal").inOrder()
        assertThat(result.map { it.count }).containsExactly(3, 2).inOrder()
    }

    @Test
    fun `clearCache deletes only the active account's tags`() = runTest {
        coEvery { tagDao.deleteAllForAccount(any()) } just Runs

        repository.clearCache()

        coVerify(exactly = 1) { tagDao.deleteAllForAccount(account.value) }
    }
}
