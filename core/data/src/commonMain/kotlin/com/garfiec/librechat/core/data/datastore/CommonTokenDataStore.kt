package com.garfiec.librechat.core.data.datastore

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.extensions.trimTrailingSlash
import com.garfiec.librechat.core.common.result.AccessGatewayException
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import com.garfiec.librechat.core.model.response.RefreshResponse
import com.garfiec.librechat.core.network.client.CookieHelper
import com.garfiec.librechat.core.network.client.PinnedServerBaseUrlKey
import com.garfiec.librechat.core.network.client.RefreshResult
import com.garfiec.librechat.core.network.client.SecureTokenStorage
import com.garfiec.librechat.core.network.client.SessionEndReason
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.client.expiresAtEpochMillisOrNull
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Account-keyed secure token store. Tokens are namespaced by account
 * (`acct:<accountId>:access_token`) and **several accounts' tokens are retained at rest at once** —
 * the foundation for switching accounts without re-login (multi-account, issue #179). A switch
 * ([selectAccount]) repoints the active binding to another already-keyed account without touching any
 * token slot; account removal ([removeAccount]) drops exactly one account's slot; and logout
 * ([onAccountCleared]) drops only the active account's slot.
 *
 * The active account is mirrored into the same synchronously-readable secure storage (key
 * [KEY_ACTIVE_ACCOUNT]) rather than the async account registry, so the bearer for the right account
 * is seeded in the constructor — the first-frame `isAuthenticated` check and the first authed request
 * never read a blind bearer at cold start. When no account is resolved (fresh install, logged out, or
 * a legacy pre-keying upgrade) the keys fall back to their bare form, which is also how tokens from an
 * older build are read until [onAccountResolved] re-homes them.
 *
 * **Authentication staging.** An interactive sign-in ([setTokens] from login/OAuth/2FA) always writes
 * the freshly-issued pair to the **bare** keys and drops the active binding, even when another account
 * is active. [onAccountResolved] then re-homes that staged pair into the resolved account's keyed
 * slot. This makes it structurally impossible for a re-login (e.g. a soft-expiry re-auth as a
 * different user) to overwrite the currently-active account's keyed tokens or leave its slot holding
 * another account's credentials. Steady-state token rotation does **not** go through [setTokens] —
 * refresh writes the keyed slot directly.
 *
 * **Locking.** [stateMutex] guards the in-memory identity/cache and short storage reads/writes of it;
 * it is held only for brief critical sections and **never across the refresh network POST**, so a
 * switch or logout never stalls behind a slow refresh. A per-account [flightMutex] serializes
 * refreshes of the *same* account across their POST (single-flight, so a second 401 reads the rotated
 * token instead of re-POSTing a spent one). [tokenEpochs] holds a per-slot epoch bumped by every
 * operation that invalidates THAT slot's token truth (logout, clear, removal, a new authentication,
 * an identity re-home); a refresh captures its slot's epoch before its POST and discards its result
 * if it changed, so a refresh that races a teardown can never resurrect the cleared session even
 * though the POST holds no lock — while another account's changes leave it valid.
 */
