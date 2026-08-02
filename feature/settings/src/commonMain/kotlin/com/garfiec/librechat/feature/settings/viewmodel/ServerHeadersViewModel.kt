package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.data.datastore.ServerDataStore
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A rejected header row: the index plus the machine-readable reason, mapped to text in the UI. */
@Immutable
data class ServerHeaderError(val index: Int, val reason: HeaderRejection)

@Immutable
data class ServerHeadersUiState(
    val serverUrl: String = "",
    val headers: List<CustomHeaderRow> = emptyList(),
    val error: ServerHeaderError? = null,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    /** One-shot: set on a successful save, cleared by [consumeSaved] once the confirmation is shown. */
    val saved: Boolean = false,
    /**
     * Why the last save didn't land, rendered *inside* the editor and cleared by the next edit or
     * save. Not a one-shot event: a refused save leaves the dialog open and unchanged, so a message
     * that plays once — behind the dialog's own scrim, at that — reads as a dead Save button.
     */
    val saveFailure: HeaderWriteFailure? = null,
    /**
     * The stored headers could not be read, so [headers] is whatever was already on screen rather
     * than what is persisted. Rendering an unreadable store as an empty editor would tell the user
     * their credential is gone — and a save from that state would then really erase it.
     */
    val loadFailed: Boolean = false,
)

/**
 * Post-login editor for the active server's gateway headers (issue #287).
 *
 * The pre-login screen owns the same headers, but only reaching it requires being signed out — and a
 * gateway credential is exactly the thing that can be revoked or rotated *mid-session*. Without this
 * the only recovery is to log out, which is not a step anyone would guess from "could not reach the
 * server."
 *
 * Always edits the **active** server, and follows it: the URL is *observed*, not read once, because
 * this screen can stay composed across an account switch (a two-pane tablet layout never navigates
 * away from it). A stale URL would file the edited credential under the previous server's id —
 * breaking the server being edited and overwriting the one that was working.
 */
