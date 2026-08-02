package com.garfiec.librechat.feature.auth.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.HeaderWriteFailure
import com.garfiec.librechat.core.data.repository.HeaderWriteResult
import com.garfiec.librechat.core.data.repository.ServerRepository
import com.garfiec.librechat.core.network.client.CustomHeaderRules
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import com.garfiec.librechat.core.ui.components.toPairs
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
    /**
     * The stored headers could not be read, so [customHeaders] is empty because nothing loaded, not
     * because nothing is configured. Rendered as a warning: an unlabelled empty editor reads as the
     * credential having been lost.
     */
    val headersLoadFailed: Boolean = false,
    /**
     * Why the typed headers could not be persisted, or null. Connect stops on any of these rather
     * than probing, because a probe without the credential fails as an opaque connection error and
     * sends the user to fix a URL that was never the problem.
     */
    val headersSaveFailure: HeaderWriteFailure? = null,
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
    private val serverRepository: ServerRepository,
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
            val savedHeaders = if (addAccount) emptyMap() else serverRepository.headersForServer(existingUrl)
            // Both awaits above can outlast the first frame, and `headersForServer` adds a second
            // suspension on the header store's own warm-up. By the time they resolve the user may
            // already be typing — applying the prefill then overwrites a URL or a freshly rotated
            // token with the stale persisted set, and can collapse the section back shut.
            if (userEdited) return@launch
            _uiState.value = _uiState.value.copy(
                url = existingUrl,
                customHeaders = savedHeaders.orEmpty().map { (name, value) -> CustomHeaderRow(name, value) },
                // Auto-expand when headers already exist, so a saved credential is never invisible —
                // and equally when they could not be read, so the warning is not hidden behind a
                // collapsed section.
                showAdvanced = savedHeaders == null || savedHeaders.isNotEmpty(),
                // Null is "could not read", not "none configured": an unlabelled empty editor would
                // read as the credential having been lost.
                headersLoadFailed = savedHeaders == null,
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
            headersSaveFailure = null,
        )
    }

    fun removeHeaderRow(index: Int) {
        val current = _uiState.value.customHeaders
        if (index !in current.indices) return
        markHeadersEdited()
        _uiState.value = _uiState.value.copy(
            customHeaders = current.filterIndexed { i, _ -> i != index },
            headerError = null,
            headersSaveFailure = null,
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
            // point at the wrong row after an add/remove. The store-level failure goes for a second
            // reason — it masks the load warning, so leaving it up hides the explanation for the very
            // situation the user is typing their way out of.
            headerError = null,
            headersSaveFailure = null,
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
        val headers = _uiState.value.customHeaders.toPairs()
        CustomHeaderRules.firstRejection(headers)?.let { rejection ->
            // Reject before touching the network: a gateway answers a malformed credential with a
            // redirect to its own login page, which surfaces as an opaque "could not reach the
            // server" — the user would have no way to tell a typo from a wrong URL.
            _uiState.value = _uiState.value.copy(
                headerError = HeaderFieldError(rejection.index, rejection.reason),
                showAdvanced = true,
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                headerError = null,
                headersSaveFailure = null,
            )

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
            // Stop on a refusal rather than probing anyway: the probe would go out without the
            // credential, come back as the gateway's login page, and surface as "could not connect
            // to server" — sending the user to re-check a URL that was never the problem, with the
            // typed token silently not stored and every retry failing identically. That includes the
            // store refusing to clear a value it could not read, which this screen does not
            // second-guess: the rule lives in one place and both editors write through it.
            val typed = CustomHeaderRules.toHeaderMap(headers)
            // Add mode's editor starts empty by design — it must not show the active server's secret
            // — so an empty map here is "the user typed nothing", never "the user cleared their
            // headers". The URL is prefilled with the ACTIVE server, so writing it would delete a
            // credential belonging to a session the user is still signed in to, from a screen about
            // a different account. There is nothing to clear in add mode: the editor never
            // represented anything stored.
            val wouldClearWithoutHavingLoaded = addAccount && typed.isEmpty()
            if (headersEdited && !wouldClearWithoutHavingLoaded) {
                val written = serverRepository.setHeaders(url, typed)
                if (written is HeaderWriteResult.Refused) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        showAdvanced = true,
                        headersSaveFailure = written.reason,
                    )
                    return@launch
                }
                // A write that landed replaces whatever could not be read, so the warning is spent.
                _uiState.value = _uiState.value.copy(headersLoadFailed = false)
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
}