abstract class CommonTokenDataStore(
    private val refreshClient: Lazy<HttpClient>,
) : TokenManager, SecureTokenStorage {

    @Volatile
    private var cachedAccessToken: String? = null

    /**
     * The account whose tokens are currently active, or `null` when logged out or on a legacy install
     * whose tokens still live under the bare keys. Seeded synchronously from [KEY_ACTIVE_ACCOUNT] at
     * construction. All key selection reads this, so the hot-path bearer read stays keyed without an
     * async account lookup.
     */
    @Volatile
    private var activeAccountKey: String? = null

    private var tokenInitialized = false

    private fun loadCacheFromStorage() {
        activeAccountKey = readValue(KEY_ACTIVE_ACCOUNT)
        cachedAccessToken = readValue(accessKey(activeAccountKey))
        tokenInitialized = true
    }

    /**
     * Eagerly load the active account + its cached access token from platform storage.
     * Must be called from each platform subclass's `init {}` block (not from the super constructor,
     * which runs before subclass properties are initialised).
     */
    protected fun initializeTokenCache() = loadCacheFromStorage()

    private fun ensureTokenLoaded(): String? {
        if (!tokenInitialized) loadCacheFromStorage()
        return cachedAccessToken.nonBlankOrNull()
    }

    private fun String?.nonBlankOrNull(): String? = this?.takeUnless { it.isBlank() }

    // The bare key (null account) is the logged-out / legacy / mid-auth-staging fallback; a resolved
    // account namespaces to `acct:<id>:<base>`. One helper so both token bases scope identically.
    private fun scopedKey(account: String?, base: String): String =
        if (account == null) base else accountScopedName(account, base)

    private fun accessKey(account: String?): String = scopedKey(account, KEY_ACCESS_TOKEN)

    private fun refreshKey(account: String?): String = scopedKey(account, KEY_REFRESH_TOKEN)

    /** Guards the in-memory identity/cache + short storage reads/writes of it. Never held across a POST. */
    private val stateMutex = Mutex()

    /** Guards [flights] while a per-account single-flight lock is looked up / created. */
    private val flightMutex = Mutex()

    /** Per-account refresh single-flight locks, held across the refresh POST. Keyed by account (bare = ""). */
    private val flights = mutableMapOf<String, Mutex>()

    /**
     * Per-slot token epochs, keyed like [flights] (bare = [BARE_FLIGHT_KEY]). A slot's epoch is bumped
     * whenever ITS token truth changes (teardown, removal, an authentication's staging, an identity
     * re-home, an invalidate). A refresh captures its own slot's epoch before the POST and discards its
     * result if it changed, so a refresh racing a teardown can't resurrect a cleared session — while
     * another slot's changes (e.g. an add-account login staging B mid-flight of A's refresh, whose
     * server has already rotated A's refresh token) leave it untouched. A global epoch here would
     * discard A's rotated pair and strand A's slot on the spent pre-rotation token. A plain
     * [selectAccount] switch bumps nothing — an in-flight refresh of the outgoing account stays valid
     * because that account's tokens are retained. Guarded by [stateMutex].
     */
    private val tokenEpochs = mutableMapOf<String, Int>()

    private fun epochOf(account: String?): Int = tokenEpochs[account ?: BARE_FLIGHT_KEY] ?: 0

    private fun bumpEpoch(account: String?) {
        val key = account ?: BARE_FLIGHT_KEY
        tokenEpochs[key] = (tokenEpochs[key] ?: 0) + 1
    }

    private val _sessionExpired = MutableSharedFlow<SessionEndReason>(extraBufferCapacity = 1)
    override val sessionExpiredFlow: SharedFlow<SessionEndReason> = _sessionExpired.asSharedFlow()

    @Volatile
    private var sessionExpiryReported = false

    /** One-entry cache for [memoizedExpiryOf], so the per-request decode is a string comparison. */
    @Volatile
    private var expiryMemo: ExpiryMemo? = null

    /**
     * When each slot may next attempt a proactive renewal ([Long.MAX_VALUE] = never again), keyed like
     * [flights] (bare = [BARE_FLIGHT_KEY]). Reactive 401 renewal is never suppressed.
     *
     * Per slot rather than process-wide: both conditions that suppress it — a refresh endpoint that
     * cannot answer, and a deployment issuing tokens already inside the skew window — are properties
     * of one server, and one account's bad deployment must not silently turn the feature off for
     * another account on a healthy one. Immutable map behind a single [Volatile] so the per-request
     * read stays lock-free.
     */
    @Volatile
    private var proactiveSuppressedUntil: Map<String, Long> = emptyMap()

    // Platform implementations provide a plain synchronously-readable key/value secure store.
    protected abstract fun readValue(key: String): String?
    protected abstract fun writeValue(key: String, value: String)

    /** Persist several keys in one commit, so an access+refresh pair is written all-or-nothing. */
    protected abstract fun writeValues(values: Map<String, String>)
    protected abstract fun removeValue(key: String)
    protected abstract fun onKeystoreCorruption()

    // --- TokenManager ---

    override val isAuthenticated: Boolean
        get() = ensureTokenLoaded() != null

    override suspend fun getAccessToken(): String? = ensureTokenLoaded()

    override suspend fun setTokens(accessToken: String, refreshToken: String) = stateMutex.withLock {
        // Authentication path only (login / OAuth / 2FA). Stage the freshly-issued pair under the bare
        // keys and drop the active binding, so a re-login while another account is active never writes
        // into that account's keyed slot. onAccountResolved re-homes the staged pair once identity is
        // known. Steady-state rotation does NOT come here — refresh writes the keyed slot directly.
        // Only the bare (staging) slot's truth changes; keyed slots (and their in-flight refreshes,
        // which write their own slot) stay valid.
        bumpEpoch(null)
        resetSessionExpiryLatch()
        cachedAccessToken = accessToken
        activeAccountKey = null
        // Drop the persisted mirror BEFORE staging, so a crash in the staging window (before
        // onAccountResolved re-homes) can never cold-start back into the previously-active account —
        // the mirror must never outlive the in-memory identity. A crash after this remove but before
        // the stage write just cold-starts logged-out (safe), never as the wrong account.
        removeValue(KEY_ACTIVE_ACCOUNT)
        // One commit so a crash never persists a new access token with a stale/absent refresh token.
        writeValues(mapOf(KEY_ACCESS_TOKEN to accessToken, KEY_REFRESH_TOKEN to refreshToken))
    }

    override suspend fun onAccountResolved(accountId: String) = stateMutex.withLock {
        if (activeAccountKey == accountId) return@withLock
        // Re-home the staged (bare-key) authentication tokens into this account's keyed slot. A fresh
        // sign-in is the account's new truth, so overwrite. If none are staged (a pure cold-start
        // restore of already-keyed tokens whose mirror diverged), leave the keyed slot intact.
        // Crash-safe ordering: write the keyed slot, THEN the mirror, and only then drop the staging
        // keys — an interruption before the mirror write re-stages on the next resolve.
        val stagedAccess = readValue(KEY_ACCESS_TOKEN).nonBlankOrNull()
        val stagedRefresh = readValue(KEY_REFRESH_TOKEN)
        val rehomed = stagedAccess != null && stagedRefresh != null
        if (rehomed) {
            // Both the keyed slot (overwritten with the new truth) and the bare slot (staging
            // consumed) change; a no-staging mirror re-home changes neither, so nothing is bumped.
            bumpEpoch(accountId)
            bumpEpoch(null)
            writeValues(mapOf(accessKey(accountId) to stagedAccess, refreshKey(accountId) to stagedRefresh))
        }
        writeValue(KEY_ACTIVE_ACCOUNT, accountId)
        activeAccountKey = accountId
        // De-fanged: remove ONLY the bare staging keys, never another account's keyed slot — a
        // previously-active account's tokens are retained for a later switch back.
        removeValue(KEY_ACCESS_TOKEN)
        removeValue(KEY_REFRESH_TOKEN)
        // Trust the staged access in-memory only when we actually persisted it (rehomed); otherwise
        // (torn/absent staging, e.g. a partial keychain write) read the keyed slot so the cached
        // bearer can never diverge from storage across a cold start.
        cachedAccessToken = if (rehomed) stagedAccess else readValue(accessKey(accountId))
    }

    /**
     * Full teardown of the active session: drop its keyed tokens, purge any leftover bare staging
     * pair (an abandoned/killed login), clear the mirror + in-memory identity, and bump the epoch so
     * an in-flight refresh discards its result. Backs [clearTokens] (and thus its logout-named alias
     * [TokenManager.onAccountCleared], which delegates to it). Caller holds [stateMutex].
     */
    private fun tearDownActiveSessionLocked() {
        resetSessionExpiryLatch()
        bumpEpoch(activeAccountKey)
        // The bare staging slot is purged below too (when the active slot is keyed) — bump it so an
        // in-flight bare refresh can't resurrect the staged pair.
        if (activeAccountKey != null) bumpEpoch(null)
        removeValue(accessKey(activeAccountKey))
        removeValue(refreshKey(activeAccountKey))
        if (activeAccountKey != null) {
            // The active slot is keyed, so the bare keys can only hold a stale staged pair — purge it
            // so a killed login can't be cold-started back into an apparently-authenticated state.
            removeValue(KEY_ACCESS_TOKEN)
            removeValue(KEY_REFRESH_TOKEN)
        }
        removeValue(KEY_ACTIVE_ACCOUNT)
        activeAccountKey = null
        cachedAccessToken = null
    }

    override suspend fun getAccessTokenFor(accountId: String): String? = stateMutex.withLock {
        // Serve the active account from the in-memory cache (the per-request hot path via the switch
        // barrier's keyed bearer read); fall to storage only for a non-active account's slot. Under
        // stateMutex so the account check and the cache read can't interleave with a select/teardown.
        val token = if (accountId == activeAccountKey) cachedAccessToken else readValue(accessKey(accountId))
        token.nonBlankOrNull()
    }

    override suspend fun getStagedAccessToken(): String? = stateMutex.withLock {
        readValue(KEY_ACCESS_TOKEN).nonBlankOrNull()
    }

    override suspend fun clearStagedTokens() = stateMutex.withLock {
        // Bare keys only — no keyed slot, no mirror, no epoch bump. Not bumping the epoch is
        // deliberate: an in-flight refresh of a *keyed* account (the active one, mid-add) is
        // legitimate and must not discard its result; nothing refreshes the bare slot while an
        // account binding exists (pending add-flow 401s never trigger a refresh).
        removeValue(KEY_ACCESS_TOKEN)
        removeValue(KEY_REFRESH_TOKEN)
    }

    override suspend fun selectAccount(accountId: String) = stateMutex.withLock {
        if (activeAccountKey == accountId) return@withLock
        // Non-destructive switch: repoint the active binding + mirror + cached bearer to an already-keyed
        // account. Writes no token slot and deletes none; bumps no epoch (an in-flight refresh
        // of the outgoing account stays valid — its request keeps flowing to that account under the
        // switch barrier).
        writeValue(KEY_ACTIVE_ACCOUNT, accountId)
        activeAccountKey = accountId
        cachedAccessToken = readValue(accessKey(accountId))
        resetSessionExpiryLatch()
    }

    override suspend fun removeAccount(accountId: String) = stateMutex.withLock {
        resetSessionExpiryLatch()
        bumpEpoch(accountId)
        removeValue(accessKey(accountId))
        removeValue(refreshKey(accountId))
        // Evict the account's single-flight lock so [flights] doesn't retain a Mutex per removed
        // account. Safe: removeAccount bumps the epoch, so any in-flight refresh of this account
        // discards its result on commit regardless.
        flightMutex.withLock { flights.remove(accountId) }
        if (activeAccountKey == accountId) {
            removeValue(KEY_ACTIVE_ACCOUNT)
            activeAccountKey = null
            cachedAccessToken = null
        }
    }

    override suspend fun refreshAccessToken(usedAccessToken: String?): RefreshResult =
        // The live active account, against the live base URL (the refresh client's defaultRequest).
        performRefresh(
            accountKey = activeAccountKey,
            absoluteRefreshUrl = null,
            pinnedBaseUrl = null,
            usedAccessToken = usedAccessToken,
        )

    override suspend fun refreshAccessTokenFor(
        accountId: String,
        baseUrl: String,
        usedAccessToken: String?,
    ): RefreshResult =
        // URL-pinned: post to an absolute URL so a concurrent server switch can't redirect this
        // account's refresh token to another server. [pinnedBaseUrl] carries the *server* half of that
        // pin to ServerHeadersPlugin — the request URL alone can't serve as the key, because it is the
        // refresh endpoint, not the deployment root, and would derive a different serverId.
        performRefresh(
            accountKey = accountId,
            absoluteRefreshUrl = "${baseUrl.trimTrailingSlash()}$REFRESH_PATH",
            pinnedBaseUrl = baseUrl.trimTrailingSlash(),
            usedAccessToken = usedAccessToken,
        )

    override suspend fun ensureFreshAccessToken(
        accountId: String?,
        baseUrl: String,
        currentAccessToken: String?,
    ): String? {
        // Everything before the refresh is pure computation on values the caller already holds: no
        // storage read, no mutex. That matters because this runs on EVERY request the barrier builds,
        // on the caller's dispatcher, which for several cold-start repositories is main (issue #326).
        if (currentAccessToken == null) return currentAccessToken
        if (!isNearingExpiry(currentAccessToken)) return currentAccessToken
        // Resolve the slot exactly as [performRefresh] will, so suppression is tracked against the
        // same key the refresh actually targets rather than a bare-key approximation of it.
        val accountKey = accountId ?: activeAccountKey
        val slot = accountKey ?: BARE_FLIGHT_KEY
        if (Clock.System.now().toEpochMilliseconds() < (proactiveSuppressedUntil[slot] ?: 0L)) {
            return currentAccessToken
        }
        Diag.d(
            "Auth",
            origin = LogOrigin.CLIENT,
            attrs = mapOf("event" to "refresh_proactive"),
        ) { "Access token is at or past its expiry; renewing before the request goes out" }
        // Pin the POST to the URL the caller snapshotted, on EVERY branch — including the
        // no-account one, where the reactive [refreshAccessToken] posts to the *live* base URL and a
        // concurrent server switch can therefore redirect this slot's refresh token to another
        // deployment. Left unpinned only when the base URL is genuinely unknown, where an empty
        // `pinnedBaseUrl` would derive the wrong serverId and cost the gateway headers.
        val pinned = baseUrl.trimTrailingSlash().takeIf { it.isNotEmpty() }
        val result = performRefresh(
            accountKey = accountKey,
            absoluteRefreshUrl = pinned?.let { "$it$REFRESH_PATH" },
            pinnedBaseUrl = pinned,
            usedAccessToken = currentAccessToken,
            bestEffort = true,
        )
        if (result != RefreshResult.Refreshed) {
            // Negative caching, and NOT an optimization. A renewal that cannot succeed — offline, or
            // a 5xx-ing refresh endpoint — changes nothing for the coalescing check to see, so
            // without this every request in a cold-start fan-out queues behind the flight lock and
            // spends its own connect timeout BEFORE being sent, serially. Suppressed per slot; the
            // request still goes out on its old bearer and a real 401 still gets the full reactive
            // ladder.
            suppressProactiveRenewal(slot, Clock.System.now().toEpochMilliseconds() + PROACTIVE_RETRY_COOLDOWN_MS)
            return currentAccessToken
        }
        val renewed = (if (accountId != null) getAccessTokenFor(accountId) else getAccessToken())
        // A token that reads as stale the moment it is issued means the deadline and our clock
        // disagree — a device clock fast by more than the token's lifetime, or a deployment whose
        // SESSION_EXPIRY is shorter than the skew window. Left alone that is a refresh POST per
        // request, forever. One wasted refresh detects both causes. Suppressed per slot, not
        // process-wide: the cause is a property of one deployment, and a second account on a normal
        // server must keep the feature.
        if (renewed != null && isNearingExpiry(renewed)) {
            suppressProactiveRenewal(slot, Long.MAX_VALUE)
            Diag.w(
                "Auth",
                origin = LogOrigin.CLIENT,
                attrs = mapOf("event" to "refresh_proactive_disabled"),
            ) { "A freshly-issued access token already reads as expired; disabling proactive renewal" }
        }
        return renewed
    }

    /**
     * Copy-on-write so the hot-path read above needs no lock. A lost update under a race just costs a
     * redundant renewal attempt, never a wrong suppression.
     */
    private fun suppressProactiveRenewal(slot: String, untilEpochMillis: Long) {
        proactiveSuppressedUntil = proactiveSuppressedUntil + (slot to untilEpochMillis)
    }

    /**
     * True when [token] carries a deadline at or inside [PROACTIVE_RENEWAL_SKEW_MS]. An unreadable or
     * absent `exp` is false — unknown means leave it to the reactive path.
     */
    private fun isNearingExpiry(token: String): Boolean {
        val expiresAt = memoizedExpiryOf(token) ?: return false
        return expiresAt - Clock.System.now().toEpochMilliseconds() <= PROACTIVE_RENEWAL_SKEW_MS
    }

    /**
     * [expiresAtEpochMillisOrNull] for [token], reusing the last result when the string is unchanged.
     *
     * This runs at the request barrier, so without the memo every request base64-decodes and
     * JSON-parses the access token — including the overwhelmingly common case where it is fresh and
     * nothing happens — on the caller's dispatcher, which for several cold-start repositories is the
     * main thread (issue #326). One immutable entry swapped under a single [Volatile] write: a race
     * can only recompute, never publish a half-built memo or pair a token with another's deadline.
     */
    private fun memoizedExpiryOf(token: String): Long? {
        expiryMemo?.let { if (it.token == token) return it.expiresAtMillis }
        val computed = expiresAtEpochMillisOrNull(token)
        expiryMemo = ExpiryMemo(token, computed)
        return computed
    }

    private class ExpiryMemo(val token: String, val expiresAtMillis: Long?)

    /**
     * Refresh [accountKey]'s tokens with a bounded retry loop, classifying the outcome so a caller can
     * tell a hard-expired session (route to re-auth) from a transient failure (keep the session).
     *
     * The whole loop runs under the per-account flight lock: same-account refreshes stay single-flight
     * across every retry+backoff, so a queued 401 waits and then reads the freshly-rotated token
     * instead of re-POSTing a spent one. [stateMutex] is still taken only for the brief epoch read and
     * the commit — never across a POST or a backoff delay — so a switch/logout never stalls behind a
     * slow or retrying refresh.
     *
     * A refresh 401 is **not** treated as immediately terminal: the backend returns the same `401`
     * ("Refresh token expired or not found") for a transiently-missed session (replica lag / lookup
     * hiccup) as for a genuinely-expired one, and the app relaunch that "fixes" such a logout proves
     * the token was fine — so an auth rejection is retried with backoff. The budget is classified
     * [HardExpired] if **any** attempt saw an auth rejection (a dead session must route to re-auth even
     * if a later attempt hit a transient blip); a purely transient run (5xx / rate-limit) stays
     * [Transient] so the session is kept. A transport failure (server unreachable) is terminal-Transient
     * rather than retried, so the flight lock is not held across repeated full request timeouts.
     *
     * A settled [HardExpired] also **drops the account's token slot** — see [settle].
     *
     * [usedAccessToken] collapses a queued burst: the flight lock serializes same-account refreshes but
     * does not *coalesce* them, so N callers each POST in turn. A waiter that arrives holding the bearer
     * its request sent can compare it against the slot once it gets the lock — if the value has already
     * moved, the holder before it rotated on its behalf and there is nothing left to do.
     *
     * [bestEffort] is the proactive-renewal mode ([ensureFreshAccessToken]) and changes two things:
     * a **single** attempt instead of the retry ladder, and **never** dropping the slot — any failure
     * returns [Transient]. Both follow from the same fact: a proactive renewal nobody is waiting on
     * must not be able to log the user out. The ladder exists because the backend answers `401`
     * identically for a dead session and a transiently-missed one, so a single attempt cannot tell
     * them apart — which is exactly why a single attempt must not be allowed to settle the question.
     * The request proceeds on its old bearer and the reactive 401 path, with its full ladder, keeps
     * sole ownership of the logout decision.
     */
    private suspend fun performRefresh(
        accountKey: String?,
        absoluteRefreshUrl: String?,
        pinnedBaseUrl: String?,
        usedAccessToken: String? = null,
        bestEffort: Boolean = false,
    ): RefreshResult =
        flightFor(accountKey).withLock {
            // Coalesce: another caller rotated this slot while we waited for the flight lock, so its
            // POST already did our work. Only a *changed* value counts — an absent one means a teardown
            // or a hard-expiry dropped the slot, which must still fall through to the loop below and
            // settle as HardExpired rather than reporting a refresh that never happened.
            //
            // Read outside [stateMutex], like the stored-refresh-token read below: the failure mode is
            // one-directional. A stale read can only miss a rotation, which costs a redundant POST —
            // never the reverse.
            if (usedAccessToken != null) {
                val current = readValue(accessKey(accountKey)).nonBlankOrNull()
                if (current != null && current != usedAccessToken) {
                    Diag.d(
                        "Auth",
                        origin = LogOrigin.CLIENT,
                        attrs = mapOf("event" to "refresh_coalesced"),
                    ) { "Token already rotated by a concurrent refresh; skipping this POST" }
                    return@withLock RefreshResult.Refreshed
                }
            }
            // Any auth rejection ⇒ a genuinely dead session ⇒ route to re-auth, even if a later attempt
            // hit a transient blip; a purely transient run (5xx / rate-limit / transport) keeps the
            // session. This fold is the terminal classification for every non-Success exit of the loop.
            var sawHardRejection = false
            // The epoch the most recent attempt captured. [settle] drops the slot against it, so a
            // teardown that landed while the last POST was in flight still wins.
            var lastEpoch = NO_EPOCH
            suspend fun settle(): RefreshResult =
                if (sawHardRejection && !bestEffort) {
                    // The session is dead, so drop the slot. `isLoggedIn()` is a token-PRESENCE check:
                    // a retained dead pair is replayed on every later cold start.
                    invalidateRefresh(accountKey, lastEpoch)
                    Diag.w(
                        "Auth",
                        origin = LogOrigin.CLIENT,
                        attrs = mapOf("event" to "session_torn_down", "reason" to "refresh_rejected"),
                    ) { "Session expired - cleared the account's tokens" }
                    RefreshResult.HardExpired
                } else {
                    RefreshResult.Transient
                }
            repeat(if (bestEffort) 1 else MAX_REFRESH_ATTEMPTS) { attempt ->
                // Capture the slot's epoch BEFORE the stored-token read, so any teardown of THIS slot
                // that races the POST below is detected (and its result discarded) with no lock held
                // across the network call. Re-read each attempt: a teardown or a sibling that landed
                // between retries must be seen.
                val epochAtStart = stateMutex.withLock { epochOf(accountKey) }
                lastEpoch = epochAtStart
                val storedRefreshToken = readValue(refreshKey(accountKey))
                if (storedRefreshToken.isNullOrBlank()) {
                    // Attempt 0 with no token = no session at all → hard. A LATER attempt losing the
                    // token means a teardown (logout/removal) landed mid-loop and already owns the
                    // routing → Transient, so we don't double-emit session-expired over it. A
                    // best-effort renewal never makes that call at all — see [bestEffort].
                    return@withLock if (attempt == 0 && !bestEffort) {
                        Diag.w(
                            "Auth",
                            origin = LogOrigin.CLIENT,
                            attrs = mapOf("event" to "session_expired", "reason" to "no_refresh_token"),
                        ) { "No refresh token available" }
                        // Drop the slot here too, not just in [settle]. A torn pair (refresh gone,
                        // access retained) would otherwise keep `isLoggedIn()` true forever, which is
                        // the same replay this path exists to end. A no-op when the slot is empty.
                        invalidateRefresh(accountKey, epochAtStart)
                        RefreshResult.HardExpired
                    } else {
                        // A best-effort renewal against an empty slot, or a teardown that landed
                        // between retries. Logged so every renewal reconciles to an outcome: this is
                        // the one arm that can return without a POST, and untraced it makes
                        // `refresh_proactive` read as a POST counter when it isn't.
                        Diag.d(
                            "Auth",
                            origin = LogOrigin.CLIENT,
                            attrs = mapOf("event" to "refresh_skipped", "reason" to "no_refresh_token"),
                        ) { "No refresh token in this slot; skipping the refresh without a POST" }
                        RefreshResult.Transient
                    }
                }

                val delayMillis: Long =
                    when (
                        val outcome =
                            attemptRefresh(accountKey, epochAtStart, storedRefreshToken, absoluteRefreshUrl, pinnedBaseUrl)
                    ) {
                        RefreshAttempt.Success -> return@withLock RefreshResult.Refreshed
                        // A teardown/re-authentication owns the routing for this slot; never double-emit
                        // a session-expired from here and never re-persist over it.
                        RefreshAttempt.Discarded -> return@withLock RefreshResult.Transient
                        RefreshAttempt.KeystoreCleared -> return@withLock RefreshResult.HardExpired
                        // Server unreachable: retrying now only holds the flight lock across another full
                        // request timeout; stop. A session already confirmed dead by a prior 401 still
                        // routes to re-auth; otherwise keep the session (a later request/relaunch recovers).
                        RefreshAttempt.TransportError -> return@withLock settle()
                        // Never `settle()`: a gateway rejection says nothing about whether the session
                        // is alive, so it must not promote a prior 401 into a logout. See GatewayBlocked.
                        RefreshAttempt.GatewayBlocked -> return@withLock RefreshResult.Transient
                        RefreshAttempt.AuthRejected -> {
                            sawHardRejection = true
                            retryBackoffMillis(attempt)
                        }
                        RefreshAttempt.Retryable -> retryBackoffMillis(attempt)
                        is RefreshAttempt.RateLimited -> {
                            val wait = outcome.retryAfterMillis
                            // Honor the server's Retry-After when we can wait it out under the flight
                            // lock; if it exceeds our cap, stop retrying (keep the session) rather than
                            // re-POSTing while still rate-limited.
                            if (wait != null && wait > REFRESH_RETRY_MAX_DELAY_MS) {
                                return@withLock RefreshResult.Transient
                            }
                            wait ?: retryBackoffMillis(attempt)
                        }
                    }

                if (attempt < MAX_REFRESH_ATTEMPTS - 1) delay(delayMillis)
            }
            // Budget exhausted with no terminal outcome; classify by whether any attempt was rejected.
            settle()
        }

    /** One refresh POST + classification. Caller owns the flight lock and the retry/backoff loop. */
    private suspend fun attemptRefresh(
        accountKey: String?,
        epochAtStart: Int,
        storedRefreshToken: String,
        absoluteRefreshUrl: String?,
        pinnedBaseUrl: String?,
    ): RefreshAttempt =
        try {
            val httpResponse: HttpResponse = refreshClient.value
                .post(absoluteRefreshUrl ?: REFRESH_PATH) {
                    // Tells ServerHeadersPlugin which deployment's gateway headers this POST needs.
                    // Without it the plugin would fall back to the *live* base URL and pair server B's
                    // headers with server A's pinned refresh URL.
                    pinnedBaseUrl?.let { attributes.put(PinnedServerBaseUrlKey, it) }
                    header("Cookie", "refreshToken=$storedRefreshToken")
                    setBody(mapOf("refreshToken" to storedRefreshToken))
                }
            // The refresh client has no response validator, so a non-2xx returns here instead of
            // throwing — inspect the status directly rather than relying on a deserialization failure.
            val status = httpResponse.status.value
            when {
                status in 200..299 -> handleSuccess(accountKey, epochAtStart, httpResponse, storedRefreshToken)
                status == 401 || status == 403 -> {
                    Diag.w(
                        "Auth",
                        origin = LogOrigin.SERVER,
                        attrs = mapOf("event" to "refresh_rejected", "status" to status.toString()),
                    ) { "Auth rejected during token refresh" }
                    RefreshAttempt.AuthRejected
                }
                status == 429 -> {
                    val retryAfter = parseRetryAfterMillis(httpResponse)
                    Diag.w(
                        "Auth",
                        origin = LogOrigin.SERVER,
                        attrs = mapOf("event" to "refresh_rate_limited", "status" to "429"),
                    ) { "Refresh rate-limited" }
                    RefreshAttempt.RateLimited(retryAfter)
                }
                else -> {
                    Diag.w(
                        "Auth",
                        origin = LogOrigin.SERVER,
                        attrs = mapOf("event" to "refresh_transient", "status" to status.toString()),
                    ) { "Transient server error during token refresh" }
                    RefreshAttempt.Retryable
                }
            }
        } catch (e: AccessGatewayException) {
            // This POST never reached LibreChat and no retry will change that — stop spending the
            // budget under the flight lock.
            Diag.w(
                "Auth",
                origin = LogOrigin.NETWORK,
                throwable = e,
                attrs = mapOf("event" to "refresh_gateway_blocked"),
            ) { "Access gateway rejected the token refresh" }
            RefreshAttempt.GatewayBlocked
        } catch (e: Exception) {
            if (isKeystoreException(e)) {
                Diag.e(
                    "Auth",
                    origin = LogOrigin.CLIENT,
                    throwable = e,
                    attrs = mapOf("event" to "keystore_corruption"),
                ) { "Keystore corruption—clearing tokens" }
                onKeystoreCorruption()
                invalidateRefresh(accountKey, epochAtStart)
                RefreshAttempt.KeystoreCleared
            } else {
                // Transport failure (timeout / connection reset / DNS): the server is unreachable, so
                // immediately re-POSTing under the flight lock won't help. Keep the session (don't log
                // out) and let a later request / relaunch recover.
                Diag.w(
                    "Auth",
                    origin = LogOrigin.NETWORK,
                    throwable = e,
                    attrs = mapOf("event" to "refresh_transport_error"),
                ) { "Network error during token refresh" }
                RefreshAttempt.TransportError
            }
        }

    /** Parse a `Retry-After` header (delta-seconds form only) into millis; null when absent/unparseable. */
    private fun parseRetryAfterMillis(httpResponse: HttpResponse): Long? =
        httpResponse.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.let { it * 1000 }

    /** Parse + commit a 2xx refresh. A malformed body or a discarded commit is not a hard failure. */
    private suspend fun handleSuccess(
        accountKey: String?,
        epochAtStart: Int,
        httpResponse: HttpResponse,
        storedRefreshToken: String,
    ): RefreshAttempt {
        val body: RefreshResponse = try {
            httpResponse.body()
        } catch (e: Exception) {
            // A 2xx whose body isn't the expected JSON (e.g. an HTML interstitial from a proxy).
            // Recoverable, not a dead session.
            Diag.w(
                "Auth",
                origin = LogOrigin.SERVER,
                throwable = e,
                attrs = mapOf("event" to "refresh_malformed"),
            ) { "Refresh response body not parseable" }
            return RefreshAttempt.Retryable
        }
        // The backend rotates the refresh token on every successful refresh and returns the new one
        // via Set-Cookie; persist it. A 2xx without a rotated cookie means the server didn't rotate
        // (reuse path) — the stored token stays valid, so retain it rather than dropping the slot.
        val rotated = CookieHelper.extractRefreshToken(httpResponse.headers)
        if (rotated == null) {
            Diag.d("Auth", origin = LogOrigin.SERVER) { "Refresh 2xx without rotated cookie; retaining stored token" }
        }
        return if (commitRefresh(accountKey, epochAtStart, body.token, rotated ?: storedRefreshToken)) {
            RefreshAttempt.Success
        } else {
            RefreshAttempt.Discarded
        }
    }

    /** Equal-jitter exponential backoff between refresh retries, capped (half fixed + half random). */
    private fun retryBackoffMillis(attempt: Int): Long {
        val exp = (REFRESH_RETRY_BASE_DELAY_MS shl attempt).coerceAtMost(REFRESH_RETRY_MAX_DELAY_MS)
        return exp / 2 + Random.nextLong(exp / 2 + 1)
    }

    /** Persist a successful refresh — unless a teardown changed the epoch while the POST was in flight. */
    private suspend fun commitRefresh(
        accountKey: String?,
        epochAtStart: Int,
        access: String,
        refresh: String,
    ): Boolean = stateMutex.withLock {
        if (epochOf(accountKey) != epochAtStart) {
            // A logout / clear / removal / re-authentication of THIS slot landed mid-POST — discard so
            // the refresh can never resurrect a session that was torn down. Changes to other slots
            // (another account's login/teardown) don't invalidate this slot's rotated pair.
            Logger.d { "Token refresh discarded: session changed during refresh" }
            return@withLock false
        }
        writeValues(mapOf(accessKey(accountKey) to access, refreshKey(accountKey) to refresh))
        if (accountKey == activeAccountKey) cachedAccessToken = access
        Logger.d { "Token refreshed successfully" }
        true
    }

    /**
     * Drop [accountKey]'s slot after a hard auth failure — unless a teardown already owns the slot.
     *
     * Only the account's own keys go: the roster entry, the active-account mirror and
     * [activeAccountKey] all stay, so the account remains listed and re-loginable. Nulling the cached
     * bearer is what makes `isAuthenticated` (and through it `isLoggedIn()`) false.
     */
    private suspend fun invalidateRefresh(accountKey: String?, epochAtStart: Int) = stateMutex.withLock {
        if (epochOf(accountKey) != epochAtStart) return@withLock
        bumpEpoch(accountKey)
        removeValue(accessKey(accountKey))
        removeValue(refreshKey(accountKey))
        if (accountKey == activeAccountKey) cachedAccessToken = null
    }

    private suspend fun flightFor(accountKey: String?): Mutex = flightMutex.withLock {
        flights.getOrPut(accountKey ?: BARE_FLIGHT_KEY) { Mutex() }
    }

    override suspend fun clearTokens() = stateMutex.withLock { tearDownActiveSessionLocked() }

    override fun emitSessionExpired(expiredAccountId: String?, reason: SessionEndReason) {
        // Scoped emit: only the ACTIVE binding's expiry routes the app to re-auth. A straggler
        // failure for a switched-away (retained) account is not a live-session event — its slot is
        // just stale until that account is selected again. Null = active/legacy session: always emit.
        if (expiredAccountId != null && expiredAccountId != activeAccountKey) return
        // One signal per dead session. A cold start fans out ~a dozen requests, each of which 401s and
        // reaches here independently, spread far enough apart by the refresh retry/backoff that the
        // flow's 1-slot buffer coalesces nothing — and every emission replays the logout navigation.
        // A racing double-emit is possible (plain @Volatile, no CAS) and deliberately tolerated: the
        // navigator's own guard absorbs it.
        if (sessionExpiryReported) return
        // Latch on DELIVERY, not on the attempt. This flow has replay 0, so an emission with no
        // subscriber is discarded — and burning the one-shot on it would consume the report the next
        // request needs, stranding the user in a logged-out shell. Unattended emitters (background
        // prefetch runs in processes where no navigation host is composed) make that an ordinary case.
        if (_sessionExpired.subscriptionCount.value == 0) {
            Diag.d("Auth") { "session expiry discovered with no subscriber - leaving the signal armed" }
            return
        }
        sessionExpiryReported = true
        _sessionExpired.tryEmit(reason)
    }

    /**
     * Re-arm [emitSessionExpired] for a new session. Must run on every path that establishes or tears
     * one down — a latch left set silently swallows the NEXT session's expiry signal, including the
     * one `AccountSwitcher` reuses to route a remove-last-account teardown to auth.
     *
     * Deliberately **not** called from `onAccountResolved`: it is the re-home half of a sign-in
     * [setTokens] already covers, and it runs *after* the account gate opens — so re-arming there
     * would let the same dead session report itself twice mid-storm. [selectAccount] also runs on the
     * cold-start path but re-arms safely because its cold-start call happens inside the roster seed,
     * before the gate opens and therefore before any request can 401. Moving either call across that
     * gate reintroduces the double-report.
     */
    private fun resetSessionExpiryLatch() {
        sessionExpiryReported = false
    }

    // --- SecureTokenStorage ---

    override suspend fun getRefreshToken(): String? = readValue(refreshKey(activeAccountKey))

    override suspend fun storeTokens(accessToken: String, refreshToken: String) {
        setTokens(accessToken, refreshToken)
    }

    override suspend fun clearAll() {
        clearTokens()
    }

    protected open fun isKeystoreException(e: Exception): Boolean = false

    /** Classification of a single refresh POST, consumed by [performRefresh]'s retry loop. */
    private sealed interface RefreshAttempt {
        /** 2xx parsed + committed. Terminal → [RefreshResult.Refreshed]. */
        data object Success : RefreshAttempt

        /** A teardown/re-auth of this slot landed mid-POST; the commit was dropped. Terminal → Transient. */
        data object Discarded : RefreshAttempt

        /** Keystore corruption cleared the slot. Terminal → HardExpired. */
        data object KeystoreCleared : RefreshAttempt

        /** 401/403 — the session was rejected. Retried (may be a transient server false-negative). */
        data object AuthRejected : RefreshAttempt

        /** A received 5xx / malformed-2xx response. Retried with local backoff. */
        data object Retryable : RefreshAttempt

        /** 429 with the server's requested delay (ms), when present. Retried honoring it. */
        data class RateLimited(val retryAfterMillis: Long?) : RefreshAttempt

        /** Transport failure (server unreachable). Terminal → Transient, so we don't retry under the lock. */
        data object TransportError : RefreshAttempt

        /**
         * An access gateway intercepted the refresh (issue #287). Terminal → Transient.
         *
         * Deliberately **not** HardExpired: the request never reached LibreChat, so it is no evidence
         * the session is dead, and logging out over it costs a re-login on top of the header fix.
         * Nor retryable — every attempt in the budget meets the same gateway.
         *
         * Accepted cost: returning directly also discards a `sawHardRejection` from an earlier
         * attempt in the same loop, so a real 401 followed by a gateway block keeps a dead session.
         */
        data object GatewayBlocked : RefreshAttempt
    }

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACTIVE_ACCOUNT = "active_account_id"
        private const val REFRESH_PATH = "/api/auth/refresh"

        /** [flights] key for the bare (null-account) refresh path. */
        private const val BARE_FLIGHT_KEY = ""

        /** "No attempt has captured an epoch yet". Never equals a real epoch, so an
         *  [invalidateRefresh] against it is a no-op rather than an unguarded clear. */
        private const val NO_EPOCH = -1

        /**
         * How far ahead of an access token's `exp` a proactive renewal fires.
         *
         * **Must stay under 30 s.** On an OpenID deployment the refresh endpoint has a reuse path
         * (`OPENID_REUSE_EXPIRY_BUFFER_SECONDS = 30` in `AuthController.js`): while
         * `decoded.exp > now + 30` it answers 2xx with *the same token* and no rotation. A wider
         * window would fire inside that band, get the identical token back, and repeat on the next
         * request with nothing to coalesce against. Below 30 s the same call falls through to a
         * genuine renewal.
         *
         * Nothing is lost by keeping it small: this only governs how far *ahead* of expiry we
         * preempt, and the case the feature exists for — a cold start after idle — has an `exp`
         * already minutes in the past, which any positive threshold catches.
         */
        private const val PROACTIVE_RENEWAL_SKEW_MS = 20_000L

        /**
         * How long a slot stops attempting proactive renewal after one fails. Long enough that a
         * cold-start fan-out spends at most one failed attempt rather than one per request, and
         * costless when wrong: the request goes out on its old bearer and a real 401 still gets the
         * full reactive ladder immediately.
         */
        private const val PROACTIVE_RETRY_COOLDOWN_MS = 60_000L

        /** Total refresh attempts (initial + retries) before a persistent failure is classified. */
        private const val MAX_REFRESH_ATTEMPTS = 3
        private const val REFRESH_RETRY_BASE_DELAY_MS = 300L
        private const val REFRESH_RETRY_MAX_DELAY_MS = 2_000L
    }
}
