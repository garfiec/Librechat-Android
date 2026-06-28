package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.data.db.dao.DraftDao
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import com.garfiec.librechat.core.model.NEW_CHAT_DRAFT_KEY
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Guards the row-tenancy boundary on the in-memory side of [DraftRepositoryImpl]: the account-blind
 * `cache`/`debounceJobs` must be dropped on every identity change, so account A's unsent compose-box
 * text can neither be read back under B nor land in Room after the account that owned it went away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftRepositoryImplTest {

    private val accountA = AccountId("srv:userA")
    private val accountB = AccountId("srv:userB")

    @Test
    fun getDraft_afterAccountSwitch_doesNotReturnPreviousAccountsCachedText() = runTest {
        val dao = mockk<DraftDao>(relaxed = true)
        // No persisted rows for either account: a cache miss must yield null, not a fabricated row.
        coEvery { dao.getDraftForAccount(any(), any()) } returns null
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        val repo = DraftRepositoryImpl(dao, provider, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // A types into the new-chat box; the debounced write persists for A.
        repo.saveDraft(NEW_CHAT_DRAFT_KEY, "A's secret")
        testScheduler.advanceUntilIdle()
        assertThat(repo.getDraft(NEW_CHAT_DRAFT_KEY)).isEqualTo("A's secret")

        // Switch identity to B. The cache must be cleared, so getDraft falls through to the
        // account-scoped DAO read (which has nothing for B) instead of leaking A's text.
        provider.set(accountB)
        testScheduler.advanceUntilIdle()

        // Null (not "A's secret") proves the cache was cleared and the read fell through to B's
        // empty account-scoped DAO result.
        assertThat(repo.getDraft(NEW_CHAT_DRAFT_KEY)).isNull()
    }

    @Test
    fun getDraft_doesNotLeakCachedText_inWindowBeforeIdentityCollectorClearsCache() = runTest {
        val dao = mockk<DraftDao>(relaxed = true)
        coEvery { dao.getDraftForAccount(any(), any()) } returns null
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        val repo = DraftRepositoryImpl(dao, provider, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // A populates the account-blind sentinel slot in the in-memory cache.
        repo.saveDraft(NEW_CHAT_DRAFT_KEY, "A's secret")
        testScheduler.advanceUntilIdle()

        // Flip identity to B but do NOT advance the scheduler: the async clearPendingState() collector
        // has not run yet, so A's text is still physically in the cache. A read under B must still miss
        // (the cache is keyed by account), proving the fix doesn't rely on racing the collector.
        provider.set(accountB)
        assertThat(repo.getDraft(NEW_CHAT_DRAFT_KEY)).isNull()
    }

    @Test
    fun awaitDraft_suspendsThroughWarming_thenReturnsPersistedDraftOnceResolved() = runTest {
        val dao = mockk<DraftDao>(relaxed = true)
        // A's draft is persisted in Room (the post-migration legacy-claim case), but the active
        // account hasn't resolved yet.
        coEvery { dao.getDraftForAccount(NEW_CHAT_DRAFT_KEY, accountA.value) } returns
            DraftEntity(conversationId = NEW_CHAT_DRAFT_KEY, text = "A's draft", accountId = accountA.value)
        val provider = InMemoryActiveAccountProvider(AccountState.Warming)
        val repo = DraftRepositoryImpl(dao, provider, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // Start the restore while warming: a one-shot getDraft would return null here. awaitDraft must
        // park until the account resolves rather than miss the persisted row.
        val restored = async { repo.awaitDraft(NEW_CHAT_DRAFT_KEY) }
        testScheduler.advanceUntilIdle()
        assertThat(restored.isCompleted).isFalse()

        provider.set(accountA)
        testScheduler.advanceUntilIdle()

        assertThat(restored.await()).isEqualTo("A's draft")
    }

    @Test
    fun deleteDraft_whenAccountUnresolved_doesNotTouchRoom() = runTest {
        val dao = mockk<DraftDao>(relaxed = true)
        // Warming / logged out: there is no account this caller may delete, so the unscoped
        // delete-by-id must not run and destroy whichever account owns the shared sentinel row.
        val provider = InMemoryActiveAccountProvider(AccountState.Warming)
        val repo = DraftRepositoryImpl(dao, provider, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        repo.deleteDraft(NEW_CHAT_DRAFT_KEY)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { dao.deleteDraftForAccount(any(), any()) }
    }

    @Test
    fun deleteDraft_scopesRoomDeleteToActiveAccount() = runTest {
        val dao = mockk<DraftDao>(relaxed = true)
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        val repo = DraftRepositoryImpl(dao, provider, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        repo.deleteDraft(NEW_CHAT_DRAFT_KEY)
        testScheduler.advanceUntilIdle()

        // Scoped delete only — the unscoped by-PK delete that could reach another account's row no
        // longer exists on the DAO.
        coVerify(exactly = 1) { dao.deleteDraftForAccount(NEW_CHAT_DRAFT_KEY, accountA.value) }
    }

    @Test
    fun debouncedWrite_isDropped_whenIdentityFlipsBeforeItLands() = runTest {
        val dao = mockk<DraftDao>(relaxed = true)
        val provider = InMemoryActiveAccountProvider(AccountState.Resolved(accountA))
        val repo = DraftRepositoryImpl(dao, provider, StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // Edit captured for A, but identity flips (logout / switch) before the 500ms debounce fires.
        repo.saveDraft("c1", "A's text")
        provider.set(accountB)
        testScheduler.advanceUntilIdle()

        // The write must not land as a stray A row (which logout's scoped purge could no longer reach).
        coVerify(exactly = 0) { dao.upsertDraft(any<DraftEntity>()) }
    }
}
