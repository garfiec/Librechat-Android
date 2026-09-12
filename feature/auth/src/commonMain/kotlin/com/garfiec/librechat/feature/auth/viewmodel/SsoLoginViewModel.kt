package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.feature.auth.oauth.OAuthCookieStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SsoLoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
)

class SsoLoginViewModel(
    private val authRepository: AuthRepository,
    private val oAuthCookieStore: OAuthCookieStore,
    serverDataStore: ServerDataStore,
    private val accountSwitcher: AccountSwitcher,
    private val provider: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SsoLoginUiState())
    val uiState: StateFlow<SsoLoginUiState> = _uiState.asStateFlow()

    /** The server this screen is signing into: the pending add target when set, else the live one. */
    val serverUrl: String =
        accountSwitcher.pendingAdd?.serverUrl ?: serverDataStore.getBaseUrl()

    private var tokenConsumed = false

    init {
        // The cookie jar is process-global (carries cookies from any prior identity/session), so
        // wipe the server's stale refreshToken cookie before the round-trip — only a cookie minted
        // by THIS round-trip may be captured.
        oAuthCookieStore.clearRefreshTokenCookie(serverUrl)
    }

    fun onTokenCaptured(refreshToken: String) {
        if (tokenConsumed) return
        tokenConsumed = true

        // Clear immediately to avoid re-capture on subsequent page events.
        oAuthCookieStore.clearRefreshTokenCookie(serverUrl)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = authRepository.loginWithOAuthToken(refreshToken)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "SSO sign-in failed",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun onOAuthError(message: String?) {
        if (tokenConsumed) return
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = message ?: "Sign-in with ${providerLabel()} failed",
        )
    }

    private fun providerLabel(): String = when (provider) {
        "openid" -> "OpenID"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
}