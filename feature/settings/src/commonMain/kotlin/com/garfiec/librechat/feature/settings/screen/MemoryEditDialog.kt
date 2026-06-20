package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

/** Add/edit memory dialog; key field is immutable when editing an existing memory. */
@Composable
internal fun MemoryEditDialog(
    editingMemory: Memory?,
    onDismiss: () -> Unit,
    onSave: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEditing = editingMemory != null
    var key by remember { mutableStateOf(editingMemory?.key ?: "") }
    var value by remember { mutableStateOf(editingMemory?.value ?: "") }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (isEditing) Res.string.edit_memory else Res.string.add_memory))
        },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(Res.string.memory_key_label)) },
                    singleLine = true,
                    enabled = !isEditing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(Res.string.memory_value_label)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(key.trim(), value.trim()) },
                enabled = key.isNotBlank() && value.isNotBlank(),
            ) {
                Text(stringResource(if (isEditing) Res.string.action_save else Res.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