class ServerHeadersViewModel(
    private val serverDataStore: ServerDataStore,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerHeadersUiState())
    val uiState: StateFlow<ServerHeadersUiState> = _uiState.asStateFlow()

    /** The URL whose stored headers are currently loaded, or null before the first resolution. */
    private var loadedFor: String? = null

    init {
        viewModelScope.launch {
            serverDataStore.currentUrlFlow.distinctUntilChanged().collect { url ->
                if (url.isBlank()) return@collect
                val saved = serverRepository.headersForServer(url)
                val previous = loadedFor
                loadedFor = url
                val state = _uiState.value
                // Keep edits ONLY across the very first resolution (blank → the active server): the
                // user can start typing into the section before ServerDataStore's warm-up lands, and
                // those rows were always meant for "this server". A genuine server *change* is the
                // opposite case — the rows belong to the server that just went away, so carrying them
                // over is precisely the mix-up this observation exists to prevent.
                val keepEdits = previous == null && state.isDirty
                if (keepEdits) {
                    // Rows stay, but everything describing the *store* follows the read that just
                    // happened — a refusal recorded before the URL resolved is about a write that
                    // never reached this server.
                    _uiState.value =
                        state.copy(serverUrl = url, saveFailure = null, loadFailed = saved == null)
                } else {
                    // Null is "could not read", not "none configured", and [applyLoaded] renders it as
                    // a warning rather than an empty list. The typed rows still go, though: they were
                    // typed for the server that just went away, and an unreadable store is no reason
                    // to offer one server's credential for saving under another's id.
                    applyLoaded(url, saved)
                }
            }
        }
    }

    /**
     * Re-reads the stored headers for the active server, unless there are unsaved edits to lose.
     *
     * The URL is *observed*, so for a server that never changes the load happens exactly once — and a
     * read that failed then stays failed for the life of the process, leaving the editor warning about
     * a store that has since recovered. The host calls this each time the editor is opened, which is
     * the one moment the user is in a position to act on the result.
     */
    fun reload() {
        val url = _uiState.value.serverUrl
        if (url.isBlank() || _uiState.value.isDirty) return
        viewModelScope.launch {
            val saved = serverRepository.headersForServer(url)
            // Re-checked on the far side of the read, not only before it. The read genuinely suspends
            // when the store is recovering — that is the whole case this exists for — and the editor
            // is already on screen by then, so the user is typing into it while it runs. Checking
            // only on entry moves the clobber past the await instead of preventing it.
            if (_uiState.value.isDirty) {
                _uiState.value = _uiState.value.copy(loadFailed = saved == null)
                return@launch
            }
            applyLoaded(url, saved)
        }
    }

    /** Replaces the editor with what the store just returned. Null is a failed read, not an empty set. */
    private fun applyLoaded(url: String, saved: Map<String, String>?) {
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            headers = saved.orEmpty().map { (name, value) -> CustomHeaderRow(name, value) },
            error = null,
            isDirty = false,
            saveFailure = null,
            loadFailed = saved == null,
        )
    }

    fun addHeaderRow() {
        _uiState.value = _uiState.value.copy(
            headers = _uiState.value.headers + CustomHeaderRow(),
            error = null,
            saveFailure = null,
            isDirty = true,
        )
    }

    fun removeHeaderRow(index: Int) {
        val current = _uiState.value.headers
        if (index !in current.indices) return
        _uiState.value = _uiState.value.copy(
            headers = current.filterIndexed { i, _ -> i != index },
            error = null,
            saveFailure = null,
            isDirty = true,
        )
    }

    fun onNameChanged(index: Int, name: String) = update(index) { it.copy(name = name) }

    fun onValueChanged(index: Int, value: String) = update(index) { it.copy(value = value) }

    private fun update(index: Int, transform: (CustomHeaderRow) -> CustomHeaderRow) {
        val current = _uiState.value.headers
        if (index !in current.indices) return
        _uiState.value = _uiState.value.copy(
            headers = current.mapIndexed { i, row -> if (i == index) transform(row) else row },
            // Clear on edit — a rejection pinned to a stale index points at the wrong row after an
            // add or remove.
            error = null,
            saveFailure = null,
            isDirty = true,
        )
    }

    /**
     * Throws away unsaved edits and restores what is actually persisted.
     *
     * The editor lives in a dismissible dialog but this ViewModel outlives it, so without an explicit
     * revert an abandoned edit would still be sitting there the next time it opens — presenting a
     * half-typed credential as if it were the active one.
     */
    fun discardEdits() {
        val url = _uiState.value.serverUrl
        if (url.isBlank()) {
            // No server resolved yet, so there is nothing stored to revert to and nothing that could
            // have failed to read. Same seam as every other reset — a hand-written subset here is how
            // one of these paths ended up missing a field the other two clear.
            applyLoaded(url, emptyMap())
            return
        }
        // Nothing to revert *to* if the store can't be read, but the typed rows go either way: the
        // dialog closes regardless, so leaving them would put the abandoned edit back on screen at
        // the next open — and the store refuses to write an empty set over a value it could not
        // read, so discarding them here destroys nothing persisted.
        viewModelScope.launch { applyLoaded(url, serverRepository.headersForServer(url)) }
    }

    /** Clears the one-shot success flag once the UI has shown its confirmation. */
    fun consumeSaved() {
        if (_uiState.value.saved) _uiState.value = _uiState.value.copy(saved = false)
    }

    /**
     * Validate and persist. Nothing here retries or re-probes the server: the repository patches its
     * in-memory map under the same lock as the write, so the next request picks the new values up on
     * its own, and in-flight requests keep the headers they were snapshotted with (immutable by
     * design).
     */
    fun save() {
        val rows = _uiState.value.headers.toPairs()
        CustomHeaderRules.firstRejection(rows)?.let { rejection ->
            _uiState.value = _uiState.value.copy(
                error = ServerHeaderError(rejection.index, rejection.reason),
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveFailure = null)
            // Re-resolve rather than giving up on a blank URL: the Save button is enabled as soon as
            // a row is edited, which can be before ServerDataStore has warmed up. Returning silently
            // there drops the credential with no write, no error and no way to retry. A URL that
            // still won't resolve comes back as [HeaderWriteFailure.NoServer] rather than being
            // classified here.
            val url = _uiState.value.serverUrl.ifBlank { serverDataStore.awaitBaseUrl() }
            // The store decides, including whether an empty set is a real clear or the absence of a
            // read — it is the only layer that knows which, and the pre-login editor writes through
            // the same guard rather than a second copy of it.
            val result = serverRepository.setHeaders(url, CustomHeaderRules.toHeaderMap(rows))
            val persisted = result is HeaderWriteResult.Saved
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                serverUrl = if (url.isNotBlank()) url else _uiState.value.serverUrl,
                isDirty = !persisted,
                saved = persisted,
                saveFailure = (result as? HeaderWriteResult.Refused)?.reason,
                // A save that landed proves the store is readable again.
                loadFailed = if (persisted) false else _uiState.value.loadFailed,
            )
        }
    }
}
