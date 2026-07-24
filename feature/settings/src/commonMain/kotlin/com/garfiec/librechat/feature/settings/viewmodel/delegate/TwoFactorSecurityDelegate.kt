package com.garfiec.librechat.feature.settings.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.feature.settings.viewmodel.SettingsStateHandle
import com.garfiec.librechat.feature.settings.viewmodel.isHttpStatus
import kotlinx.coroutines.launch

/**
 * Handles 2FA setup, backup codes, and disable flow.
 */
class TwoFactorSecurityDelegate(
    private val stateHandle: SettingsStateHandle,
    private val authRepository: AuthRepository,
) {

    fun toggleTwoFactor() {
        if (stateHandle.state.isTwoFactorEnabled) {
            stateHandle.update { copy(showDisableTwoFactorDialog = true) }
        } else {
            enableTwoFactor()
        }
    }

    /**
     * Enable 2FA. When re-enrolling (2FA already enabled on server), OTP is required.
     */
    fun enableTwoFactor(token: String? = null, backupCode: String? = null) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isTwoFactorLoading = true) }
            when (val result = authRepository.enableTwoFactor(token = token, backupCode = backupCode)) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            showTwoFactorSetupDialog = true,
                            showEnableTwoFactorOtpDialog = false,
                            twoFactorOtpauthUrl = result.data.otpauthUrl,
                            backupCodes = result.data.backupCodes,
                        )
                    }
                }
                is Result.Error -> {
                    // Server returns 400 when 2FA is already enabled and OTP is required to re-enroll
                    val needsOtp = token == null && backupCode == null && result.isHttpStatus(400)
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            showEnableTwoFactorOtpDialog = if (needsOtp) true else showEnableTwoFactorOtpDialog,
                            error = if (needsOtp) null else (result.message ?: "Failed to enable two-factor authentication"),
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissEnableTwoFactorOtpDialog() {
        stateHandle.update { copy(showEnableTwoFactorOtpDialog = false) }
    }

    fun confirmEnableTwoFactor(code: String) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isTwoFactorLoading = true) }
            when (val result = authRepository.confirmTwoFactor(code)) {
                is Result.Success -> {
                    // confirm returns an empty body — the backup codes to display were already
                    // returned by enable and are held in state; keep them rather than re-reading.
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            isTwoFactorEnabled = true,
                            showTwoFactorSetupDialog = false,
                            twoFactorOtpauthUrl = null,
                            showBackupCodesDialog = true,
                        )
                    }
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            error = result.message ?: "Invalid verification code",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun confirmDisableTwoFactor(code: String) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isTwoFactorLoading = true) }
            when (val result = authRepository.disableTwoFactor(code)) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            isTwoFactorEnabled = false,
                            showDisableTwoFactorDialog = false,
                        )
                    }
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            error = result.message ?: "Failed to disable two-factor authentication",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissTwoFactorSetupDialog() {
        stateHandle.update { copy(showTwoFactorSetupDialog = false, twoFactorOtpauthUrl = null) }
    }

    fun dismissDisableTwoFactorDialog() {
        stateHandle.update { copy(showDisableTwoFactorDialog = false) }
    }

    fun dismissBackupCodesDialog() {
        stateHandle.update { copy(showBackupCodesDialog = false, backupCodes = emptyList()) }
    }

    /**
     * View/regenerate backup codes. Requires OTP when 2FA is enabled.
     */
    fun viewBackupCodes(token: String? = null, backupCode: String? = null) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isTwoFactorLoading = true) }
            when (val result = authRepository.regenerateBackupCodes(token = token, backupCode = backupCode)) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            showBackupCodesDialog = true,
                            showBackupCodesOtpDialog = false,
                            backupCodes = result.data.backupCodes,
                        )
                    }
                }
                is Result.Error -> {
                    // Server returns 400 when 2FA is enabled and OTP is required to regenerate codes
                    val needsOtp = token == null && backupCode == null && result.isHttpStatus(400)
                    stateHandle.update {
                        copy(
                            isTwoFactorLoading = false,
                            showBackupCodesOtpDialog = if (needsOtp) true else showBackupCodesOtpDialog,
                            error = if (needsOtp) null else (result.message ?: "Failed to retrieve backup codes"),
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissBackupCodesOtpDialog() {
        stateHandle.update { copy(showBackupCodesOtpDialog = false) }
    }
}
