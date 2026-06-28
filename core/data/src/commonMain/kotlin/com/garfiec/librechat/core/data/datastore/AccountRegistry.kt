package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Device-global persistence of the **active account id**, and the cold-start seeder of
 * [ActiveAccountProvider].
 *
 * There is exactly one active account at a time. The id is the single persisted identity
 * fact (the leak persists because today *nothing* records who the cached rows belong to);
 * establishing it is the prerequisite the rest of row-tenancy builds on.
 *
 * Cold start mirrors [ServerDataStore]'s async warm-up so Koin can instantiate this on Main without
 * blocking: it reads the persisted id off the IO dispatcher and resolves [ActiveAccountProvider] from
 * [AccountState.Warming] to the right [AccountState.Resolved]. The read **chains the URL warm-up**
 * — `awaitBaseUrl()` first — so identity never resolves before the server it belongs to is
 * known. A missing/blank id or any failure resolves to the logged-out state `Resolved(null)`, never
 * left [AccountState.Warming] and never failing open to a wrong account.
 */
class AccountRegistry(
    private val dataStore: DataStore<Preferences>,
    private val activeAccountProvider: ActiveAccountProvider,
    private val serverUrlProvider: ServerUrlProvider,
    appScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {

    private val seeded = CompletableDeferred<Unit>()

    init {
        appScope.launch(ioDispatcher) {
            try {
                // Chain the URL warm-up: identity resolves only after the server is known.
                serverUrlProvider.awaitBaseUrl()
                val stored = dataStore.data.map { prefs -> prefs[KEY_ACTIVE_ACCOUNT_ID] }.first()
                if (stored.isNullOrBlank()) {
                    activeAccountProvider.clear()
                } else {
                    activeAccountProvider.set(AccountId(stored))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fail to logged-out, never to a guessed account.
                activeAccountProvider.clear()
            } finally {
                seeded.complete(Unit)
            }
        }
    }

    /** Suspends until the cold-start seed has resolved the provider (for ordered consumers / tests). */
    suspend fun awaitSeeded() {
        seeded.await()
    }

    /**
     * Records [id] as the active account and publishes it. Persist-then-publish: disk is the source
     * of truth, so a crash between the two is reconciled (re-seeded) on the next cold start. Called by
     * the login paths and the account-switch transition.
     */
    suspend fun setActiveAccount(id: AccountId) {
        // Order after the cold-start seed: otherwise its async resolve can land *after* this set
        // and clobber the just-established account back to null/stale (a spurious logout). The
        // login paths don't await the seed themselves, so the gate lives here.
        seeded.await()
        dataStore.edit { prefs -> prefs[KEY_ACTIVE_ACCOUNT_ID] = id.value }
        activeAccountProvider.set(id)
    }

    /**
     * Clears the active account (logout / account-remove). **Flip-to-null first** (collectors
     * must tear down their account-scoped query before any row delete), then drop it from disk.
     */
    suspend fun clearActiveAccount() {
        // Same ordering guard as setActiveAccount: don't let a late seed re-publish a stale account
        // after we've cleared it.
        seeded.await()
        activeAccountProvider.clear()
        dataStore.edit { prefs -> prefs.remove(KEY_ACTIVE_ACCOUNT_ID) }
    }

    private companion object {
        val KEY_ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
    }
}
