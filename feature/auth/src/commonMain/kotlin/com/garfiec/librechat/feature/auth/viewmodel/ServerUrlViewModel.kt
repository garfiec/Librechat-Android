package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
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

class ServerUrlViewModel(
    private val serverDataStore: ServerDataStore,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUrlUiState())
    val uiState: StateFlow<ServerUrlUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // awaitBaseUrl (not getBaseUrl) so a cold-start relaunch doesn't read "" before
            // ServerDataStore's async warm-up resolves, which would skip pre-filling the saved URL.
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

            // Set the URL first so API calls use it
            serverDataStore.setServerUrl(url)

            when (val result = configRepository.validateServerUrl(url)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isValidated = true,
                    )
                }
                is Result.Error -> {
                    // Reset URL on failure
                    serverDataStore.setServerUrl("")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message ?: "Could not connect to server",
                    )
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }
}
