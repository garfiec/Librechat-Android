package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class VerifyEmailUiState(
    val email: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isVerified: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val resendSuccess: Boolean = false,
)

class VerifyEmailViewModel(
    private val userRepository: UserRepository,
    initialEmail: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VerifyEmailUiState(email = initialEmail ?: ""),
    )
    val uiState: StateFlow<VerifyEmailUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    fun setEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email)
    }

    fun verifyEmail(token: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = userRepository.verifyEmail(token)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isVerified = true,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.rateLimitMessageOrNull()
                            ?: "Verification failed. The link may have expired.",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun consumeVerified() {
        _uiState.value = _uiState.value.copy(isVerified = false)
    }

    fun resendVerification() {
        val email = _uiState.value.email
        if (email.isBlank() || _uiState.value.resendCooldownSeconds > 0) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, resendSuccess = false)
            when (val result = userRepository.resendVerification(email)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        resendSuccess = true,
                    )
                    startCooldown()
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.rateLimitMessageOrNull()
                            ?: "Could not resend verification email. Please try again later.",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (seconds in COOLDOWN_DURATION_SECONDS downTo 1) {
                _uiState.value = _uiState.value.copy(resendCooldownSeconds = seconds)
                delay(1000L)
            }
            _uiState.value = _uiState.value.copy(resendCooldownSeconds = 0)
        }
    }

    companion object {
        private const val COOLDOWN_DURATION_SECONDS = 60
    }
}
