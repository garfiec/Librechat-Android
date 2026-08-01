package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import com.garfiec.librechat.core.ui.components.CustomHeaderRowError
import com.garfiec.librechat.core.ui.components.CustomHeadersEditor
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.action_cancel
import com.garfiec.librechat.feature.settings.resources.section_server_connection
import com.garfiec.librechat.feature.settings.resources.server_headers_discard_confirm
import com.garfiec.librechat.feature.settings.resources.server_headers_discard_message
import com.garfiec.librechat.feature.settings.resources.server_headers_discard_title
import com.garfiec.librechat.feature.settings.resources.server_headers_keep_editing
import com.garfiec.librechat.feature.settings.resources.server_headers_save
import com.garfiec.librechat.feature.settings.resources.server_headers_scope
import com.garfiec.librechat.feature.settings.viewmodel.ServerHeaderError
import org.jetbrains.compose.resources.stringResource

/**
 * Post-login editor for the active server's gateway headers (issue #287).
 *
 * The pre-login server screen owns the same headers, but reaching it requires being signed out — and a
 * gateway credential is exactly the thing that gets rotated or revoked *mid-session*. Without this the
 * only recovery is to log out, which is not a step anyone would guess from "could not reach the
 * server."
 *
 * Saving is explicit and deliberately does **not** re-probe: a wrong value fails the next ordinary
 * request, and a probe would only duplicate that with a second, differently-worded error. Stateless
 * apart from the discard prompt — the caller owns the rows, the save and the dismissal, and is what
 * closes this on a save that actually landed.
 */
@Composable
fun ServerHeadersDialog(
    serverUrl: String,
    headers: List<CustomHeaderRow>,
    error: ServerHeaderError?,
    isSaving: Boolean,
    isDirty: Boolean,
    onNameChange: (Int, String) -> Unit,
    onValueChange: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Dismissing with pending edits asks first: these values are pasted secrets, and a stray tap
    // outside the dialog is a cheap way to lose one with no way to get it back.
    val requestClose: () -> Unit = {
        if (isDirty) showDiscardConfirm = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = requestClose,
        modifier = modifier,
        title = { Text(stringResource(Res.string.section_server_connection)) },
        text = {
            // AlertDialog clips its content instead of scrolling it, so a handful of header rows
            // plus the keyboard would put the fields out of reach with no way to get at them.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (serverUrl.isNotBlank()) {
                    Text(
                        text = stringResource(Res.string.server_headers_scope, serverUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CustomHeadersEditor(
                    headers = headers,
                    onNameChange = onNameChange,
                    onValueChange = onValueChange,
                    onAdd = onAdd,
                    onRemove = onRemove,
                    errorIndex = error?.index,
                    errorReason = error?.reason?.toRowError(),
                    enabled = !isSaving,
                    // The dialog's own title already names this.
                    showHeading = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !isSaving && isDirty,
                modifier = Modifier.testTag("server_headers_save"),
            ) {
                Text(stringResource(Res.string.server_headers_save))
            }
        },
        dismissButton = {
            TextButton(onClick = requestClose, enabled = !isSaving) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(Res.string.server_headers_discard_title)) },
            text = { Text(stringResource(Res.string.server_headers_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        // Revert, or the abandoned edit is still sitting there on the next open:
                        // the ViewModel behind this outlives the dialog.
                        onDiscard()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("server_headers_discard"),
                ) {
                    Text(stringResource(Res.string.server_headers_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(Res.string.server_headers_keep_editing))
                }
            },
        )
    }
}

/**
 * `:core:ui` deliberately does not depend on `:core:network`, so the wire-level rejection is mapped to
 * the editor's own enum here. Exhaustive `when` — a new [HeaderRejection] case breaks the build rather
 * than silently rendering no message.
 */
private fun HeaderRejection.toRowError(): CustomHeaderRowError = when (this) {
    HeaderRejection.InvalidName -> CustomHeaderRowError.InvalidName
    HeaderRejection.InvalidValue -> CustomHeaderRowError.InvalidValue
    HeaderRejection.ReservedName -> CustomHeaderRowError.ReservedName
}
