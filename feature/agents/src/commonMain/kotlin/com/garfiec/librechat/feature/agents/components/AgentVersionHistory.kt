package com.garfiec.librechat.feature.agents.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.agents.components.model.AgentVersion
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.resources.no_version_history
import com.garfiec.librechat.feature.agents.resources.revert
import com.garfiec.librechat.feature.agents.resources.version_current
import com.garfiec.librechat.feature.agents.resources.version_history
import com.garfiec.librechat.feature.agents.resources.version_no_date
import com.garfiec.librechat.feature.agents.resources.version_number
import com.garfiec.librechat.feature.agents.resources.version_snapshot_artifacts
import com.garfiec.librechat.feature.agents.resources.version_snapshot_capabilities
import com.garfiec.librechat.feature.agents.resources.version_snapshot_description
import com.garfiec.librechat.feature.agents.resources.version_snapshot_empty
import com.garfiec.librechat.feature.agents.resources.version_snapshot_instructions
import com.garfiec.librechat.feature.agents.resources.version_snapshot_name
import com.garfiec.librechat.feature.agents.resources.version_snapshot_tools
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentVersionHistory(
    versions: List<AgentVersion>,
    onRevert: (versionIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(Res.string.version_history),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (versions.isEmpty()) {
                Text(
                    text = stringResource(Res.string.no_version_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(versions, key = { it.versionIndex }) { version ->
                        VersionCard(
                            version = version,
                            onRevert = { onRevert(version.versionIndex) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VersionCard(
    version: AgentVersion,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.version_number, version.displayNumber),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (version.isActive) {
                            Spacer(Modifier.width(8.dp))
                            ActiveBadge()
                        }
                    }
                    Text(
                        text = version.updatedAt ?: stringResource(Res.string.version_no_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    SnapshotRow(
                        label = stringResource(Res.string.version_snapshot_name),
                        value = version.name,
                    )
                    SnapshotRow(
                        label = stringResource(Res.string.version_snapshot_description),
                        value = version.description,
                    )
                    SnapshotRow(
                        label = stringResource(Res.string.version_snapshot_instructions),
                        value = version.instructions,
                        maxLines = 6,
                    )
                    SnapshotRow(
                        label = stringResource(Res.string.version_snapshot_artifacts),
                        value = version.artifacts,
                    )
                    SnapshotRow(
                        label = stringResource(Res.string.version_snapshot_capabilities),
                        value = version.capabilities.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    )
                    SnapshotRow(
                        label = stringResource(Res.string.version_snapshot_tools),
                        value = version.tools.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    )
                    if (!version.isActive) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRevert) {
                            Text(stringResource(Res.string.revert))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(Res.string.version_current),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SnapshotRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    val empty = stringResource(Res.string.version_snapshot_empty)
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: empty,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
        )
    }
}
