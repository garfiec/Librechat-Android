package com.garfiec.librechat.feature.agents.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.ActionMetadata
import com.garfiec.librechat.core.model.request.FunctionTool
import com.garfiec.librechat.feature.agents.AgentActionDisplayData
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import org.jetbrains.compose.resources.stringResource

/** Collapsible CRUD panel for OpenAPI actions with full editor. */
@Composable
fun AgentActionsPanel(
    actions: List<AgentActionDisplayData>,
    onSaveAction: (actionId: String?, metadata: ActionMetadata, functions: List<FunctionTool>) -> Unit,
    onDeleteAction: (actionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var editingAction by remember { mutableStateOf<AgentActionDisplayData?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.openapi_actions_count, actions.size),
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(Res.string.cd_collapse) else stringResource(Res.string.cd_expand),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actions.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.no_actions_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                actions.forEach { action ->
                    val actionId = action.actionId ?: return@forEach
                    ActionCard(
                        action = action,
                        onEdit = {
                            editingAction = action
                            showEditor = true
                        },
                        onDelete = { onDeleteAction(actionId) },
                    )
                }

                OutlinedButton(
                    onClick = {
                        editingAction = null
                        showEditor = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_action))
                }
            }
        }
    }

    if (showEditor) {
        ActionEditorDialog(
            existingAction = editingAction,
            onDismiss = {
                showEditor = false
                editingAction = null
            },
            onSave = { actionId, metadata, functions ->
                onSaveAction(actionId, metadata, functions)
                showEditor = false
                editingAction = null
            },
        )
    }
}

@Composable
private fun ActionCard(
    action: AgentActionDisplayData,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.domain ?: stringResource(Res.string.unknown_author),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val authLabel = when (action.authType) {
                    "service_http" -> stringResource(Res.string.auth_api_key)
                    "oauth" -> stringResource(Res.string.auth_oauth)
                    else -> stringResource(Res.string.auth_none)
                }
                Text(
                    text = stringResource(Res.string.action_auth_info, authLabel, action.functionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(Res.string.cd_edit_action),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_action),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
