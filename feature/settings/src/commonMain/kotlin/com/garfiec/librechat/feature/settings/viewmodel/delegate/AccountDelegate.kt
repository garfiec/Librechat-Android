package com.garfiec.librechat.feature.settings.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.BalanceRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.feature.settings.util.ContentReader
import com.garfiec.librechat.feature.settings.viewmodel.SettingsStateHandle
import com.garfiec.librechat.feature.settings.viewmodel.isHttpStatus
import com.garfiec.librechat.feature.settings.viewmodel.toDisplayData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account section: user-profile load, avatar upload, balance, sign-out, and
 * account deletion (with the 403 → OTP step-up). Holds the in-flight profile-load
 * job so a retry cancels a hung request before starting a fresh one.
 */
class AccountDelegate(
    private val stateHandle: SettingsStateHandle,
    private val userRepository: UserRepository,
    private val balanceRepository: BalanceRepository,
    private val contentReader: ContentReader,
    private val ioDispatcher: CoroutineDispatcher,
) {

    // In-flight profile-load job. Cancelled before each retry so a hung 90s request
    // doesn't continue racing the fresh one.
    private var loadUserJob: Job? = null

    fun loadUser() {
        loadUserJob?.cancel()
        loadUserJob = stateHandle.scope.launch {
            stateHandle.update { copy(profileLoadError = null) }
            when (val result = userRepository.getUser()) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            user = result.data.toDisplayData(),
                            profileLoadError = null,
                            isTwoFactorEnabled = result.data.twoFactorEnabled,
                            // Fail-CLOSED admin gate: the role-skills admin row shows
                            // only for the ADMIN system role (mirrors the server's
                            // manageRoles middleware). Server also 403s non-admins.
                            isAdmin = result.data.role == ADMIN_ROLE,
                        )
                    }
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            profileLoadError = result.message ?: "Failed to load user profile",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun retry() {
        loadUser()
    }

    fun loadBalance() {
        stateHandle.scope.launch {
            stateHandle.update { copy(isBalanceLoading = true) }
            when (val result = balanceRepository.getBalance()) {
                is Result.Success -> {
                    stateHandle.update { copy(tokenCredits = result.data.tokenCredits, isBalanceLoading = false) }
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load balance: ${result.message}" }
                    stateHandle.update { copy(isBalanceLoading = false) }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    // ── Avatar ─────────────────────────────────────────────────────

    fun showAvatarDialog() {
        stateHandle.update { copy(showAvatarDialog = true) }
    }

    fun dismissAvatarDialog() {
        stateHandle.update { copy(showAvatarDialog = false) }
    }

    fun uploadAvatar(uri: Any) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isAvatarUploading = true) }
            // Reading bytes off the URI is blocking I/O — keep it off the Main
            // dispatcher (viewModelScope = Main.immediate) to avoid an ANR on
            // large images. Mirrors the FileAttachmentDelegate fix.
            val bytes = try {
                withContext(ioDispatcher) { contentReader.readBytes(uri) }
            } catch (e: CancellationException) {
                // Cooperative cancellation must propagate (SKIE/iOS requirement).
                throw e
            } catch (e: Exception) {
                stateHandle.update {
                    copy(isAvatarUploading = false, error = "Could not read selected image: ${e.message}")
                }
                return@launch
            }
            if (bytes == null) {
                stateHandle.update {
                    copy(isAvatarUploading = false, error = "Could not read selected image")
                }
                return@launch
            }
            when (val result = userRepository.uploadAvatar(bytes)) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            user = result.data.toDisplayData(),
                            isAvatarUploading = false,
                            showAvatarDialog = false,
                        )
                    }
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            isAvatarUploading = false,
                            error = result.message ?: "Failed to upload avatar",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    // ── Sign out & deletion ────────────────────────────────────────

    fun logout() {
        stateHandle.update { copy(isLoggedOut = true) }
    }

    fun deleteAccount(token: String? = null, backupCode: String? = null) {
        stateHandle.scope.launch {
            stateHandle.update { copy(isDeletingAccount = true) }
            when (val result = userRepository.deleteUser(token = token, backupCode = backupCode)) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(isDeletingAccount = false, isAccountDeleted = true, showDeleteAccountOtpDialog = false)
                    }
                }
                is Result.Error -> {
                    val needsOtp = token == null && backupCode == null &&
                        result.isHttpStatus(403)
                    stateHandle.update {
                        copy(
                            isDeletingAccount = false,
                            showDeleteAccountOtpDialog = if (needsOtp) true else showDeleteAccountOtpDialog,
                            error = if (needsOtp) null else (result.message ?: "Failed to delete account"),
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissDeleteAccountOtpDialog() {
        stateHandle.update { copy(showDeleteAccountOtpDialog = false) }
    }

    fun dismissError() {
        stateHandle.update { copy(error = null) }
    }

    private companion object {
        /** Upstream SystemRoles.ADMIN — the role name manageRoles checks. */
        const val ADMIN_ROLE = "ADMIN"
    }
}
