package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.ServerHeadersDataStore
import com.garfiec.librechat.core.network.client.CustomHeaderRules
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A rejected header row: the index plus the machine-readable reason, mapped to text in the UI. */
@Immutable
data class ServerHeaderError(val index: Int, val reason: HeaderRejection)

/** Why a save didn't happen. Row-level rejections are [ServerHeaderError]; these are screen-level. */
enum class ServerHeadersSaveFailure {
    /** No active server URL resolved, so there is no server id to file the headers under. */
    NoActiveServer,
}

@Immutable
data class ServerHeadersUiState(
    val serverUrl: String = "",
    val headers: List<CustomHeaderRow> = emptyList(),
    val error: ServerHeaderError? = null,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    /** One-shot: set on a successful save, cleared by [consumeSaved] once the confirmation is shown. */
    val saved: Boolean = false,
    /** One-shot: set when a save could not be persisted, cleared by [consumeSaveFailure]. */
    val saveFailure: ServerHeadersSaveFailure? = null,
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
    private val serverHeadersDataStore: ServerHeadersDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerHeadersUiState())
    val uiState: StateFlow<ServerHeadersUiState> = _uiState.asStateFlow()

    /** The URL whose stored headers are currently loaded, or null before the first resolution. */
    private var loadedFor: String? = null

    init {
        viewModelScope.launch {
            serverDataStore.currentUrlFlow.distinctUntilChanged().collect { url ->
                if (url.isBlank()) return@collect
                val saved = serverHeadersDataStore.headersForServer(url)
                val previous = loadedFor
                loadedFor = url
                val state = _uiState.value
                // Keep edits ONLY across the very first resolution (blank → the active server): the
                // user can start typing into the section before ServerDataStore's warm-up lands, and
                // those rows were always meant for "this server". A genuine server *change* is the
                // opposite case — the rows belong to the server that just went away, so carrying them
                // over is precisely the mix-up this observation exists to prevent.
                val keepEdits = previous == null && state.isDirty
                _uiState.value = if (keepEdits) {
                    state.copy(serverUrl = url)
                } else {
                    state.copy(
                        serverUrl = url,
                        headers = saved.map { (name, value) -> CustomHeaderRow(name, value) },
                        error = null,
                        isDirty = false,
                    )
                }
            }
        }
    }

    fun addHeaderRow() {
        _uiState.value = _uiState.value.copy(
            headers = _uiState.value.headers + CustomHeaderRow(),
            error = null,
            isDirty = true,
        )
    }

    fun removeHeaderRow(index: Int) {
        val current = _uiState.value.headers
        if (index !in current.indices) return
        _uiState.value = _uiState.value.copy(
            headers = current.filterIndexed { i, _ -> i != index },
            error = null,
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
            _uiState.value = _uiState.value.copy(headers = emptyList(), error = null, isDirty = false)
            return
        }
        viewModelScope.launch {
            val saved = serverHeadersDataStore.headersForServer(url)
            _uiState.value = _uiState.value.copy(
                headers = saved.map { (name, value) -> CustomHeaderRow(name, value) },
                error = null,
                isDirty = false,
            )
        }
    }

    /** Clears the one-shot success flag once the UI has shown its confirmation. */
    fun consumeSaved() {
        if (_uiState.value.saved) _uiState.value = _uiState.value.copy(saved = false)
    }

    /** Clears the one-shot failure flag once the UI has shown it. */
    fun consumeSaveFailure() {
        if (_uiState.value.saveFailure != null) _uiState.value = _uiState.value.copy(saveFailure = null)
    }

    /**
     * Validate and persist. Nothing here retries or re-probes the server: the store is Flow-backed, so
     * the next request picks the new values up on its own, and in-flight requests keep the headers
     * they were snapshotted with (immutable by design).
     */
    fun save() {
        val rows = _uiState.value.headers
        firstInvalid(rows)?.let { rejection ->
            _uiState.value = _uiState.value.copy(error = rejection)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            // Re-resolve rather than giving up on a blank URL: the Save button is enabled as soon as
            // a row is edited, which can be before ServerDataStore has warmed up. Returning silently
            // there drops the credential with no write, no error and no way to retry.
            val url = _uiState.value.serverUrl.ifBlank { serverDataStore.awaitBaseUrl() }
            // Honour the store's own verdict: setHeaders no-ops when the URL yields no server id, and
            // confirming "saved" over that is a lie the user cannot see through.
            val persisted = url.isNotBlank() && serverHeadersDataStore.setHeaders(url, rows.toHeaderMap())
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                serverUrl = if (url.isNotBlank()) url else _uiState.value.serverUrl,
                isDirty = !persisted,
                saved = persisted,
                saveFailure = if (persisted) null else ServerHeadersSaveFailure.NoActiveServer,
            )
        }
    }

    /** The first row that can't be sent, or null. Fully blank rows are skipped, not rejected. */
    private fun firstInvalid(rows: List<CustomHeaderRow>): ServerHeaderError? =
        rows.withIndex().firstNotNullOfOrNull { (index, row) ->
            if (row.name.isBlank() && row.value.isBlank()) {
                null
            } else {
                CustomHeaderRules.validate(row.name, row.value)?.let { ServerHeaderError(index, it) }
            }
        }

    /** Drops blank rows and normalizes what's left. Later rows win on a duplicate name. */
    private fun List<CustomHeaderRow>.toHeaderMap(): Map<String, String> =
        filterNot { it.name.isBlank() && it.value.isBlank() }
            .associate { it.name.trim() to CustomHeaderRules.normalizeValue(it.value) }
}
