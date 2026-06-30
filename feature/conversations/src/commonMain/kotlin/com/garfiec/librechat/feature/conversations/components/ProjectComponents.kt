package com.garfiec.librechat.feature.conversations.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.resources.cancel
import com.garfiec.librechat.feature.conversations.resources.delete
import com.garfiec.librechat.feature.conversations.resources.project_delete
import com.garfiec.librechat.feature.conversations.resources.project_delete_confirm
import com.garfiec.librechat.feature.conversations.resources.project_name_label
import com.garfiec.librechat.feature.conversations.resources.project_open
import com.garfiec.librechat.feature.conversations.resources.project_rename
import com.garfiec.librechat.feature.conversations.resources.save
import org.jetbrains.compose.resources.stringResource

/**
 * Shared Open/Rename/Delete overflow menu for a project, used by both the Projects index
 * rows and the drawer folder rows (v0.8.7).
 */
@Composable
fun ProjectActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.project_open)) },
            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
            onClick = { onDismiss(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.project_rename)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.project_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/**
 * Create/rename dialog for a project. [initialName] empty = create, non-empty = rename.
 */
@Composable
fun ProjectNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.project_name_label)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
fun ProjectDeleteDialog(
    projectName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.project_delete)) },
        text = { Text(stringResource(Res.string.project_delete_confirm, projectName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
