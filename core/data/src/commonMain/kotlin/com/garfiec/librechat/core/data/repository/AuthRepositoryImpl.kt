package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.LoginOutcome
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.response.TwoFactorSetupResponse
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.db.LibreChatDatabase
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.client.TokenManager

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val tokenManager: TokenManager,
    private val sessionCacheCleaner: SessionCacheCleaner,
    private val database: LibreChatDatabase,
    private val settingsDataStore: SettingsDataStore,
) : AuthRepository {

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
                LoginOutcome.Success(
                    result.response.user
                        ?: throw IllegalStateException("Login succeeded but user was null in response"),
                )
            }
        }
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
            // Fetch the user profile
            userApi.getUser()
        }
    }

    override suspend fun verifyTwoFactor(tempToken: String, code: String): Result<User> {
        return safeApiCall {
            val result = authApi.verifyTempToken(tempToken = tempToken, totpCode = code)
            tokenManager.setTokens(
                accessToken = result.response.token ?: "",
                refreshToken = result.refreshToken ?: "",
            )
            result.response.user ?: throw IllegalStateException("No user in 2FA response")
        }
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
            try {
                authApi.logout()
            } finally {
                tokenManager.clearTokens()
                sessionCacheCleaner.clearSessionCaches()
                database.clearAllUserData()
                settingsDataStore.clearUserData()
            }
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        return tokenManager.getAccessToken() != null
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
