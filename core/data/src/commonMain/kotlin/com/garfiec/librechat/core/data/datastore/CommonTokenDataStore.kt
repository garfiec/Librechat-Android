package com.garfiec.librechat.core.data.datastore

import co.touchlab.kermit.Logger
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
 * Account-keyed secure token store. Tokens are namespaced by the active account
 * (`acct:<accountId>:access_token`) so one account's cached credentials are never **read** under
 * another — the precursor for holding several accounts' sessions at once (multi-account, issue #179).
 * Under the current single-active model only one account's tokens are retained at a time: a direct
 * account switch re-homes the newest sign-in into its keyed slot and drops the previous one (full
 * multi-account retention is a later step).
 *
 * The active account is mirrored into the same synchronously-readable secure storage (key
 * [KEY_ACTIVE_ACCOUNT]) rather than the async account registry, so the bearer for the right account
 * is seeded in the constructor — the first-frame `isAuthenticated` check and the first authed request
 * never read a blind bearer at cold start. When no account is resolved (fresh install, logged out, or
 * a legacy pre-keying upgrade) the keys fall back to their bare form, which is also how tokens from an
 * older build are read until [onAccountResolved] re-homes them.
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

    private fun accessKey(account: String?): String =
        if (account == null) KEY_ACCESS_TOKEN else accountScopedName(account, KEY_ACCESS_TOKEN)

    private fun refreshKey(account: String?): String =
        if (account == null) KEY_REFRESH_TOKEN else accountScopedName(account, KEY_REFRESH_TOKEN)

    private val refreshMutex = Mutex()

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

    override suspend fun setTokens(accessToken: String, refreshToken: String) {
        cachedAccessToken = accessToken
        // One commit so a crash never persists a new access token with a stale/absent refresh token.
        writeValues(
            mapOf(
                accessKey(activeAccountKey) to accessToken,
                refreshKey(activeAccountKey) to refreshToken,
            ),
        )
    }

    override suspend fun onAccountResolved(accountId: String) = refreshMutex.withLock {
        if (activeAccountKey == accountId) return@withLock
        // Identity just became known (fresh login, upgrade, or a re-login). The tokens written by the
        // preceding setTokens live under the previous effective key — the bare keys on a legacy/first
        // install, or the prior account on a switch. Re-home them into this account's keyed slot (only
        // when empty, so we never clobber existing keyed tokens), drop the old slot, and repoint the
        // mirror so the next cold start seeds this account.
        val previous = activeAccountKey
        val access = readValue(accessKey(previous))
        val refresh = readValue(refreshKey(previous))
        // Crash-safe ordering: write the new keyed slot (atomically, only when empty so existing keyed
        // tokens are never clobbered), THEN repoint the mirror, and only after that remove the old slot.
        // An interruption before the mirror write leaves the old slot intact (seeds `previous`, retried
        // next resolve); after it, the tokens are already safe under `accountId`.
        if (access != null && refresh != null && readValue(accessKey(accountId)) == null) {
            writeValues(mapOf(accessKey(accountId) to access, refreshKey(accountId) to refresh))
        }
        writeValue(KEY_ACTIVE_ACCOUNT, accountId)
        activeAccountKey = accountId
        removeValue(accessKey(previous))
        removeValue(refreshKey(previous))
        cachedAccessToken = readValue(accessKey(accountId))
    }

    override suspend fun onAccountCleared() = refreshMutex.withLock {
        removeValue(accessKey(activeAccountKey))
        removeValue(refreshKey(activeAccountKey))
        removeValue(KEY_ACTIVE_ACCOUNT)
        activeAccountKey = null
        cachedAccessToken = null
    }

    override suspend fun refreshAccessToken(): Boolean = refreshMutex.withLock {
        val storedRefreshToken = readValue(refreshKey(activeAccountKey))
        if (storedRefreshToken.isNullOrBlank()) {
            Diag.w(
                "Auth",
                origin = LogOrigin.CLIENT,
                attrs = mapOf("event" to "session_expired", "reason" to "no_refresh_token"),
            ) { "No refresh token available" }
            return false
        }

        return try {
            val httpResponse: HttpResponse = refreshClient.value
                .post("/api/auth/refresh") {
                    header("Cookie", "refreshToken=$storedRefreshToken")
                    setBody(mapOf("refreshToken" to storedRefreshToken))
                }

            val body: RefreshResponse = httpResponse.body()
            val newRefreshToken = CookieHelper.extractRefreshToken(httpResponse.headers)
                ?: storedRefreshToken

            setTokens(body.token, newRefreshToken)
            Logger.d { "Token refreshed successfully" }
            true
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
            clearTokensLocked()
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
                clearTokensLocked()
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

    override suspend fun clearTokens() {
        refreshMutex.withLock {
            clearTokensLocked()
        }
    }

    private fun clearTokensLocked() {
        cachedAccessToken = null
        removeValue(accessKey(activeAccountKey))
        removeValue(refreshKey(activeAccountKey))
    }

    override fun emitSessionExpired() {
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
    }
}
