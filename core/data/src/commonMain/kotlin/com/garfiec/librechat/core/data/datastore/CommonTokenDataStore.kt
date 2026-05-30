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

abstract class CommonTokenDataStore(
    private val refreshClient: Lazy<HttpClient>,
) : TokenManager, SecureTokenStorage {

    @Volatile
    private var cachedAccessToken: String? = null

    private var tokenInitialized = false

    /**
     * Eagerly load tokens from platform storage into the in-memory cache.
     * Must be called from each platform subclass's `init {}` block (not from
     * the super constructor, which runs before subclass properties are initialised).
     */
    protected fun initializeTokenCache() {
        cachedAccessToken = readAccessToken()
        tokenInitialized = true
    }

    private fun ensureTokenLoaded(): String? {
        if (!tokenInitialized) {
            cachedAccessToken = readAccessToken()
            tokenInitialized = true
        }
        return cachedAccessToken
    }

    private val refreshMutex = Mutex()

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val sessionExpiredFlow: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    // Platform implementations provide these
    protected abstract fun readAccessToken(): String?
    protected abstract fun readRefreshToken(): String?
    protected abstract fun writeTokens(accessToken: String, refreshToken: String)
    protected abstract fun removeTokens()
    protected abstract fun onKeystoreCorruption()

    // --- TokenManager ---

    override val isAuthenticated: Boolean
        get() = ensureTokenLoaded() != null

    override suspend fun getAccessToken(): String? = ensureTokenLoaded()

    override suspend fun setTokens(accessToken: String, refreshToken: String) {
        cachedAccessToken = accessToken
        writeTokens(accessToken, refreshToken)
    }

    override suspend fun refreshAccessToken(): Boolean = refreshMutex.withLock {
        val storedRefreshToken = readRefreshToken()
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
        removeTokens()
    }

    override fun emitSessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }

    // --- SecureTokenStorage ---

    override suspend fun getRefreshToken(): String? = readRefreshToken()

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
    }
}
