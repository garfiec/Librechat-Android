package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * Request body for POST /api/auth/2fa/verify.
 * Backend reads { token, backupCode } where token is the TOTP code.
 */
@Serializable
data class TwoFactorVerifyRequest(
    val token: String,
    val backupCode: String? = null,
)

/**
 * Request body for POST /api/auth/2fa/verify-temp.
 * Backend reads { tempToken, token, backupCode } where tempToken is the
 * temporary auth token from login and token is the TOTP code.
 */
@Serializable
data class TwoFactorVerifyTempRequest(
    val tempToken: String,
    val token: String? = null,
    val backupCode: String? = null,
)
