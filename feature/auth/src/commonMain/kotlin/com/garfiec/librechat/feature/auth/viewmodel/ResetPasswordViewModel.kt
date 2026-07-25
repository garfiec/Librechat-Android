package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class ResetPasswordUiState(
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isReset: Boolean = false,
)

class ResetPasswordViewModel(
    private val authRepository: AuthRepository,
    initialUserId: String? = null,
    initialToken: String? = null,
) : ViewModel() {

    private val userId: String = initialUserId ?: ""
    private val token: String = initialToken ?: ""

    private val _uiState = MutableStateFlow(ResetPasswordUiState())
    val uiState: StateFlow<ResetPasswordUiState> = _uiState.asStateFlow()

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun onConfirmPasswordChanged(confirmPassword: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = confirmPassword, error = null)
    }

    fun resetPassword() {
        val state = _uiState.value

        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "Please enter a new password")
            return
        }
        if (state.password.length < 8) {
            _uiState.value = state.copy(error = "Password must be at least 8 characters")
            return
        }
        if (state.password != state.confirmPassword) {
            _uiState.value = state.copy(error = "Passwords do not match")
            return
        }
        if (userId.isBlank() || token.isBlank()) {
            _uiState.value = state.copy(error = "Invalid reset link. Please request a new one.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = authRepository.resetPassword(userId, token, state.password, state.confirmPassword)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isReset = true,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.rateLimitMessageOrNull()
                            ?: "Could not reset password. The link may have expired.",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
