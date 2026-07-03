package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.extensions.trimTrailingSlash
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.identity.deriveAccountId
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.data.datastore.AccountEntry
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.datastore.AccountScopedPrefsPurger
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.network.client.PendingRequestIdentity
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * An in-progress add-account flow: the target server plus the coroutine-context element that routes
 * the flow's HTTP calls to it (see [PendingRequestIdentity]). Created by [AccountSwitcher.beginAdd];
 * consumed by [AccountSwitcher.completeAdd] on auth success or [AccountSwitcher.cancelAdd] on
 * abandon. Held in memory only — a process death mid-add self-cancels (the cold-start seed
 * reconciles the token binding back to the roster authority).
 */
class PendingAddSession internal constructor(
    val serverUrl: String,
    internal val requestIdentity: PendingRequestIdentity,
) {
    private val _startupConfig = MutableStateFlow<StartupConfig?>(null)

    /** The pending server's validated startup config, probed under the pending identity and never
     *  written to the live server's config state/cache ([ConfigRepository.probeServerUrl]) — the
     *  add-mode login screen reads its feature flags (registration, social logins) from here. */
    val startupConfig: StateFlow<StartupConfig?> = _startupConfig.asStateFlow()

    internal fun attachConfig(config: StartupConfig) {
        _startupConfig.value = config
    }
}

/**
 * Runs [block] under this pending add flow's request identity — routing its HTTP calls to the server
 * being added, with the staged bearer — or straight through when there is no add in progress. The one
 * definition of "route through the pending identity", shared by [AccountSwitcher.withPendingIdentity]
 * and the auth repository's per-call routing so the rule can't drift between them. Callers that need
 * routing and completion to agree pass the same captured [PendingAddSession] to both.
 */
internal suspend fun <T> PendingAddSession?.withRequestIdentity(block: suspend () -> T): T =
    if (this != null) withContext(requestIdentity) { block() } else block()

/**
 * The single coordinator that switches the active account without re-login (issue #179). It owns the
 * *data-layer* transition — server URL, token key, roster pointer, published identity — sequenced
 * atomically under the [SwitchGate] barrier. It does **not** own session-scope teardown; that stays
 * reactive in `SessionManager`, driven by the identity flip this publishes.
 *
 * The switch is deliberately *lighter* than logout: it deletes **no** Room rows (retaining the other
 * account's data is the whole point) and re-keys **no** tokens ([TokenManager.selectAccount] is
 * non-destructive). It repoints identity/URL/token-key so the outgoing account's data + tokens stay at
 * rest for an instant switch back.
 *
 * **In-flight refresh safety:** an A-refresh already in flight when the switch runs is harmless —
 * the account-keyed, URL-pinned refresh path writes A's own slot and skips the active-cache
 * mutation once A is no longer active, so it can neither corrupt B nor stall the switch (the refresh
 * POST runs outside the token store's state lock). No refresh cancellation is needed or buildable.
 *
 * **Add-account** (signing in B while A stays live) is the second transition this owns, and it is
 * non-destructive toward A by construction: the flow's HTTP calls run under a [PendingRequestIdentity]
 * (B's URL + B's *staged* bearer — the global URL and A's identity are never touched), B's
 * freshly-issued tokens bare-stage (A's keyed slot untouched), and only the successful completion
 * flips URL/token/roster/identity to B atomically under the same [SwitchGate] barrier a switch uses.
 * At most one add is pending at a time (the auth screens drive exactly one flow); the pending state
 * lives in memory only, so a killed app self-cancels.
 *
 * **Remove-account** ([remove]) is the third owned transition: the destructive counterpart that
 * purges one account's roster entry, tokens, scoped preferences, and tenant rows — flipping the
 * active identity to a successor (or the logged-out state) first when the removed account was live.
 */
