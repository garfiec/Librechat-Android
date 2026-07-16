package com.garfiec.librechat.feature.conversations.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.resources.project_create
import com.garfiec.librechat.feature.conversations.resources.project_new_name
import com.garfiec.librechat.feature.conversations.resources.project_none
import com.garfiec.librechat.feature.conversations.resources.project_picker_title
import org.jetbrains.compose.resources.stringResource

/**
 * Single-select project assignment sheet (v0.8.7). Lists existing projects (the
 * current one checked), a "No project" row to unassign, and an inline field to
 * create a new project and assign in one step. Stateless — the caller supplies
 * [projects]/[currentProjectId] and handles [onSelect]/[onCreate].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPicker(
    projects: List<ChatProject>,
    currentProjectId: String?,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        dragHandle = { LowProfileDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.project_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            ProjectRow(
                label = stringResource(Res.string.project_none),
                selected = currentProjectId == null,
                onClick = { onSelect(null); onDismiss() },
            )

            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        label = project.name,
                        selected = project.id == currentProjectId,
                        onClick = { onSelect(project.id); onDismiss() },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.project_new_name)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                )
                IconButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            onCreate(name)
                            newName = ""
                            onDismiss()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.project_create),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
