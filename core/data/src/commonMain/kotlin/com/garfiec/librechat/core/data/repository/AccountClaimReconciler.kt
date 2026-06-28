package com.garfiec.librechat.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.data.db.dao.AccountClaimDao
import com.garfiec.librechat.core.model.NEW_CHAT_DRAFT_KEY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Runs the one-time legacy-data [AccountClaimDao.claimLegacyRows] when an account first resolves. Invoked by the account-establishment path
 * ([AccountSessionEstablisher.establish], driven by login / cold-start session restore) once the
 * active [AccountId] is known.
 *
 * A persisted boolean marker short-circuits repeat runs, but it is only an optimization: the claim is
 * idempotent and per-user, so running it twice — or even for a second account after a crash dropped
 * the marker — neither leaks nor corrupts (atomicity is met by idempotency, not a shared
 * transaction with the marker).
 */
class AccountClaimReconciler(
    private val claimDao: AccountClaimDao,
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param userKey the legacy `user` column value (the user's Mongo `_id`) the establisher already
     * resolved when composing [accountId]; passed in rather than re-parsed out of the composite id so
     * the `serverId:userKey` format stays owned solely by `deriveAccountId`.
     */
    suspend fun claimIfNeeded(accountId: AccountId, userKey: String) {
        withContext(ioDispatcher) {
            val alreadyClaimed = dataStore.data.first()[KEY_LEGACY_CLAIM_DONE] == true
            if (alreadyClaimed) return@withContext

            claimDao.claimLegacyRows(
                accountId = accountId.value,
                userKey = userKey,
                newChatKey = NEW_CHAT_DRAFT_KEY,
            )
            dataStore.edit { prefs -> prefs[KEY_LEGACY_CLAIM_DONE] = true }
        }
    }

    private companion object {
        val KEY_LEGACY_CLAIM_DONE = booleanPreferencesKey("legacy_claim_done")
    }
}
