package com.garfiec.librechat.feature.agents.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Instructions field with an "Insert variable" overflow that inserts upstream's
 * special variables (`{{current_date}}`, `{{iso_datetime}}`, etc.) at the
 * current cursor position. Mirrors upstream `Instructions.tsx`.
 */
@Composable
internal fun InstructionsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track cursor + selection via TextFieldValue so the menu can insert at the caret.
    var fieldValue by remember(value) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = value,
                selection = androidx.compose.ui.text.TextRange(value.length),
            )
        )
    }
    // Sync VM value into local state when it changes from outside (e.g. agent load).
    if (fieldValue.text != value) {
        fieldValue = fieldValue.copy(text = value)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val variables = listOf(
        "current_date" to stringResource(Res.string.special_var_current_date),
        "iso_datetime" to stringResource(Res.string.special_var_iso_datetime),
        "current_datetime" to stringResource(Res.string.special_var_current_datetime),
        "current_user" to stringResource(Res.string.special_var_current_user),
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.agent_instructions_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.insert_variable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.cd_insert_variable),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    variables.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text("$label  ($key)") },
                            onClick = {
                                menuExpanded = false
                                val insertion = "{{$key}}"
                                val sel = fieldValue.selection
                                val newText = fieldValue.text.replaceRange(
                                    sel.min, sel.max, insertion,
                                )
                                val newCaret = sel.min + insertion.length
                                fieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(newCaret),
                                )
                                onValueChange(newText)
                            },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onValueChange(it.text)
            },
            placeholder = { Text(stringResource(Res.string.agent_instructions_placeholder)) },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SelectedToolRow(
    toolName: String,
    toolDescription: String?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolName,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!toolDescription.isNullOrBlank()) {
                Text(
                    text = toolDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.cd_remove_item, toolName),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
