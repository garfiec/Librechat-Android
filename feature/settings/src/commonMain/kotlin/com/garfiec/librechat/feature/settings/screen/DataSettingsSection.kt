package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DataSettingsSection(
    archivedCount: Int,
    isClearing: Boolean,
    onClearAllChats: () -> Unit,
    onViewArchive: () -> Unit,
    onExportAllData: () -> Unit,
    logsBufferBytes: Long,
    isLogsExporting: Boolean,
    isLogsClearing: Boolean,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Clear all conversations
            Button(
                onClick = { showClearDialog = true },
                enabled = !isClearing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(if (isClearing) Res.string.clearing else Res.string.clear_all_conversations))
            }

            // Archived conversations
            OutlinedButton(
                onClick = onViewArchive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(Res.string.archived_conversations))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (archivedCount > 0) {
                            Text(
                                text = "$archivedCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Export all data
            OutlinedButton(
                onClick = onExportAllData,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.export_all_data))
            }

            // Export diagnostic logs (issue #96). Label includes the buffer size when known.
            OutlinedButton(
                onClick = onExportLogs,
                enabled = !isLogsExporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        isLogsExporting -> stringResource(Res.string.exporting_logs)
                        logsBufferBytes > 0 -> stringResource(
                            Res.string.export_diagnostic_logs_with_size,
                            formatBytes(logsBufferBytes),
                        )
                        else -> stringResource(Res.string.export_diagnostic_logs)
                    },
                )
            }

            // Clear diagnostic logs (destructive)
            OutlinedButton(
                onClick = { showClearLogsDialog = true },
                enabled = !isLogsClearing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(if (isLogsClearing) Res.string.clearing_logs else Res.string.clear_diagnostic_logs))
            }

            Spacer(modifier = Modifier.height(0.dp))
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        // Clear all chats confirmation dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(Res.string.dialog_title_clear_conversations)) },
                text = {
                    Text(
                        stringResource(Res.string.dialog_clear_conversations_message),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            onClearAllChats()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(Res.string.clear_all))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }

        // Clear diagnostic logs confirmation dialog
        if (showClearLogsDialog) {
            AlertDialog(
                onDismissRequest = { showClearLogsDialog = false },
                title = { Text(stringResource(Res.string.dialog_title_clear_logs)) },
                text = { Text(stringResource(Res.string.dialog_clear_logs_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearLogsDialog = false
                            onClearLogs()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(Res.string.clear_all))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearLogsDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
    } // Column
}

/**
 * Compact human-readable byte size (e.g. `123 KB`, `1.2 MB`) for the export-logs button label.
 * Uses binary (1024) units; one decimal place above KB.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    val rounded = (mb * 10).toLong() / 10.0
    return "$rounded MB"
}
