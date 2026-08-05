package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.mapper.toEntity
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.network.api.MessagesApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * `refreshMessages` replaces a conversation's cached rows, so it is the one message path where
 * getting provenance wrong destroys data rather than merely mislabelling it.
 *
 * The origin parameter guards two different things and both are asserted here: the **network** leg
 * (a GET issued once the origin account is no longer live rides the new account's bearer and base
 * URL, carrying this conversation id to a server that never saw it) and the **write** leg (a landing
 * for an account that has since been removed would resurrect rows the logout purge deleted).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryRefreshProvenanceTest {

    private val messagesApi = mockk<MessagesApi>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val roster = mockk<AccountRoster>(relaxed = true)

    private val accountA = AccountId("srv:user-a")
    private val accountB = AccountId("srv:user-b")
    private val activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setup() {
        repository = MessageRepositoryImpl(
            messagesApi = messagesApi,
            messageDao = messageDao,
            activeAccountProvider = activeAccountProvider,
            roster = roster,
            dispatcher = UnconfinedTestDispatcher(),
        )
        coEvery { roster.contains(any()) } returns true
        coEvery { messageDao.observeMessagesForAccount(any(), any()) } returns flowOf(emptyList())
    }

    private fun message(id: String) = Message(
        messageId = id,
        conversationId = "conv-1",
        parentMessageId = null,
        text = "hello",
        isCreatedByUser = true,
    )

    @Test
    fun `a foreground refresh fetches and replaces under the live account`() = runTest {
        coEvery { messagesApi.getMessages("conv-1") } returns listOf(message("m1"))

        val result = repository.refreshMessages("conv-1", originAccount = null)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) { messagesApi.getMessages("conv-1") }
        coVerify(exactly = 1) {
            messageDao.replaceAllForConversation("conv-1", accountA.value, any())
        }
    }

    @Test
    fun `an origin-captured refresh proceeds while its origin is still live`() = runTest {
        coEvery { messagesApi.getMessages("conv-1") } returns listOf(message("m1"))

        repository.refreshMessages("conv-1", originAccount = accountA)

        coVerify(exactly = 1) {
            messageDao.replaceAllForConversation("conv-1", accountA.value, any())
        }
    }

    /**
     * The important one. After a switch the GET must not be issued at all — not merely have its
     * result discarded, since by then the id has already been sent to the other account's server.
     */
    @Test
    fun `a refresh for a switched-away account never reaches the network`() = runTest {
        activeAccountProvider.set(accountB)

        val result = repository.refreshMessages("conv-1", originAccount = accountA)

        coVerify(exactly = 0) { messagesApi.getMessages(any()) }
        coVerify(exactly = 0) { messageDao.replaceAllForConversation(any(), any(), any()) }
        assertThat(result).isInstanceOf(Result.Error::class.java)
    }

    /**
     * A skip is routine, not a failure, so the cache is served when it holds something. Reporting an
     * error over a populated cache would surface a post-switch banner for a conversation the user
     * is no longer looking at.
     */
    @Test
    fun `a skipped refresh serves the cached rows when it has them`() = runTest {
        activeAccountProvider.set(accountB)
        coEvery { messageDao.observeMessagesForAccount("conv-1", accountA.value) } returns
            flowOf(listOf(message("m1").toEntity().copy(accountId = accountA.value)))

        val result = repository.refreshMessages("conv-1", originAccount = accountA)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 0) { messagesApi.getMessages(any()) }
    }

    /**
     * The account was removed since capture, so its rows were purged at logout. Writing here would
     * bring them back — invisible to every account-filtered read and unreapable by the scoped purge.
     */
    @Test
    fun `a refresh for a removed account does not resurrect its rows`() = runTest {
        coEvery { roster.contains(accountA.value) } returns false
        coEvery { messagesApi.getMessages("conv-1") } returns listOf(message("m1"))

        repository.refreshMessages("conv-1", originAccount = accountA)

        coVerify(exactly = 0) { messageDao.replaceAllForConversation(any(), any(), any()) }
    }
}
