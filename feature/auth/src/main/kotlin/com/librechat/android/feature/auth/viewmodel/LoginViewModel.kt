package com.librechat.android.feature.auth.viewmodel

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librechat.android.core.common.result.Result
import com.librechat.android.core.data.datastore.ServerDataStore
import com.librechat.android.core.data.repository.AuthRepository
import com.librechat.android.core.data.repository.ConfigRepository
import com.librechat.android.core.model.LoginOutcome
import com.librechat.android.core.model.StartupConfig
import com.librechat.android.feature.auth.oauth.OAuthWebViewActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val twoFactorTempToken: String? = null,
    val registrationEnabled: Boolean = false,
    /** When false, hide email/password (e.g. GitHub-only servers). */
    val emailLoginEnabled: Boolean = true,
    val socialLoginEnabled: Boolean = false,
    val socialLogins: List<String> = emptyList(),
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val configRepository: ConfigRepository,
    private val serverDataStore: ServerDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            configRepository.startupConfig.collect { config ->
                if (config != null) {
                    val providers = mergeSocialProviders(config)
                    _uiState.value = _uiState.value.copy(
                        registrationEnabled = config.registrationEnabled,
                        emailLoginEnabled = config.emailLoginEnabled,
                        // Many LibreChat instances set githubLoginEnabled / googleLoginEnabled without socialLogins[]
                        socialLoginEnabled = providers.isNotEmpty(),
                        socialLogins = providers,
                    )
                }
            }
        }
    }

    fun onEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(email = email, error = null)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun login() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "Please enter email and password")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            when (val result = authRepository.login(state.email, state.password)) {
                is Result.Success -> {
                    when (val outcome = result.data) {
                        is LoginOutcome.Success -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isLoggedIn = true,
                            )
                        }
                        is LoginOutcome.TwoFactorRequired -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                twoFactorTempToken = outcome.tempToken,
                            )
                        }
                    }
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Login failed",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun createOAuthIntent(context: Context, provider: String): Intent {
        val base = serverDataStore.getBaseUrl().trimEnd('/')
        return OAuthWebViewActivity.createIntent(context, base, provider)
    }

    fun completeOAuthWithRefreshToken(refreshToken: String) {
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
                        error = result.message ?: "OAuth login failed",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private companion object {
        /**
         * Builds the list of OAuth provider ids for the login screen.
         * Backend may send [StartupConfig.socialLogins] and/or per-provider booleans.
         */
        fun mergeSocialProviders(config: StartupConfig): List<String> {
            val order = LinkedHashSet<String>()
            config.socialLogins?.forEach { order.add(it.lowercase()) }
            if (config.githubLoginEnabled) order.add("github")
            if (config.googleLoginEnabled) order.add("google")
            if (config.discordLoginEnabled) order.add("discord")
            if (config.facebookLoginEnabled) order.add("facebook")
            if (config.appleLoginEnabled) order.add("apple")
            if (config.openidLoginEnabled) order.add("openid")
            return order.toList()
        }
    }
}