class AccountSwitcher(
    private val roster: AccountRoster,
    private val serverDataStore: ServerDataStore,
    private val tokenManager: TokenManager,
    private val activeAccountProvider: ActiveAccountProvider,
    private val switchGate: SwitchGate,
    private val claimReconciler: AccountClaimReconciler,
    private val switchCacheCleaner: SwitchCacheCleaner,
    private val accountDataPurger: AccountDataPurger,
    private val prefsPurger: AccountScopedPrefsPurger,
    private val sessionCacheCleaner: SessionCacheCleaner,
) {

    /** The in-progress add-account flow, or null. The auth repository reads this to route sign-in
     *  calls through the pending identity and their success through [completeAdd]. */
    @Volatile
    var pendingAdd: PendingAddSession? = null
        private set

    /**
     * Switch the active account to [accountId]. No-op when it isn't in the roster or is already active.
     * The URL + token-key + roster pointer + identity repoint runs inside [SwitchGate.withSwitch], so
     * new requests park until it completes and in-flight requests finish against the account they were
     * snapshotted for. Publishing identity last (inside the gate) makes `SessionManager` reconcile the
     * old session away only once the flip is atomic and complete.
     */
    suspend fun switch(accountId: String) {
        switchGate.withSwitch {
            // Resolve the target and the no-op guards INSIDE the barrier: reading the roster / active
            // pointer outside it opens a TOCTOU window where a concurrent logout could remove the entry
            // (or flip the active account) between the check and the flip, leaving the live identity
            // pointing at an account the persisted roster no longer marks active. Under the gate the
            // read and the repoint are atomic with respect to any other switch/teardown.
            val entry = roster.snapshot().entries.firstOrNull { it.accountId == accountId }
                ?: return@withSwitch
            if (activeAccountProvider.currentAccountId()?.value == accountId) return@withSwitch
            flipTo(entry)
        }
    }

    /**
     * The atomic five-step repoint to [entry]: URL → token binding → roster pointer → cache clear →
     * identity publish. Shared by [switch] and [remove]'s successor flip. NonCancellable because the
     * callers run in UI scopes: a cancellation between the URL flip (persisted synchronously) and the
     * identity publish would otherwise leave a torn `(url=B, tokens/identity=A)` state that survives
     * an app restart — A's bearer sent to B's host. Caller holds the [SwitchGate] barrier.
     */
    private suspend fun flipTo(entry: AccountEntry) {
        withContext(NonCancellable) {
            serverDataStore.setServerUrl(entry.serverUrl)
            tokenManager.selectAccount(entry.accountId)
            roster.activate(entry.accountId)
            // Quiesce → clear → publish: the non-partitionable caches (WebView storage + cookie jar)
            // clear while requests are still parked and before the new identity is observable, so the
            // incoming account never sees the outgoing one's web state or OAuth cookie. Best-effort
            // inside the cleaner — a clear failure doesn't abort the switch.
            switchCacheCleaner.clearOnSwitch()
            activeAccountProvider.set(AccountId(entry.accountId))
        }
    }

    /**
     * Start an add-account flow against [serverUrl] (scheme-qualified; trailing slash trimmed).
     * Requires a resolved active account — adding is "sign in another account while one is live";
     * a logged-out user takes the plain login path. Purges any bare-key staging left by an earlier
     * abandoned sign-in (it would otherwise leak into this flow's pending bearer) and re-asserts the
     * active account's token binding, then exposes the [PendingAddSession] whose [PendingRequestIdentity]
     * the flow's calls must run under.
     *
     * Callers own the lifecycle: exactly one flow at a time, ended by [completeAdd] (auth success)
     * or [cancelAdd] (user abandons / flow fails). Calling again while one is pending restarts it.
     */
    suspend fun beginAdd(serverUrl: String): PendingAddSession {
        val active = checkNotNull(activeAccountProvider.currentAccountId()) {
            "add-account requires a resolved active account; use the plain login flow when logged out"
        }
        val normalizedUrl = serverUrl.trimTrailingSlash()
        require(normalizedUrl.isNotBlank()) { "add-account requires a server URL" }
        // A previously abandoned sign-in (killed app / dropped flow) may have left a staged pair and
        // a dropped active binding. Clear the stale staging so this flow's pending bearer can only
        // ever be a token issued by THIS flow, and re-bind the active account (idempotent no-op when
        // already bound).
        tokenManager.clearStagedTokens()
        tokenManager.selectAccount(active.value)
        val session = PendingAddSession(
            serverUrl = normalizedUrl,
            requestIdentity = PendingRequestIdentity(
                baseUrl = normalizedUrl,
                bearer = { tokenManager.getStagedAccessToken() },
            ),
        )
        pendingAdd = session
        return session
    }

    /**
     * Records the pending server's validated config on the add session (see
     * [PendingAddSession.startupConfig]). No-op when no add is pending (the flow was cancelled
     * between validation and this call).
     */
    fun attachPendingConfig(config: StartupConfig) {
        pendingAdd?.attachConfig(config)
    }

    /**
     * Runs [block] under the pending add flow's request identity, so its HTTP calls target the
     * server being added (config validation, social-login checks) instead of the live server.
     * Passthrough when no add is pending. The auth repository handles its own routing; this is for
     * feature-layer callers (the add-mode auth screens).
     */
    suspend fun <T> withPendingIdentity(block: suspend () -> T): T =
        pendingAdd.withRequestIdentity(block)

    /**
     * Abandon the pending add-account flow: drop the staged tokens the flow's sign-in may have
     * issued and re-assert the active account's token binding (sign-in staging drops it). Every step
     * is best-effort and non-throwing — a cancel must always leave the app on the active account.
     * No-op when nothing is pending.
     */
    suspend fun cancelAdd() {
        if (pendingAdd == null) return
        pendingAdd = null
        withContext(NonCancellable) {
            runCatching { tokenManager.clearStagedTokens() }
                .onFailure { Logger.w(it) { "cancelAdd: staged-token clear failed" } }
            val active = activeAccountProvider.currentAccountId()
            if (active != null) {
                runCatching { tokenManager.selectAccount(active.value) }
                    .onFailure { Logger.w(it) { "cancelAdd: active-account rebind failed" } }
            }
        }
    }

    /**
     * Complete the pending add-account flow for the authenticated [user]: derive the new account's
     * identity from the **pending** server URL (never the live one), run the one-time legacy claim,
     * then atomically — under the switch barrier — point the server URL at the new server, re-home
     * the staged tokens into the account's keyed slot, upsert-and-activate its roster entry, and
     * publish it. The outgoing account keeps its tokens and rows; this is "add, then switch to it".
     *
     * Re-adding an account already in the roster is inherently a switch-with-fresh-login: the upsert
     * replaces its entry and the re-home overwrites its keyed slot with the just-issued pair.
     *
     * On failure the half-applied flip is rolled back (URL + token binding restored to the outgoing
     * account, non-throwing, inside the still-closed gate) and the pending session stays set so the
     * flow can retry or cancel.
     */
    suspend fun completeAdd(user: User): AccountId {
        val pending = checkNotNull(pendingAdd) { "completeAdd without a pending add-account flow" }
        val userKey = user.accountUserKey()
        val serverId = deriveServerId(pending.serverUrl)
        val accountId = deriveAccountId(serverId, userKey)
        // Same claim-before-publish discipline as AccountSessionEstablisher.establish: stamp any
        // legacy unowned rows for this user before scoped reads/writes go live for the account.
        // Best-effort (marker-guarded, retried by a later establish); only cancellation aborts.
        runCatching { claimReconciler.claimIfNeeded(accountId, userKey) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Logger.w(error) { "Legacy account claim failed; completing add anyway (will retry)" }
            }
        switchGate.withSwitch {
            val rollbackUrl = serverDataStore.getBaseUrl()
            val rollbackAccount = activeAccountProvider.currentAccountId()
            var flipped = false
            try {
                serverDataStore.setServerUrl(pending.serverUrl)
                tokenManager.onAccountResolved(accountId.value)
                roster.upsertAndActivate(
                    AccountEntry(
                        accountId = accountId.value,
                        serverUrl = pending.serverUrl,
                        displayLabel = user.displayLabel(pending.serverUrl),
                        avatarUrl = user.avatar?.takeIf { it.isNotBlank() },
                        lastActiveAt = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
                // Same quiesce → clear → publish discipline as switch(): the outgoing account's web
                // state/cookies clear before the added account becomes observable. (The add flow has
                // already consumed its own OAuth cookie, so clearing it here is safe.)
                switchCacheCleaner.clearOnSwitch()
                activeAccountProvider.set(accountId)
                flipped = true
            } finally {
                if (!flipped) {
                    // Restore a coherent outgoing-account state before reopening the gate, so parked
                    // requests never snapshot a half-applied flip. Non-throwing, and NonCancellable so
                    // a cancelled completion still rolls back. (The staged pair may already be
                    // re-homed into the new account's keyed slot; that is harmless at rest and a
                    // retry's re-home no-ops onto it.)
                    withContext(NonCancellable) {
                        runCatching { serverDataStore.setServerUrl(rollbackUrl) }
                            .onFailure { Logger.w(it) { "completeAdd rollback: URL restore failed" } }
                        if (rollbackAccount != null) {
                            runCatching { tokenManager.selectAccount(rollbackAccount.value) }
                                .onFailure { Logger.w(it) { "completeAdd rollback: token rebind failed" } }
                        }
                    }
                }
            }
        }
        pendingAdd = null
        return accountId
    }

    /**
     * Remove [accountId] and every trace of its data: roster entry, keyed tokens, `acct:` preferences,
     * and (outside the gate — the deletes are the slow leg, and the account is already unreachable by
     * then) its tenant Room rows. No-op when it isn't in the roster.
     *
     * Removing the **active** account first flips to the most-recently-active remaining account —
     * a full switch (URL + token binding + cache clear + publish) so the app stays signed in — or,
     * when it was the last account, tears down to the logged-out state (logout-shaped: active tokens +
     * staging + mirror cleared, session file caches cleared) and fires the session-expired signal to
     * route the app to auth. The server URL is deliberately retained on remove-last, matching logout,
     * so the login screen comes back prefilled.
     *
     * Ordering mirrors logout: identity is flipped away from the account *before* its rows are
     * deleted, so no read collector or origin-captured write can observe the purge mid-flight (the
     * roster removal also flips origin-captured stragglers to skip — `resolveWriteAccountId`).
     */
    suspend fun remove(accountId: String) {
        var removed = false
        var removedLastAccount = false
        switchGate.withSwitch {
            val entries = roster.snapshot().entries
            if (entries.none { it.accountId == accountId }) return@withSwitch
            removed = true
            // NonCancellable like [flipTo]: a cancellation between the successor flip / teardown and
            // the roster+token removal below would leave a half-removed account (roster entry with
            // deleted tokens, or vice versa) at rest.
            withContext(NonCancellable) {
                if (activeAccountProvider.currentAccountId()?.value == accountId) {
                    val successor = entries
                        .filterNot { it.accountId == accountId }
                        .maxByOrNull { it.lastActiveAt }
                    if (successor != null) {
                        flipTo(successor)
                    } else {
                        removedLastAccount = true
                        // Publish logged-out first: read collectors tear down their account-scoped
                        // queries before the token/cache teardown, same order as logout. Only the fast
                        // identity flip + token clear run under the gate; the slow WebKit / file-cache
                        // wipes run after it reopens (below).
                        activeAccountProvider.clear()
                        tokenManager.onAccountCleared()
                    }
                }
                roster.removeAndDeactivate(accountId)
                tokenManager.removeAccount(accountId)
                prefsPurger.purge(accountId)
            }
        }
        if (removedLastAccount) {
            // Slow, non-partitionable wipes run OUTSIDE the gate (it has reopened) — a ≤3s WebKit
            // clear held under the gate would park every in-flight request for its whole duration.
            // Still NonCancellable so a scope cancellation can't abort the teardown half-done. The
            // cache cleaner is account-blind (file caches only) — prefsPurger.purge(accountId) above
            // already reaped this account's keyed role cache and other acct:-scoped prefs.
            withContext(NonCancellable) {
                switchCacheCleaner.clearOnSwitch()
                sessionCacheCleaner.clearFileCaches()
            }
            // Reuse the session-expired channel to drive nav-to-auth reactively; the settings screen
            // that triggered the removal has no navigator reference of its own.
            tokenManager.emitSessionExpired()
        }
        if (removed) {
            accountDataPurger.purge(AccountId(accountId))
        }
    }
}
