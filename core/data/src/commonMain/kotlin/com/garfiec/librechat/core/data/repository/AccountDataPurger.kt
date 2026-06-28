package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.ConversationTagDao
import com.garfiec.librechat.core.data.db.dao.DraftDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Deletes every tenant row belonging to one [AccountId] — the scoped purge that replaces the old
 * "logout never touches Room" leak. Run
 * after the account's session has been torn down (writes cancelled) and the active account flipped to
 * null, so the deletes can't race a live read collector or an in-session write.
 *
 * Per-table sequential deletes (no FK cascade is declared between these tables); a process death
 * mid-purge leaves only rows still owned by the now-inactive account, which are invisible to every
 * account-filtered read and are the orphan sweep's responsibility.
 */
class AccountDataPurger(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val draftDao: DraftDao,
    private val tagDao: ConversationTagDao,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun purge(accountId: AccountId) = withContext(ioDispatcher) {
        val id = accountId.value
        messageDao.deleteAllForAccount(id)
        draftDao.deleteAllForAccount(id)
        tagDao.deleteAllForAccount(id)
        conversationDao.deleteAllForAccount(id)
    }
}
