package com.garfiec.librechat.core.data.datastore

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.network.client.AccountReadyGate
import com.garfiec.librechat.core.network.client.TokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the account **roster** cold-start seed and is the single [AccountReadyGate] every request /
 * route waits on. Persistence itself lives in [AccountRoster]; this class orchestrates the seed.
 *
 * There are three "active account" facts and they **cannot** be unified: the token store's
 * synchronously-seeded secure-storage mirror (the fast bearer path), the durable roster active pointer,
 * and the `serverId` derived from the persisted server URL. **The roster active pointer is the
 * authority**; the mirror is a bearer-seed cache. At cold start this seed reconciles them:
 *
 * 1. Read the persisted server URL (awaiting [ServerDataStore]'s own warm-up) — the migration input.
 * 2. One-time migrate the pre-roster single-active pointer into a one-entry roster ([AccountRoster.migrateIfNeeded]).
 * 3. Read the roster snapshot (authority).
 * 4. If an active entry exists: **drive** the server URL from it (enforcing the url↔account invariant
 *    even if the persisted URL drifted), reconcile the token mirror to it ([TokenManager.selectAccount],
 *    idempotent + non-destructive), then publish identity. If none: publish `Resolved(null)` and touch
 *    **no** tokens — an empty roster with tokens at rest is either a legacy pre-tenancy upgrade (bare
 *    tokens the restore safety net must claim, `restoreAccountIfNeeded`) or a crash mid-establish
 *    (keyed tokens the same safety net re-establishes); destroying them here would force a re-login.
 *
 * The publish uses the **low-level** [ActiveAccountProvider.set] / [ActiveAccountProvider.clear], never
 * [upsertActive] / [clearActiveAccount] — those await [seeded] and would self-deadlock this coroutine.
 * The whole body is inside the `try/finally` so [seeded] always completes and the [AccountReadyGate]
 * never hangs, even on an unexpected failure.
 */
class AccountRegistry(
    private val roster: AccountRoster,
    private val activeAccountProvider: ActiveAccountProvider,
    private val serverDataStore: ServerDataStore,
    private val tokenManager: TokenManager,
    appScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : AccountReadyGate {

    private val seeded = CompletableDeferred<Unit>()

    init {
        appScope.launch(ioDispatcher) {
            try {
                // The persisted URL warm-up feeds migration and is the pre-roster fallback for the
                // login screen. awaitBaseUrl() resolves ServerDataStore's own async warm-up.
                val legacyUrl = serverDataStore.awaitBaseUrl()
                roster.migrateIfNeeded(legacyUrl)
                val activeEntry = roster.snapshot().activeEntry
                if (activeEntry == null) {
                    // Authority says no account — publish logged-out, but leave the token store
                    // alone: on a pre-tenancy upgrade the roster is empty while the user's session
                    // lives under the bare keys, and clearing here would destroy it before the
                    // restore safety net (restoreAccountIfNeeded) can claim it.
                    activeAccountProvider.clear()
                } else {
                    // Drive the URL from the active entry BEFORE publishing identity: the token bearer
                    // and the server it is sent to must agree from the first admitted request.
                    serverDataStore.setServerUrl(activeEntry.serverUrl)
                    // Point the sync mirror at the authority. Idempotent when already aligned (the
                    // common case); re-homes a crash-diverged mirror. Writes/deletes nothing.
                    tokenManager.selectAccount(activeEntry.accountId)
                    activeAccountProvider.set(AccountId(activeEntry.accountId))
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

    /** [AccountReadyGate]: suspends until the cold-start seed + reconcile has resolved. */
    override suspend fun awaitReady() = seeded.await()

    /**
     * Records [entry] as the active account (upsert + activate) and publishes it. Called by the login
     * paths and cold-start restore via [AccountSessionEstablisher]. Ordered after the seed so its async
     * resolve can't land after this set and clobber the just-established account back to stale.
     */
    suspend fun upsertActive(entry: AccountEntry) {
        seeded.await()
        roster.upsertAndActivate(entry)
        activeAccountProvider.set(AccountId(entry.accountId))
    }

    /**
     * Clears the active account (logout / remove-active). **Flip-to-null first** (collectors must tear
     * down their account-scoped query before any row delete), then drop the entry from the roster.
     */
    suspend fun clearActiveAccount() {
        seeded.await()
        val activeId = roster.snapshot().activeId
        activeAccountProvider.clear()
        if (activeId != null) roster.removeAndDeactivate(activeId)
    }
}
