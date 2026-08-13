package com.garfiec.librechat.core.network.client

import kotlinx.coroutines.flow.SharedFlow

/**
 * Outcome of a token refresh, so a caller can tell a **hard** failure (the session is genuinely gone
 * — route to re-auth) from a **transient** one (network blip, 5xx, a malformed response, or a server
 * session-lookup false-negative) that must NOT log the user out. The refresh implementation retries
 * transient failures — and a first-seen auth rejection, since the backend returns the same `401` for a
 * transiently-missed session as for a truly-expired one — with backoff before settling on
 * [HardExpired]. A [Transient] result leaves the stored tokens intact so a later attempt (or an app
 * relaunch) can still recover.
 */
enum class RefreshResult { Refreshed, HardExpired, Transient }

interface TokenManager {
    /** Non-suspend check — returns true when an access token is cached in memory. */
    val isAuthenticated: Boolean
    suspend fun getAccessToken(): String?
    suspend fun setTokens(accessToken: String, refreshToken: String)

    /**
     * Refresh the live active account against the live base URL.
     *
     * [usedAccessToken] is **the bearer the caller's failing request actually sent**. Refreshes of one
     * account are single-flighted, so a cold-start fan-out queues N callers behind one lock, all
     * holding the same stale bearer; passing it lets a waiter notice that the holder of the lock
     * already rotated the token and return [RefreshResult.Refreshed] without POSTing a second time.
     * Null (the default) disables that check and preserves the unconditional-POST behaviour.
     */
    suspend fun refreshAccessToken(usedAccessToken: String? = null): RefreshResult

    /**
     * Full teardown of the active session — keyed tokens, any bare staging keys, and the persisted
     * active-account pointer — bumping the refresh epoch so an in-flight refresh discards its result.
     * The single teardown implementation; [onAccountCleared] is the logout-named alias that delegates
     * here.
     */
    suspend fun clearTokens()

    /**
     * The access token stored in [accountId]'s keyed slot, independent of which account is active.
     * Multi-account callers (the switch barrier) read the bearer for a specific request's account
     * rather than the live active one.
     */
    suspend fun getAccessTokenFor(accountId: String): String?

    /**
     * The access token currently **staged** under the bare keys by an interactive sign-in
     * ([setTokens]) that has not yet been re-homed by [onAccountResolved] — i.e. the token of an
     * account mid-authentication. Null outside that window. The add-account flow's pending request
     * identity reads its bearer from here.
     */
    suspend fun getStagedAccessToken(): String?

    /**
     * Drop any bare-key staged pair left by an abandoned or crashed sign-in, without touching any
     * account's keyed slot, the mirror, or the refresh epoch. Callers must hold a resolved active
     * account (whose real session is keyed) — when logged out or on a legacy pre-keying install the
     * bare keys ARE the live session and must not be cleared through this.
     */
    suspend fun clearStagedTokens()

    /**
     * Non-destructive switch to an already-keyed account: repoints the active binding, the
     * synchronously-readable mirror, and the cached bearer to [accountId] **without writing or
     * deleting any token slot**. The previously active account's tokens are retained so a switch back
     * needs no re-login. Unlike [onAccountResolved] this does not consume staged tokens — the target
     * must already have keyed tokens.
     */
    suspend fun selectAccount(accountId: String)

    /**
     * Delete [accountId]'s keyed tokens (account removal). When [accountId] is the active account this
     * also clears the mirror + cached bearer, like [onAccountCleared]; otherwise the active account is
     * left untouched.
     */
    suspend fun removeAccount(accountId: String)

    /**
     * Refresh [accountId]'s tokens against [baseUrl] (an **absolute** URL, so a server-URL switch that
     * races this call can never send this account's refresh token to a different server). Writes
     * [accountId]'s keyed slot and updates the cached bearer only while [accountId] is still the active
     * account. Distinct from [refreshAccessToken], which refreshes the live active account against the
     * live base URL.
     *
     * [usedAccessToken] carries the same coalescing hint as on [refreshAccessToken].
     */
    suspend fun refreshAccessTokenFor(
        accountId: String,
        baseUrl: String,
        usedAccessToken: String? = null,
    ): RefreshResult

    /**
     * Renew [accountId]'s access token **before** a request goes out, if [currentAccessToken] is at or
     * near its expiry, and return the bearer the caller should use. Returns [currentAccessToken]
     * unchanged when the token is still good, absent, carries no readable deadline, or the renewal
     * did not succeed.
     *
     * Renewal is otherwise driven entirely by failure — a token is refreshed only after a request
     * using it has come back `401`. Access tokens live 15 minutes by default, so every cold start
     * after an idle period pays a full round trip of 401-then-refresh-then-retry on the critical path
     * before anything renders (issue #323). Checking the deadline we already hold turns that into one
     * refresh with no 401 at all.
     *
     * The current bearer is passed **in** rather than re-read: this runs on every request the barrier
     * builds, and the caller has already read the identity triple under its own lock. Re-reading here
     * would both cost a second lock acquisition per request and risk pairing a token with a different
     * account than the caller snapshotted.
     *
     * **Best-effort and non-signalling.** It must never tear down a session or emit session-expired:
     * a failed renewal leaves everything as it was and the request proceeds on the old bearer, so the
     * reactive 401 path — which retries and can distinguish a dead session from a server
     * false-negative — remains the only thing that logs anyone out.
     *
     * Defaulted to returning the token unchanged, so an implementation that is not the token store
     * opts out by saying nothing rather than being forced into a stub.
     */
    suspend fun ensureFreshAccessToken(
        accountId: String?,
        baseUrl: String,
        currentAccessToken: String?,
    ): String? = currentAccessToken

    /**
     * Bind token storage to [accountId] once identity resolves (login, cold-start restore, upgrade).
     * Re-homes the staged (bare-key) authentication tokens into this account's keyed slot and repoints
     * the synchronously-readable mirror, so subsequent cold starts seed the right account's bearer with
     * no blind window. Removes only the bare staging keys — never another account's keyed slot — so a
     * previously-active account's tokens survive for a later switch. Idempotent when the account is
     * unchanged.
     */
    suspend fun onAccountResolved(accountId: String)

    /**
     * Clear the active account's tokens, any bare staging keys, and the persisted mirror on logout.
     * The semantic logout entry point; delegates to [clearTokens] — same teardown, named for the
     * identity-transition call site so implementers get one behaviour, not two that can drift.
     */
    suspend fun onAccountCleared() = clearTokens()

    /**
     * Signal that a session's credentials are irrecoverably invalid (refresh failed / banned), which
     * routes the app to re-auth. [expiredAccountId] scopes the signal to the account whose request
     * failed: it emits only while that account is still the active binding, so a retained-but-inactive
     * account's failed refresh (a straggler request landing after a switch) can't tear down the *live*
     * account's session. The expired account keeps its rows and roster entry — re-auth restores it
     * without data loss. Null = the active/legacy session; always emits.
     */
    fun emitSessionExpired(
        expiredAccountId: String? = null,
        reason: SessionEndReason = SessionEndReason.EXPIRED,
    )

    /**
     * Emits once per ended session. Implementations coalesce: a cold start fans out several requests
     * and every one of them discovers the same dead session independently, and each emission replays
     * the logout navigation.
     */
    val sessionExpiredFlow: SharedFlow<SessionEndReason>
}
