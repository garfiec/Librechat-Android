package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.data.db.dao.DraftDao
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DraftRepositoryImpl(
    private val draftDao: DraftDao,
    private val activeAccountProvider: ActiveAccountProvider,
    private val ioDispatcher: CoroutineDispatcher,
) : DraftRepository {

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, String>()
    private val debounceJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        // The cache and pending debounced writes are keyed by (accountId, conversationId), so a read
        // can only surface text written under the active account. We still drop both on every identity
        // change (login / logout / account switch) to bound memory and cancel a since-orphaned account's
        // in-flight write. Cache is empty at construction, so the initial Warming→Resolved emission is a
        // no-op.
        activeAccountProvider.state
            .map { (it as? AccountState.Resolved)?.id?.value }
            .distinctUntilChanged()
            .onEach { clearPendingState() }
            .launchIn(scope)
    }

    private suspend fun clearPendingState() {
        mutex.withLock {
            debounceJobs.values.forEach { it.cancel() }
            debounceJobs.clear()
            cache.clear()
        }
    }

    override suspend fun getDraft(conversationId: String): String? {
        // Resolve identity first, then read the cache under that account's key. drafts share
        // NEW_CHAT_DRAFT_KEY across accounts, so the compose box is the live cross-account vector;
        // binding the cache key to the account closes the leak at the source rather than racing the
        // async identity-change collector that clears the cache.
        val account = activeAccountProvider.currentAccountId() ?: return null
        val key = cacheKey(account.value, conversationId)
        mutex.withLock { cache[key] }?.let { return it }
        val text = draftDao.getDraftForAccount(conversationId, account.value)?.text
        if (text != null) {
            mutex.withLock { cache[key] = text }
        }
        return text
    }

    override suspend fun awaitDraft(conversationId: String): String? {
        // Wait out the cold-start / post-migration warming window before reading, so screen-entry
        // draft restore doesn't race identity resolution. Once resolved, getDraft sees a non-null
        // active account and reads under it.
        activeAccountProvider.awaitResolvedAccount()
        return getDraft(conversationId)
    }

    override suspend fun saveDraft(conversationId: String, text: String) {
        if (text.isBlank()) {
            deleteDraft(conversationId)
            return
        }
        // Capture the account active at save time so the cache key and debounced write both attribute to
        // it. With no resolved account (Warming / logged out) there is nothing to attribute the edit to,
        // so don't cache it (an account-blind entry could be read back under the next account) and don't
        // schedule a write that would orphan a null-accountId row.
        val accountId = activeAccountProvider.currentAccountId()?.value ?: return
        val key = cacheKey(accountId, conversationId)
        // Update cache immediately for fast reads
        mutex.withLock {
            cache[key] = text
            // Schedule debounced write to Room
            debounceJobs[key]?.cancel()
            debounceJobs[key] = scope.launch {
                delay(DEBOUNCE_MS)
                // Drop the write if identity moved away from the account that owned this edit: stamping a
                // since-purged account would resurrect it. Full structural enforcement is the deferred
                // SessionWriter.
                if (accountId == activeAccountProvider.currentAccountId()?.value) {
                    draftDao.upsertDraft(
                        DraftEntity(
                            conversationId = conversationId,
                            text = text,
                            accountId = accountId,
                        ),
                    )
                }
                mutex.withLock { debounceJobs.remove(key) }
            }
        }
    }

    override suspend fun deleteDraft(conversationId: String) {
        // Symmetric with saveDraft: with no resolved account (Warming / logged out) there is nothing
        // this caller may delete — skip Room entirely rather than running an unscoped delete-by-id that
        // would destroy whichever account currently owns the shared NEW_CHAT_DRAFT_KEY sentinel row.
        val accountId = activeAccountProvider.currentAccountId()?.value ?: return
        val key = cacheKey(accountId, conversationId)
        mutex.withLock {
            cache.remove(key)
            debounceJobs.remove(key)?.cancel()
        }
        draftDao.deleteDraftForAccount(conversationId, accountId)
    }

    // accountId is `<sha256 hex>:<Mongo id>` and conversationId is a Mongo id or the new-chat sentinel;
    // none contain a space, so a single space unambiguously separates the two halves of the key.
    private fun cacheKey(accountId: String, conversationId: String): String =
        "$accountId $conversationId"

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}
