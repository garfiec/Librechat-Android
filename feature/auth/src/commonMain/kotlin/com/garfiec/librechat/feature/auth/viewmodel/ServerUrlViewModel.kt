package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.ServerHeadersDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.network.client.CustomHeaderRules
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A rejected header row, as the row index plus the machine-readable reason. Deliberately not a
 * message: `:core:network` has no string resources and this module's strings live in its own
 * `composeResources`, so the mapping to text belongs in the composable.
 */
@Immutable
data class HeaderFieldError(val index: Int, val reason: HeaderRejection)

@Immutable
data class ServerUrlUiState(
    val url: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isValidated: Boolean = false,
    val showHttpWarning: Boolean = false,
    val showAdvanced: Boolean = false,
    val customHeaders: List<CustomHeaderRow> = emptyList(),
    val headerError: HeaderFieldError? = null,
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
    private val serverHeadersDataStore: ServerHeadersDataStore,
    private val configRepository: ConfigRepository,
    private val accountSwitcher: AccountSwitcher,
    private val addAccount: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUrlUiState())
    val uiState: StateFlow<ServerUrlUiState> = _uiState.asStateFlow()

    /**
     * Whether the user has edited a header row. Gates the save at Connect: the editor starts empty
     * (always in add mode, and in normal mode until the store resolves), so an unconditional write
     * would mean "empty editor" == "delete this server's credential".
     */
    private var headersEdited = false

    /** Whether the user has interacted at all, which makes the async prefill stale rather than late. */
    private var userEdited = false

    init {
        viewModelScope.launch {
            // awaitBaseUrl (not getBaseUrl) so a cold-start relaunch doesn't read "" before
            // ServerDataStore's async warm-up resolves, which would skip pre-filling the saved URL.
            // In add mode this pre-fills the ACTIVE server so the common same-server-different-user
            // add is a single tap; typing a different URL adds a new server.
            val existingUrl = serverDataStore.awaitBaseUrl()
            if (existingUrl.isBlank()) return@launch
            // The URL prefill above deliberately shows the ACTIVE server in add mode — but its
            // headers must NOT come along. They are a credential, and prefilling them would show the
            // live server's secret on a screen the user believes is configuring a *new* server; an
            // edit there would then silently rewrite the live server's credential. Add mode starts
            // empty and the user re-enters what the new server needs.
            val savedHeaders = if (addAccount) emptyMap() else serverHeadersDataStore.headersForServer(existingUrl)
            // Both awaits above can outlast the first frame, and `headersForServer` adds a second
            // suspension on the header store's own warm-up. By the time they resolve the user may
            // already be typing — applying the prefill then overwrites a URL or a freshly rotated
            // token with the stale persisted set, and can collapse the section back shut.
            if (userEdited) return@launch
            _uiState.value = _uiState.value.copy(
                url = existingUrl,
                customHeaders = savedHeaders.map { (name, value) -> CustomHeaderRow(name, value) },
                // Auto-expand when headers already exist, so a saved credential is never invisible.
                showAdvanced = savedHeaders.isNotEmpty(),
            )
        }
    }

    fun onUrlChanged(url: String) {
        // Deliberately does not re-key the saved headers: this fires per keystroke, and deriveServerId
        // throws on partial input. The header set is committed against the final URL on Connect.
        userEdited = true
        _uiState.value = _uiState.value.copy(url = url, error = null)
    }

    /**
     * Retires the one-shot navigation signal once the host has acted on it.
     *
     * Without this the flag stays latched for the ViewModel's whole life, and the screen's
     * `LaunchedEffect(isValidated)` re-fires the moment the entry is re-composed. Popping BACK onto
     * this screen therefore navigated straight forward again — in add-account mode that made the
     * flow impossible to leave by any means short of killing the app, since the ViewModel survives
     * in the nav back stack. Same shape as `TermsViewModel.consumeAccepted`.
     */
    fun consumeValidated() {
        _uiState.value = _uiState.value.copy(isValidated = false)
    }

    fun toggleAdvanced() {
        _uiState.value = _uiState.value.copy(showAdvanced = !_uiState.value.showAdvanced)
    }

    fun addHeaderRow() {
        markHeadersEdited()
        _uiState.value = _uiState.value.copy(
            customHeaders = _uiState.value.customHeaders + CustomHeaderRow(),
            headerError = null,
        )
    }

    fun removeHeaderRow(index: Int) {
        val current = _uiState.value.customHeaders
        if (index !in current.indices) return
        markHeadersEdited()
        _uiState.value = _uiState.value.copy(
            customHeaders = current.filterIndexed { i, _ -> i != index },
            headerError = null,
        )
    }

    private fun markHeadersEdited() {
        headersEdited = true
        userEdited = true
    }

    fun onHeaderNameChanged(index: Int, name: String) = updateHeader(index) { it.copy(name = name) }

    fun onHeaderValueChanged(index: Int, value: String) = updateHeader(index) { it.copy(value = value) }

    private fun updateHeader(index: Int, transform: (CustomHeaderRow) -> CustomHeaderRow) {
        val current = _uiState.value.customHeaders
        if (index !in current.indices) return
        markHeadersEdited()
        _uiState.value = _uiState.value.copy(
            customHeaders = current.mapIndexed { i, field -> if (i == index) transform(field) else field },
            // Clear the rejection as soon as the user edits: leaving it pinned to a stale index would
            // point at the wrong row after an add/remove.
            headerError = null,
        )
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
        val headers = _uiState.value.customHeaders
        firstInvalidHeader(headers)?.let { rejection ->
            // Reject before touching the network: a gateway answers a malformed credential with a
            // redirect to its own login page, which surfaces as an opaque "could not reach the
            // server" — the user would have no way to tell a typo from a wrong URL.
            _uiState.value = _uiState.value.copy(headerError = rejection, showAdvanced = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, headerError = null)

            // Three rules here, each easy to undo:
            // - Awaited BEFORE the probe below, which is the first request to a gateway-protected
            //   server and must already carry the headers; in add mode also before beginAdd(),
            //   which mints the pending identity that reads them.
            // - Keyed on the normalized `url`, not the raw field text: `http://` and `https://`
            //   derive different serverIds, so the credential would be filed under a server the
            //   app never contacts.
            // - Only when a row was actually edited. An empty map means "delete this server's
            //   headers", and the editor is empty by default both in add mode (which prefills the
            //   ACTIVE server's URL, so one tap on the untouched prefill would wipe the live
            //   session's credential) and before the store resolves. Clearing is expressed by
            //   removing rows, which sets the flag.
            if (headersEdited) {
                serverHeadersDataStore.setHeaders(url, headers.toHeaderMap())
            }

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

    /**
     * The first row that can't be sent, or null. Fully blank rows are skipped — the list always ends
     * with an empty row the user hasn't filled in yet, and that isn't an error.
     */
    private fun firstInvalidHeader(headers: List<CustomHeaderRow>): HeaderFieldError? =
        headers.withIndex().firstNotNullOfOrNull { (index, field) ->
            if (field.name.isBlank() && field.value.isBlank()) {
                null
            } else {
                CustomHeaderRules.validate(field.name, field.value)?.let { HeaderFieldError(index, it) }
            }
        }

    /** Drops blank rows and normalizes what's left. Later rows win on a duplicate name. */
    private fun List<CustomHeaderRow>.toHeaderMap(): Map<String, String> =
        filterNot { it.name.isBlank() && it.value.isBlank() }
            .associate { it.name.trim() to CustomHeaderRules.normalizeValue(it.value) }
}
