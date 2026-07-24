package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.request.LoginRequest
import com.garfiec.librechat.core.model.request.OtpVerificationRequest
import com.garfiec.librechat.core.model.request.PasswordResetRequest
import com.garfiec.librechat.core.model.request.RegisterRequest
import com.garfiec.librechat.core.model.request.ResetPasswordRequest
import com.garfiec.librechat.core.model.request.TwoFactorDisableRequest
import com.garfiec.librechat.core.model.request.TwoFactorVerifyRequest
import com.garfiec.librechat.core.model.request.TwoFactorVerifyTempRequest
import com.garfiec.librechat.core.model.response.BackupCodesResponse
import com.garfiec.librechat.core.model.response.LoginResponse
import com.garfiec.librechat.core.model.response.RefreshResponse
import com.garfiec.librechat.core.model.response.RegisterResponse
import com.garfiec.librechat.core.model.response.TwoFactorSetupResponse
import com.garfiec.librechat.core.network.api.dto.LoginResult
import com.garfiec.librechat.core.network.api.dto.RefreshResult
import com.garfiec.librechat.core.network.api.dto.TwoFactorConfirmRequest
import com.garfiec.librechat.core.network.client.CookieHelper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.path

class AuthApi constructor(
    private val client: HttpClient,
) {
    suspend fun login(email: String, password: String): LoginResult {
        val httpResponse = client.post {
            url { path("api/auth/login") }
            setBody(LoginRequest(email = email, password = password))
        }
        val body = httpResponse.body<LoginResponse>()
        val refreshToken = CookieHelper.extractRefreshToken(httpResponse.headers)
        return LoginResult(body, refreshToken)
    }

    suspend fun register(
        name: String,
        email: String,
        username: String,
        password: String,
        confirmPassword: String,
    ): RegisterResponse =
        client.post {
            url { path("api/auth/register") }
            setBody(
                RegisterRequest(
                    name = name,
                    email = email,
                    username = username,
                    password = password,
                    confirmPassword = confirmPassword,
                ),
            )
        }.body()

    suspend fun logout() {
        client.post {
            url { path("api/auth/logout") }
        }
    }

    suspend fun refresh(refreshToken: String): RefreshResult {
        val httpResponse = client.post {
            url { path("api/auth/refresh") }
            // Send refresh token via both Cookie header and request body.
            // The backend reads from cookies first, falling back to body.
            header("Cookie", "refreshToken=$refreshToken")
            setBody(mapOf("refreshToken" to refreshToken))
        }
        val body = httpResponse.body<RefreshResponse>()
        val newRefreshToken = CookieHelper.extractRefreshToken(httpResponse.headers)
        return RefreshResult(body, newRefreshToken)
    }

    suspend fun requestPasswordReset(email: String) {
        client.post {
            url { path("api/auth/requestPasswordReset") }
            setBody(PasswordResetRequest(email = email))
        }
    }

    suspend fun resetPassword(userId: String, token: String, password: String, confirmPassword: String) {
        client.post {
            url { path("api/auth/resetPassword") }
            setBody(
                ResetPasswordRequest(
                    userId = userId,
                    token = token,
                    password = password,
                    confirmPassword = confirmPassword,
                ),
            )
        }
    }

    /**
     * Verify 2FA for an already-authenticated user.
     * POST /api/auth/2fa/verify with { token: totpCode }
     */
    suspend fun verifyTwoFactor(totpCode: String): LoginResult {
        val httpResponse = client.post {
            url { path("api/auth/2fa/verify") }
            setBody(TwoFactorVerifyRequest(token = totpCode))
        }
        val body = httpResponse.body<LoginResponse>()
        val refreshToken = CookieHelper.extractRefreshToken(httpResponse.headers)
        return LoginResult(body, refreshToken)
    }

    suspend fun enableTwoFactor(token: String? = null, backupCode: String? = null): TwoFactorSetupResponse =
        client.post {
            url { path("api/auth/2fa/enable") }
            if (token != null || backupCode != null) {
                setBody(OtpVerificationRequest(token = token, backupCode = backupCode))
            }
        }.body()

    // confirm2FA returns an empty 200 body (`res.status(200).json()`); there is nothing to decode.
    suspend fun confirmTwoFactor(code: String) {
        client.post {
            url { path("api/auth/2fa/confirm") }
            setBody(TwoFactorConfirmRequest(token = code))
        }
    }

    /**
     * POST /api/auth/2fa/verify-temp with { tempToken, token: totpCode } or { tempToken, backupCode }.
     */
    suspend fun verifyTempToken(
        tempToken: String,
        totpCode: String? = null,
        backupCode: String? = null,
    ): LoginResult {
        val httpResponse = client.post {
            url { path("api/auth/2fa/verify-temp") }
            setBody(
                TwoFactorVerifyTempRequest(
                    tempToken = tempToken,
                    token = totpCode,
                    backupCode = backupCode,
                ),
            )
        }
        val body = httpResponse.body<LoginResponse>()
        val refreshToken = CookieHelper.extractRefreshToken(httpResponse.headers)
        return LoginResult(body, refreshToken)
    }

    suspend fun regenerateBackupCodes(token: String? = null, backupCode: String? = null): BackupCodesResponse =
        client.post {
            url { path("api/auth/2fa/backup/regenerate") }
            if (token != null || backupCode != null) {
                setBody(OtpVerificationRequest(token = token, backupCode = backupCode))
            }
        }.body()

    suspend fun disableTwoFactor(code: String) {
        client.post {
            url { path("api/auth/2fa/disable") }
            setBody(TwoFactorDisableRequest(token = code))
        }
    }
}
