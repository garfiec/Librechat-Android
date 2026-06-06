package com.garfiec.librechat.feature.agents.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.garfiec.librechat.feature.agents.AgentHandoffDisplayData
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Agent subagents config (v0.8.6). Master `enabled` Switch + an `allowSelf`
 * Switch + an `agent_ids` multi-select (cap [maxSubagents], excludes the agent
 * itself). Mirrors upstream
 * `client/src/components/SidePanel/Agents/Advanced/AgentSubagents.tsx` and the
 * existing chain-agent picker. Gated on the `subagents` capability by the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSubagentsSection(
    enabled: Boolean,
    allowSelf: Boolean,
    selectedSubagentIds: List<String>,
    availableAgents: List<AgentHandoffDisplayData>,
    maxSubagents: Int,
    onToggle: (Boolean) -> Unit,
    onAllowSelfToggle: (Boolean) -> Unit,
    onAddSubagent: (agentId: String) -> Unit,
    onRemoveSubagent: (agentId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val nameOf: (String) -> String = { id ->
        availableAgents.firstOrNull { it.id == id }?.name ?: id
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.label_subagents),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.subagents_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            if (enabled) {
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.subagents_allow_self),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = allowSelf, onCheckedChange = onAllowSelfToggle)
                }

                if (selectedSubagentIds.isNotEmpty()) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selectedSubagentIds.forEach { id ->
                            val label = nameOf(id)
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(label) },
                                trailingIcon = {
                                    IconButton(onClick = { onRemoveSubagent(id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(Res.string.cd_remove_item, label),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                val atCap = selectedSubagentIds.size >= maxSubagents
                if (atCap) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = stringResource(Res.string.subagents_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.padding(top = 4.dp))
                OutlinedButton(
                    onClick = { showPicker = true },
                    enabled = !atCap,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_subagent))
                }
            }
        }
    }

    if (showPicker) {
        // Exclude already-selected agents (self is excluded by the caller's
        // availableAgents construction / addSubagent guard).
        AddSubagentDialog(
            availableAgents = availableAgents.filter { it.id !in selectedSubagentIds },
            onDismiss = { showPicker = false },
            onSelect = { agentId ->
                onAddSubagent(agentId)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSubagentDialog(
    availableAgents: List<AgentHandoffDisplayData>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedAgent by remember { mutableStateOf<AgentHandoffDisplayData?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_subagent)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (availableAgents.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.no_more_agents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = selectedAgent?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Res.string.select_agent)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                        ) {
                            availableAgents.forEach { agent ->
                                DropdownMenuItem(
                                    text = { Text(agent.name) },
                                    onClick = {
                                        selectedAgent = agent
                                        dropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedAgent?.let { onSelect(it.id) } },
                enabled = selectedAgent != null,
            ) {
                Text(stringResource(Res.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
