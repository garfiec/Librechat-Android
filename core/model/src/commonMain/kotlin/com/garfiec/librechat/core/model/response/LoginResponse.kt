package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.User
import kotlinx.serialization.Serializable

/**
 * Response body for POST /api/auth/login and the 2FA verify endpoints.
 *
 * Mirrors upstream's `TLoginResponse` (packages/data-provider/src/types.ts). A password-valid login
 * against a 2FA-enabled account answers HTTP 200 with `{ twoFAPending: true, tempToken }` and no
 * user/token — the field is named `twoFAPending` on the wire, not `twoFactorRequired`; the latter
 * name never existed upstream and `ignoreUnknownKeys` silently dropped it (issue #277).
 */
@Serializable
data class LoginResponse(
    val token: String? = null,
    val user: User? = null,
    val twoFAPending: Boolean = false,
    val tempToken: String? = null,
)
