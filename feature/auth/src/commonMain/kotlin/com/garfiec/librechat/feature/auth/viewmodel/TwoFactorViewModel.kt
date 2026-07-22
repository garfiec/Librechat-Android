package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class TwoFactorUiState(
    val digits: List<String> = List(6) { "" },
    val isBackupMode: Boolean = false,
    val backupCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isVerified: Boolean = false,
)

class TwoFactorViewModel(
    private val authRepository: AuthRepository,
    initialTempToken: String? = null,
) : ViewModel() {

    private val tempToken: String = initialTempToken ?: ""

    private val _uiState = MutableStateFlow(TwoFactorUiState())
    val uiState: StateFlow<TwoFactorUiState> = _uiState.asStateFlow()

    fun onDigitChanged(index: Int, value: String) {
        if (value.length > 1) return
        val newDigits = _uiState.value.digits.toMutableList()
        newDigits[index] = value
        _uiState.value = _uiState.value.copy(digits = newDigits, error = null)

        // Auto-submit when all 6 digits are entered
        if (newDigits.all { it.isNotEmpty() }) {
            submit()
        }
    }

    fun onBackupCodeChanged(code: String) {
        _uiState.value = _uiState.value.copy(backupCode = code, error = null)
    }

    fun toggleBackupMode() {
        _uiState.value = _uiState.value.copy(
            isBackupMode = !_uiState.value.isBackupMode,
            error = null,
            digits = List(6) { "" },
            backupCode = "",
        )
    }

    fun submit() {
        val state = _uiState.value
        val code = if (state.isBackupMode) {
            state.backupCode.trim()
        } else {
            state.digits.joinToString("")
        }

        if (code.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = authRepository.verifyTwoFactor(tempToken, code, isBackupCode = state.isBackupMode)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isVerified = true,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        // Prefer the server's own wording: "invalid code" and "your session expired,
                        // sign in again" call for different actions, and a flat retry prompt has the
                        // user typing codes at a tempToken that can no longer succeed.
                        error = (result.exception as? ApiException)?.message
                            ?: "Invalid code. Please try again.",
                        digits = List(6) { "" },
                        backupCode = "",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
