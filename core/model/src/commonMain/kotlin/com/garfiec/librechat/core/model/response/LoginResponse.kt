package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.User
import kotlinx.serialization.Serializable

/**
 * Response body for POST /api/auth/login and the 2FA verify endpoints. The field is `twoFAPending`
 * on the wire (not `twoFactorRequired`).
 */
@Serializable
data class LoginResponse(
    val token: String? = null,
    val user: User? = null,
    val twoFAPending: Boolean = false,
    val tempToken: String? = null,
)
