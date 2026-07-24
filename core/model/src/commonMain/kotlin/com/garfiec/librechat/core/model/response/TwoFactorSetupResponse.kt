package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

/**
 * Response of `POST /api/auth/2fa/enable` — the initial enrolment step that returns the TOTP
 * provisioning URI (for the QR code) and the freshly generated backup codes.
 *
 * The backend sends camelCase `{ otpauthUrl, backupCodes }`
 * (`upstream/api/server/controllers/TwoFactorController.js` `enable2FA`); the Kotlin property names
 * match the wire keys, so no `@SerialName` is needed. This response shape is unique to `enable` —
 * `confirm` returns an empty body and `regenerate` returns [BackupCodesResponse].
 */
@Serializable
data class TwoFactorSetupResponse(
    val otpauthUrl: String,
    val backupCodes: List<String>,
)
