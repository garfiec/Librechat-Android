package com.garfiec.librechat.core.data.datastore

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.extensions.trimTrailingSlash
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import com.garfiec.librechat.core.model.response.RefreshResponse
import com.garfiec.librechat.core.network.client.CookieHelper
import com.garfiec.librechat.core.network.client.SecureTokenStorage
import com.garfiec.librechat.core.network.client.TokenManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

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
        return cachedAccessToken
    }

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

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val sessionExpiredFlow: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

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
        val stagedAccess = readValue(KEY_ACCESS_TOKEN)
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
        if (accountId == activeAccountKey) cachedAccessToken else readValue(accessKey(accountId))
    }

    override suspend fun getStagedAccessToken(): String? = stateMutex.withLock {
        readValue(KEY_ACCESS_TOKEN)
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
    }

    override suspend fun removeAccount(accountId: String) = stateMutex.withLock {
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

    override suspend fun refreshAccessToken(): Boolean =
        // The live active account, against the live base URL (the refresh client's defaultRequest).
        performRefresh(accountKey = activeAccountKey, absoluteRefreshUrl = null)

    override suspend fun refreshAccessTokenFor(accountId: String, baseUrl: String): Boolean =
        // URL-pinned: post to an absolute URL so a concurrent server switch can't redirect this
        // account's refresh token to another server.
        performRefresh(accountKey = accountId, absoluteRefreshUrl = "${baseUrl.trimTrailingSlash()}$REFRESH_PATH")

    private suspend fun performRefresh(accountKey: String?, absoluteRefreshUrl: String?): Boolean =
        flightFor(accountKey).withLock {
            // Capture the slot's epoch BEFORE the stored-token read, so any teardown of THIS slot that
            // races the POST below is detected (and its result discarded) with no lock held across the
            // network call. Under stateMutex only for the map read — released before the POST.
            val epochAtStart = stateMutex.withLock { epochOf(accountKey) }
            val storedRefreshToken = readValue(refreshKey(accountKey))
            if (storedRefreshToken.isNullOrBlank()) {
                Diag.w(
                    "Auth",
                    origin = LogOrigin.CLIENT,
                    attrs = mapOf("event" to "session_expired", "reason" to "no_refresh_token"),
                ) { "No refresh token available" }
                return@withLock false
            }

            try {
                val httpResponse: HttpResponse = refreshClient.value
                    .post(absoluteRefreshUrl ?: REFRESH_PATH) {
                        header("Cookie", "refreshToken=$storedRefreshToken")
                        setBody(mapOf("refreshToken" to storedRefreshToken))
                    }

                val body: RefreshResponse = httpResponse.body()
                val newRefreshToken = CookieHelper.extractRefreshToken(httpResponse.headers)
                    ?: storedRefreshToken

                commitRefresh(accountKey, epochAtStart, body.token, newRefreshToken)
            } catch (e: ClientRequestException) {
                Diag.w(
                    "Auth",
                    origin = LogOrigin.SERVER,
                    throwable = e,
                    attrs = mapOf(
                        "event" to "refresh_failed",
                        "status" to e.response.status.value.toString(),
                    ),
                ) { "Auth error during token refresh" }
                invalidateRefresh(accountKey, epochAtStart)
                false
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
                } else {
                    Diag.w(
                        "Auth",
                        origin = LogOrigin.NETWORK,
                        throwable = e,
                        attrs = mapOf("event" to "refresh_failed"),
                    ) { "Error during token refresh" }
                }
                false
            }
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

    /** Drop [accountKey]'s slot after a hard auth failure — unless a teardown already owns the slot. */
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

    override fun emitSessionExpired(expiredAccountId: String?) {
        // Scoped emit: only the ACTIVE binding's expiry routes the app to re-auth. A straggler
        // failure for a switched-away (retained) account is not a live-session event — its slot is
        // just stale until that account is selected again. Null = active/legacy session: always emit.
        if (expiredAccountId != null && expiredAccountId != activeAccountKey) return
        _sessionExpired.tryEmit(Unit)
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

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACTIVE_ACCOUNT = "active_account_id"
        private const val REFRESH_PATH = "/api/auth/refresh"

        /** [flights] key for the bare (null-account) refresh path. */
        private const val BARE_FLIGHT_KEY = ""
    }
}
