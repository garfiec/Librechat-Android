package com.garfiec.librechat.feature.agents.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.HandoffEdge
import com.garfiec.librechat.feature.agents.AgentHandoffDisplayData
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.stringResource

/**
 * Handoffs graph editor. Saved as upstream `edges`. v0.8.5+ only.
 *
 * Multi-source / multi-dest edges (from/to as arrays) are preserved on load
 * but not editable in the dialog -- shown as "N agents → M agents".
 */
@Composable
fun AgentHandoffsSection(
    edges: List<HandoffEdge>,
    availableAgents: List<AgentHandoffDisplayData>,
    currentAgentId: String?,
    onAddEdge: (HandoffEdge) -> Unit,
    onUpdateEdge: (index: Int, edge: HandoffEdge) -> Unit,
    onRemoveEdge: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

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
                text = stringResource(Res.string.handoff_edges_count, edges.size),
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
                Text(
                    text = stringResource(Res.string.handoff_edges_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (edges.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.no_handoff_edges),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                edges.forEachIndexed { index, edge ->
                    HandoffEdgeRow(
                        edge = edge,
                        availableAgents = availableAgents,
                        onEdit = { editingIndex = index },
                        onRemove = { onRemoveEdge(index) },
                    )
                }

                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_handoff_edge))
                }
            }
        }
    }

    if (showAddDialog) {
        HandoffEdgeDialog(
            initial = HandoffEdge(
                from = JsonPrimitive(currentAgentId ?: ""),
                to = JsonPrimitive(""),
            ),
            availableAgents = availableAgents,
            onDismiss = { showAddDialog = false },
            onSave = { edge ->
                onAddEdge(edge)
                showAddDialog = false
            },
        )
    }

    editingIndex?.let { idx ->
        val edge = edges.getOrNull(idx)
        if (edge != null) {
            HandoffEdgeDialog(
                initial = edge,
                availableAgents = availableAgents,
                onDismiss = { editingIndex = null },
                onSave = { updated ->
                    onUpdateEdge(idx, updated)
                    editingIndex = null
                },
            )
        } else {
            editingIndex = null
        }
    }
}

@Composable
private fun HandoffEdgeRow(
    edge: HandoffEdge,
    availableAgents: List<AgentHandoffDisplayData>,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fromLabel = edge.from.singleAgentLabel(availableAgents)
    val toLabel = edge.to.singleAgentLabel(availableAgents)
    val summary = if (fromLabel != null && toLabel != null) {
        stringResource(Res.string.handoff_edge_arrow, fromLabel, toLabel)
    } else {
        stringResource(
            Res.string.handoff_edge_multi,
            edge.from.arrayCount(),
            edge.to.arrayCount(),
        )
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                val descriptionText = edge.description
                if (!descriptionText.isNullOrBlank()) {
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = null)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandoffEdgeDialog(
    initial: HandoffEdge,
    availableAgents: List<AgentHandoffDisplayData>,
    onDismiss: () -> Unit,
    onSave: (HandoffEdge) -> Unit,
) {
    // Editor only surfaces singleton from/to. If the loaded edge has arrays,
    // we preserve them via `originalFrom`/`originalTo` and skip editing them.
    val originalFrom = initial.from
    val originalTo = initial.to
    val initialFromId = (originalFrom as? JsonPrimitive)?.contentOrNull
    val initialToId = (originalTo as? JsonPrimitive)?.contentOrNull
    val canEditEndpoints = initialFromId != null && initialToId != null

    var fromId by remember { mutableStateOf(initialFromId ?: "") }
    var toId by remember { mutableStateOf(initialToId ?: "") }
    var description by remember { mutableStateOf(initial.description ?: "") }
    var prompt by remember { mutableStateOf(initial.prompt ?: "") }
    var promptKey by remember { mutableStateOf(initial.promptKey ?: "") }
    var excludeResults by remember { mutableStateOf(initial.excludeResults ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_handoff_edge)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canEditEndpoints) {
                    AgentPickerField(
                        label = stringResource(Res.string.handoff_edge_from),
                        selectedId = fromId,
                        availableAgents = availableAgents,
                        onSelect = { fromId = it },
                    )
                    AgentPickerField(
                        label = stringResource(Res.string.handoff_edge_to),
                        selectedId = toId,
                        availableAgents = availableAgents,
                        onSelect = { toId = it },
                    )
                } else {
                    Text(
                        text = stringResource(
                            Res.string.handoff_edge_multi,
                            originalFrom.arrayCount(),
                            originalTo.arrayCount(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(Res.string.handoff_edge_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(Res.string.handoff_edge_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = promptKey,
                    onValueChange = { promptKey = it },
                    label = { Text(stringResource(Res.string.handoff_edge_prompt_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = excludeResults,
                        onCheckedChange = { excludeResults = it },
                    )
                    Text(stringResource(Res.string.handoff_edge_exclude_results))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val nextFrom = if (canEditEndpoints) JsonPrimitive(fromId) else originalFrom
                    val nextTo = if (canEditEndpoints) JsonPrimitive(toId) else originalTo
                    onSave(
                        initial.copy(
                            from = nextFrom,
                            to = nextTo,
                            description = description.ifBlank { null },
                            prompt = prompt.ifBlank { null },
                            promptKey = promptKey.ifBlank { null },
                            excludeResults = excludeResults.takeIf { it },
                        ),
                    )
                },
                enabled = !canEditEndpoints || (fromId.isNotBlank() && toId.isNotBlank()),
            ) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentPickerField(
    label: String,
    selectedId: String,
    availableAgents: List<AgentHandoffDisplayData>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = availableAgents.find { it.id == selectedId }?.name ?: selectedId
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            availableAgents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        onSelect(agent.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun JsonElement.singleAgentLabel(
    availableAgents: List<AgentHandoffDisplayData>,
): String? {
    val id = (this as? JsonPrimitive)?.contentOrNull ?: return null
    return availableAgents.find { it.id == id }?.name ?: id
}

private fun JsonElement.arrayCount(): Int = when (this) {
    is JsonArray -> this.size
    is JsonPrimitive -> 1
    else -> 0
}
