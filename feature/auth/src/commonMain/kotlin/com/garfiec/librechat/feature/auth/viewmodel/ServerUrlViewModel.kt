package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class ServerUrlUiState(
    val url: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isValidated: Boolean = false,
    val hasExistingUrl: Boolean = false,
    val showHttpWarning: Boolean = false,
)

/**
 * Validates a server URL and selects it for the sign-in flow. Two modes:
 *
 * - **Normal** (`addAccount = false`): the pre-login screen. Sets the process-global server URL and
 *   validates + caches the config through the live pipeline.
 * - **Add-account** (`addAccount = true`): reached from the account switcher while another account
 *   is live. Must never touch the live account's state: the URL goes into a pending add session
 *   ([AccountSwitcher.beginAdd]) instead of the global store, and validation runs under the pending
 *   identity without publishing to the live server's config state/cache
 *   ([ConfigRepository.probeServerUrl]). The validated config rides on the pending session for the
 *   add-mode login screen.
 */
class ServerUrlViewModel(
    private val serverDataStore: ServerDataStore,
    private val configRepository: ConfigRepository,
    private val accountSwitcher: AccountSwitcher,
    private val addAccount: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUrlUiState())
    val uiState: StateFlow<ServerUrlUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // awaitBaseUrl (not getBaseUrl) so a cold-start relaunch doesn't read "" before
            // ServerDataStore's async warm-up resolves, which would skip pre-filling the saved URL.
            // In add mode this pre-fills the ACTIVE server so the common same-server-different-user
            // add is a single tap; typing a different URL adds a new server.
            val existingUrl = serverDataStore.awaitBaseUrl()
            if (existingUrl.isNotBlank()) {
                _uiState.value = _uiState.value.copy(
                    url = existingUrl,
                    hasExistingUrl = true,
                )
            }
        }
    }

    fun onUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(url = url, error = null)
    }

    fun validateAndConnect() {
        val url = _uiState.value.url.trim().trimEnd('/')
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter a server URL")
            return
        }

        // Show warning dialog if user enters an HTTP URL
        if (url.startsWith("http://", ignoreCase = true)) {
            _uiState.value = _uiState.value.copy(showHttpWarning = true)
            return
        }

        // Auto-add https:// if no scheme provided
        val normalizedUrl = if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            "https://$url"
        } else {
            url
        }

        doValidateAndConnect(normalizedUrl)
    }

    fun confirmHttpConnection() {
        _uiState.value = _uiState.value.copy(showHttpWarning = false)
        val url = _uiState.value.url.trim().trimEnd('/')
        doValidateAndConnect(url)
    }

    fun dismissHttpWarning() {
        _uiState.value = _uiState.value.copy(showHttpWarning = false)
    }

    private fun doValidateAndConnect(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = if (addAccount) validatePendingServer(url) else validateLiveServer(url)

            when (result) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isValidated = true,
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Could not connect to server",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private suspend fun validateLiveServer(url: String): Result<*> {
        // Set the URL first so API calls use it
        serverDataStore.setServerUrl(url)
        val result = configRepository.validateServerUrl(url)
        if (result is Result.Error) {
            serverDataStore.setServerUrl("")
        }
        return result
    }

    private suspend fun validatePendingServer(url: String): Result<*> {
        // beginAdd requires a resolved active account; the switcher only offers "add" while one is
        // live, but a logout/expiry racing the tap must surface as an error, not a crash.
        val begun = runCatching { accountSwitcher.beginAdd(url) }
        if (begun.isFailure) {
            return Result.Error(
                begun.exceptionOrNull(),
                "Could not start adding an account. Try again.",
            )
        }
        val result = accountSwitcher.withPendingIdentity { configRepository.probeServerUrl() }
        when (result) {
            is Result.Success -> accountSwitcher.attachPendingConfig(result.data)
            // Drop the pending session so an abandoned attempt leaves no staged state; a retry
            // begins a fresh one.
            is Result.Error -> accountSwitcher.cancelAdd()
            is Result.Loading -> Unit
        }
        return result
    }
}
