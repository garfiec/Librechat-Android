package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

/**
 * Response of `POST /api/auth/2fa/backup/regenerate` — the backend returns
 * `{ backupCodes, backupCodesHash }` (`upstream/api/server/controllers/TwoFactorController.js`
 * `regenerateBackupCodes`). Only the plain [backupCodes] are surfaced; the `backupCodesHash`
 * (server-side hashed objects) is unmodeled and dropped via `ignoreUnknownKeys`.
 *
 * This is deliberately distinct from [TwoFactorSetupResponse]: regenerate does NOT return an
 * `otpauthUrl`, so reusing the setup shape (which requires it) fails to decode.
 */
@Serializable
data class BackupCodesResponse(
    val backupCodes: List<String>,
)
