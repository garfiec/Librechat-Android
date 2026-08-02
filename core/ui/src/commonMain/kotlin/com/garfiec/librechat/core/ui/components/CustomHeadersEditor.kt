package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.resources.Res
import com.garfiec.librechat.core.ui.resources.server_headers_add
import com.garfiec.librechat.core.ui.resources.server_headers_description
import com.garfiec.librechat.core.ui.resources.server_headers_error_name
import com.garfiec.librechat.core.ui.resources.server_headers_error_reserved
import com.garfiec.librechat.core.ui.resources.server_headers_error_value
import com.garfiec.librechat.core.ui.resources.server_headers_hide_value
import com.garfiec.librechat.core.ui.resources.server_headers_name
import com.garfiec.librechat.core.ui.resources.server_headers_remove
import com.garfiec.librechat.core.ui.resources.server_headers_show_value
import com.garfiec.librechat.core.ui.resources.server_headers_title
import com.garfiec.librechat.core.ui.resources.server_headers_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One editable header pair. Blank rows are ignored by callers rather than rejected. */
@Immutable
data class CustomHeaderRow(val name: String = "", val value: String = "")

/**
 * The rows as name-to-value pairs, for the validation and normalization helpers in `:core:network`
 * (which this module deliberately does not depend on). Named rather than inlined at each call site so
 * the two halves can't be transposed.
 */
fun List<CustomHeaderRow>.toPairs(): List<Pair<String, String>> = map { it.name to it.value }

/**
 * Why a row can't be sent, as a UI-level enum.
 *
 * Deliberately not `:core:network`'s `HeaderRejection`: mapping the two at each call site (three
 * lines) is cheaper than making `:core:ui` depend on the network module just to name three cases.
 */
enum class CustomHeaderRowError { InvalidName, InvalidValue, ReservedName }

/**
 * Below this, name and value are stacked instead of sitting side by side.
 *
 * Split across two columns each field gets under half the width, which on a phone dialog left
 * "CF-Access-Client-Id" and "CF-Access-Client-Secret" both truncated to "CF-Access-C…" — two
 * different headers rendering identically, with a masked value beside them.
 */
private val STACK_BELOW_WIDTH: Dp = 420.dp

/**
 * Editor for a server's static gateway headers (issue #287), shared by the pre-login server screen
 * and the post-login Settings dialog so the two can't drift.
 *
 * Stateless apart from which values are revealed: the caller owns the rows, the validation and the
 * persistence. The two hosts differ in exactly those things — pre-login commits on Connect as part
 * of validating the URL, Settings commits on an explicit Save against the already-active server.
 *
 * The layout branches on the width this is actually *given*, not on the device: the same phone that
 * needs stacking in a dialog has plenty of room on the full-screen pre-login form, and a tablet's
 * dialog is narrow despite the tablet.
 *
 * Test tags are indexed (`server_header_name_0`) rather than content-keyed because a header name is
 * user-entered and a value is a credential; neither belongs in an automation selector.
 */
