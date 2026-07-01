package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.LoginOutcome
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.response.TwoFactorSetupResponse
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.TokenManager

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val tokenManager: TokenManager,
    private val sessionCacheCleaner: SessionCacheCleaner,
    private val sessionTaskRunner: SessionTaskRunner,
    private val accountSessionEstablisher: AccountSessionEstablisher,
    private val accountRegistry: AccountRegistry,
    private val activeAccountProvider: ActiveAccountProvider,
    private val sessionManager: SessionManager,
    private val accountDataPurger: AccountDataPurger,
) : AuthRepository {

    /**
     * Fires session tasks when [this] represents an authenticated success. Callers pass
     * [isAuthenticated] to discriminate: for OAuth/2FA any `Result.Success` is a real
     * sign-in, but `login()`'s `LoginOutcome.TwoFactorRequired` is a pending state (user
     * isn't authenticated yet) so tasks defer to `verifyTwoFactor()`.
     */
    private fun <T> Result<T>.fireSessionTasksIfLoggedIn(isAuthenticated: (T) -> Boolean = { true }): Result<T> =
        also { if (it is Result.Success && isAuthenticated(it.data)) sessionTaskRunner.runAll() }

    override suspend fun login(email: String, password: String): Result<LoginOutcome> {
        return safeApiCall {
            val result = authApi.login(email, password)
            if (result.response.twoFactorRequired && result.response.tempToken != null) {
                LoginOutcome.TwoFactorRequired(
                    result.response.tempToken
                        ?: throw IllegalStateException("Server indicated 2FA required but tempToken was null"),
                )
            } else {
                tokenManager.setTokens(
                    accessToken = result.response.token ?: "",
                    refreshToken = result.refreshToken ?: "",
                )
                val user = result.response.user
                    ?: throw IllegalStateException("Login succeeded but user was null in response")
                // Establish identity (+ run the legacy claim) before session tasks fire, so their
                // tenant writes are stamped to this account and reads filter to it.
                accountSessionEstablisher.establish(user)
                LoginOutcome.Success(user)
            }
        }.fireSessionTasksIfLoggedIn { outcome -> outcome is LoginOutcome.Success }
    }

    override suspend fun loginWithOAuthToken(refreshToken: String): Result<User> {
        return safeApiCall {
            // Use the refresh token to get an access token from the server
            val result = authApi.refresh(refreshToken)
            // If the backend rotated the refresh token, use the new one;
            // otherwise keep the original OAuth-provided token.
            val effectiveRefreshToken = result.newRefreshToken ?: refreshToken
            tokenManager.setTokens(
                accessToken = result.response.token,
                refreshToken = effectiveRefreshToken,
            )
            // Fetch the user profile, then establish identity before session tasks fire.
            val user = userApi.getUser()
            accountSessionEstablisher.establish(user)
            user
        }.fireSessionTasksIfLoggedIn()
    }

    override suspend fun verifyTwoFactor(tempToken: String, code: String): Result<User> {
        return safeApiCall {
            val result = authApi.verifyTempToken(tempToken = tempToken, totpCode = code)
            tokenManager.setTokens(
                accessToken = result.response.token ?: "",
                refreshToken = result.refreshToken ?: "",
            )
            val user = result.response.user ?: throw IllegalStateException("No user in 2FA response")
            accountSessionEstablisher.establish(user)
            user
        }.fireSessionTasksIfLoggedIn()
    }

    override suspend fun register(
        name: String,
        email: String,
        username: String,
        password: String,
    ): Result<Unit> {
        return safeApiCall {
            authApi.register(name, email, username, password, password)
        }
    }

    override suspend fun logout(): Result<Unit> {
        return safeApiCall {
            // Let the cold-start seed resolve identity first: a logout during the warming window
            // would otherwise read a null account and skip the scoped Room purge below (the leak).
            accountRegistry.awaitSeeded()
            // Capture the account to purge before any teardown flips identity to null.
            val account = activeAccountProvider.currentAccountId()
            try {
                authApi.logout()
            } finally {
                // Logout drives teardown — end the account session before its rows are deleted.
                // NOTE: tenant writes are not yet bound to Session.scope (the structural SessionWriter
                // is deferred), so this does not by itself fence a debounced/in-flight write against the
                // purge below. Mitigated per-path instead: the draft debounce re-checks identity before
                // it lands and drops on a flip (DraftRepositoryImpl), and the conversation/message cache
                // writes capture+guard the account at request time. The remaining narrow
                // streaming-write-after-purge window is the SessionWriter's job.
                sessionManager.endCurrentSession()
                // Flip identity to null first (read collectors tear down their account-scoped
                // queries), purge the active account from the registry (disk + in-memory), then
                // scoped-DELETE its tenant rows — the leak fix: logout finally clears Room, not just
                // file caches/tokens.
                accountRegistry.clearActiveAccount()
                account?.let { accountDataPurger.purge(it) }
                // Clears the active account's keyed tokens AND drops the persisted mirror (vs
                // clearTokens, the refresh-failure path, which leaves the account pointer intact).
                tokenManager.onAccountCleared()
                sessionCacheCleaner.clearSessionCaches()
            }
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        return tokenManager.getAccessToken() != null
    }

    override suspend fun restoreAccountIfNeeded(): Boolean {
        if (!isLoggedIn()) return true
        // Let the registry's cold-start seed finish, then act only if it found no persisted account.
        accountRegistry.awaitSeeded()
        val state = activeAccountProvider.state.value
        if (state is AccountState.Resolved && state.id != null) return true
        // Logged in but unaccounted → fresh upgrade. Derive identity from the current user. Wrap the
        // live getUser() so a transient/offline failure returns false (retry-worthy) instead of throwing
        // and leaving the provider stuck at Resolved(null) — which would render every tenant read empty
        // for an otherwise logged-in user until a manual relaunch while online.
        val result = safeApiCall {
            val user = userApi.getUser()
            accountSessionEstablisher.establish(user)
        }
        return result is Result.Success
    }

    override suspend fun enableTwoFactor(token: String?, backupCode: String?): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.enableTwoFactor(token = token, backupCode = backupCode)
        }
    }

    override suspend fun confirmTwoFactor(code: String): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.confirmTwoFactor(code)
        }
    }

    override suspend fun disableTwoFactor(code: String): Result<Unit> {
        return safeApiCall {
            authApi.disableTwoFactor(code)
        }
    }

    override suspend fun regenerateBackupCodes(token: String?, backupCode: String?): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.regenerateBackupCodes(token = token, backupCode = backupCode)
        }
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> {
        return safeApiCall {
            authApi.requestPasswordReset(email)
        }
    }

    override suspend fun resetPassword(
        userId: String,
        token: String,
        password: String,
        confirmPassword: String,
    ): Result<Unit> {
        return safeApiCall {
            authApi.resetPassword(userId, token, password, confirmPassword)
        }
    }
}
