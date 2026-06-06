package com.garfiec.librechat.feature.agents.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Agent skills selector (v0.8.6). Master `skills_enabled` toggle + a removable
 * chip per selected skill + "Add skills" picker. Mirrors upstream
 * `client/src/components/SidePanel/Agents/AgentConfig.tsx` (skills block).
 *
 * Empty allowlist + enabled = "full catalog" (the [hint] reflects this) — the
 * caller must NOT auto-clear [enabled] when the chip list is emptied.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSkillsSection(
    enabled: Boolean,
    selectedSkillIds: List<String>,
    availableSkills: List<SkillSummary>,
    onToggle: (Boolean) -> Unit,
    onSkillToggle: (skillId: String) -> Unit,
    onSkillRemove: (skillId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    // _id -> display label map for resolving chips. Saved-but-unresolvable ids
    // (denied/empty catalog) fall back to the raw id so they never silently
    // drop or crash.
    val labelOf: (String) -> String = { id ->
        availableSkills.firstOrNull { it.id == id }?.skillLabel() ?: id
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
                Text(
                    text = stringResource(Res.string.label_skills),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            val hint = when {
                !enabled -> Res.string.skills_disabled_hint
                selectedSkillIds.isEmpty() -> Res.string.skills_enabled_all_hint
                else -> Res.string.skills_enabled_allowlist_hint
            }
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (enabled) {
                if (selectedSkillIds.isNotEmpty()) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selectedSkillIds.forEach { id ->
                            val label = labelOf(id)
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(label) },
                                trailingIcon = {
                                    IconButton(onClick = { onSkillRemove(id) }) {
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

                Spacer(modifier = Modifier.padding(top = 4.dp))
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_skills))
                }
            }
        }
    }

    if (showPicker) {
        SkillSelectDialog(
            availableSkills = availableSkills,
            selectedSkillIds = selectedSkillIds.toSet(),
            onToggle = onSkillToggle,
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * Searchable multi-select picker over the skill catalog. Tapping a row toggles
 * the id in the agent's allowlist (matching upstream `SkillSelectDialog`).
 */
@Composable
private fun SkillSelectDialog(
    availableSkills: List<SkillSummary>,
    selectedSkillIds: Set<String>,
    onToggle: (skillId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, availableSkills) {
        if (query.isBlank()) {
            availableSkills
        } else {
            availableSkills.filter { skill ->
                skill.name.contains(query, ignoreCase = true) ||
                    skill.displayTitle?.contains(query, ignoreCase = true) == true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.select_skills)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.search_skills_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                when {
                    availableSkills.isEmpty() -> {
                        Text(
                            text = stringResource(Res.string.no_skills_available),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    filtered.isEmpty() -> {
                        Text(
                            text = stringResource(Res.string.no_skills_matching, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        ) {
                            items(filtered, key = { it.id }) { skill ->
                                SkillRow(
                                    skill = skill,
                                    checked = skill.id in selectedSkillIds,
                                    onToggle = { onToggle(skill.id) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.skills_done))
            }
        },
    )
}

@Composable
private fun SkillRow(
    skill: SkillSummary,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.skillLabel(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val description = skill.description
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun SkillSummary.skillLabel(): String =
    displayTitle?.takeIf { it.isNotBlank() } ?: name