@Composable
fun CustomHeadersEditor(
    headers: List<CustomHeaderRow>,
    onNameChange: (Int, String) -> Unit,
    onValueChange: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    errorIndex: Int? = null,
    errorReason: CustomHeaderRowError? = null,
    enabled: Boolean = true,
    showHeading: Boolean = true,
) {
    // Indices are only valid for the current row list, so any add/remove must re-mask everything —
    // otherwise a stale index aliases onto a different row's credential.
    var revealed by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(headers.size) { revealed = emptySet() }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = maxWidth < STACK_BELOW_WIDTH

        Column(modifier = Modifier.fillMaxWidth()) {
            if (showHeading) {
                Text(
                    text = stringResource(Res.string.server_headers_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = stringResource(Res.string.server_headers_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            headers.forEachIndexed { index, field ->
                val rejection = errorReason?.takeIf { errorIndex == index }
                if (stacked) {
                    StackedHeaderRow(
                        index = index,
                        field = field,
                        rejection = rejection,
                        revealed = index in revealed,
                        enabled = enabled,
                        onNameChange = onNameChange,
                        onValueChange = onValueChange,
                        onRemove = onRemove,
                        onToggleReveal = { revealed = revealed.toggle(index) },
                    )
                } else {
                    InlineHeaderRow(
                        index = index,
                        field = field,
                        rejection = rejection,
                        revealed = index in revealed,
                        enabled = enabled,
                        onNameChange = onNameChange,
                        onValueChange = onValueChange,
                        onRemove = onRemove,
                        onToggleReveal = { revealed = revealed.toggle(index) },
                    )
                }
                if (rejection != null) {
                    Text(
                        text = stringResource(rejection.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (stacked && index != headers.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }

            TextButton(
                onClick = onAdd,
                enabled = enabled,
                modifier = Modifier.align(Alignment.Start).testTag("server_header_add"),
            ) {
                Text(stringResource(Res.string.server_headers_add))
            }
        }
    }
}

/** Name and value side by side. Only used when there is genuinely room for both. */
@Composable
private fun InlineHeaderRow(
    index: Int,
    field: CustomHeaderRow,
    rejection: CustomHeaderRowError?,
    revealed: Boolean,
    enabled: Boolean,
    onNameChange: (Int, String) -> Unit,
    onValueChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onToggleReveal: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        HeaderNameField(
            index = index,
            name = field.name,
            rejection = rejection,
            enabled = enabled,
            onNameChange = onNameChange,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        HeaderValueField(
            index = index,
            value = field.value,
            rejection = rejection,
            revealed = revealed,
            enabled = enabled,
            onValueChange = onValueChange,
            onToggleReveal = onToggleReveal,
            modifier = Modifier.weight(1f),
        )
        RemoveHeaderButton(index = index, enabled = enabled, onRemove = onRemove)
    }
}

/**
 * Name above value, both the **same** full width, with remove as a text button underneath.
 *
 * Header names are the long part ("CF-Access-Client-Secret"), and two headers whose names truncate
 * to the same prefix are indistinguishable — so the name gets the full width rather than sharing it.
 *
 * The remove control sits below rather than beside the name for the same reason the fields stack: an
 * icon button next to one of the two fields makes that field narrower than the other, and a pair of
 * boxes that nearly-but-don't-quite line up reads as broken. Below, both fields are identical width
 * and the action gets a labelled tap target instead of a bare glyph.
 */
@Composable
private fun StackedHeaderRow(
    index: Int,
    field: CustomHeaderRow,
    rejection: CustomHeaderRowError?,
    revealed: Boolean,
    enabled: Boolean,
    onNameChange: (Int, String) -> Unit,
    onValueChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onToggleReveal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HeaderNameField(
            index = index,
            name = field.name,
            rejection = rejection,
            enabled = enabled,
            onNameChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        HeaderValueField(
            index = index,
            value = field.value,
            rejection = rejection,
            revealed = revealed,
            enabled = enabled,
            onValueChange = onValueChange,
            onToggleReveal = onToggleReveal,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { onRemove(index) },
            enabled = enabled,
            // Same tag as the icon-button variant: which layout is on screen depends on width, and a
            // test asserting "remove row 1" should not have to know which one it got.
            modifier = Modifier.align(Alignment.End).testTag("server_header_remove_$index"),
        ) {
            Text(stringResource(Res.string.server_headers_remove))
        }
    }
}

@Composable
private fun HeaderNameField(
    index: Int,
    name: String,
    rejection: CustomHeaderRowError?,
    enabled: Boolean,
    onNameChange: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = name,
        onValueChange = { onNameChange(index, it) },
        label = { Text(stringResource(Res.string.server_headers_name)) },
        modifier = modifier.testTag("server_header_name_$index"),
        singleLine = true,
        enabled = enabled,
        isError = rejection == CustomHeaderRowError.InvalidName ||
            rejection == CustomHeaderRowError.ReservedName,
    )
}

@Composable
private fun HeaderValueField(
    index: Int,
    value: String,
    rejection: CustomHeaderRowError?,
    revealed: Boolean,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(index, it) },
        label = { Text(stringResource(Res.string.server_headers_value)) },
        modifier = modifier.testTag("server_header_value_$index"),
        singleLine = true,
        enabled = enabled,
        isError = rejection == CustomHeaderRowError.InvalidValue,
        // A gateway token is a credential — masked by default so it stays out of screenshots and
        // accessibility dumps, as MCP server headers and provider API keys already are.
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        // Keeps the IME from learning, suggesting or autocorrecting the secret.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                onClick = onToggleReveal,
                enabled = enabled,
                modifier = Modifier.testTag("server_header_reveal_$index"),
            ) {
                Icon(
                    imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (revealed) {
                            Res.string.server_headers_hide_value
                        } else {
                            Res.string.server_headers_show_value
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun RemoveHeaderButton(index: Int, enabled: Boolean, onRemove: (Int) -> Unit) {
    IconButton(
        onClick = { onRemove(index) },
        enabled = enabled,
        modifier = Modifier.testTag("server_header_remove_$index"),
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(Res.string.server_headers_remove),
        )
    }
}

/** Reveal/hide one row, keeping the set immutable so the state write is a plain replacement. */
private fun Set<Int>.toggle(index: Int): Set<Int> =
    if (index in this) this - index else this + index

private fun CustomHeaderRowError.messageRes(): StringResource = when (this) {
    CustomHeaderRowError.InvalidName -> Res.string.server_headers_error_name
    CustomHeaderRowError.InvalidValue -> Res.string.server_headers_error_value
    CustomHeaderRowError.ReservedName -> Res.string.server_headers_error_reserved
}
