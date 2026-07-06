package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.feature.settings.platform.LogFileSaver
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.viewmodel.SettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToArchive: () -> Unit,
    onNavigateToSharedLinks: () -> Unit,
    onNavigateToArtifactShortcuts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_data)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        DataSettingsContent(
            onNavigateToArchive = onNavigateToArchive,
            onNavigateToSharedLinks = onNavigateToSharedLinks,
            onNavigateToArtifactShortcuts = onNavigateToArtifactShortcuts,
            snackbarHostState = snackbarHostState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * Reusable Data settings content (without Scaffold/TopAppBar).
 * Used by both the standalone screen and the tabbed settings screen.
 */
@Composable
fun DataSettingsContent(
    onNavigateToArchive: () -> Unit,
    onNavigateToSharedLinks: () -> Unit,
    onNavigateToArtifactShortcuts: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showRevokeKeysDialog by remember { mutableStateOf(false) }

    // Diagnostic-log export → platform file saver (issue #96)
    var pendingLogsFileName by remember { mutableStateOf<String?>(null) }
    var pendingLogsContent by remember { mutableStateOf<String?>(null) }
    var logsSaverResult by remember { mutableStateOf<String?>(null) }
    val logsExportedMsg = stringResource(Res.string.logs_exported)

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = error)
        viewModel.dismissError()
    }

    LaunchedEffect(uiState.showExportComingSoon) {
        if (uiState.showExportComingSoon) {
            snackbarHostState.showSnackbar(message = "Export is coming soon")
            viewModel.dismissExportComingSoon()
        }
    }

    // When the ViewModel finishes reading the buffer, hand the payload to the platform saver.
    LaunchedEffect(uiState.logsExportReady) {
        val payload = uiState.logsExportReady ?: return@LaunchedEffect
        pendingLogsContent = payload.content
        pendingLogsFileName = payload.fileName
        viewModel.consumeLogsExport()
    }

    LogFileSaver(
        triggerFileName = pendingLogsFileName,
        content = pendingLogsContent,
        onComplete = { success, errorMessage ->
            if (success) {
                logsSaverResult = logsExportedMsg
            } else if (errorMessage != null) {
                logsSaverResult = errorMessage
            }
        },
        onReset = {
            pendingLogsFileName = null
            pendingLogsContent = null
        },
    )

    LaunchedEffect(logsSaverResult) {
        val msg = logsSaverResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = msg)
        logsSaverResult = null
    }

    LaunchedEffect(uiState.mcpReinitializeMessage) {
        val message = uiState.mcpReinitializeMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMcpReinitializeMessage()
    }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Conversations section
            item(key = "conversations_header") {
                SectionHeader(stringResource(Res.string.section_conversations))
            }
            item(key = "data_settings") {
                DataSettingsSection(
                    archivedCount = uiState.archivedCount,
                    isClearing = uiState.isClearing,
                    onClearAllChats = viewModel::clearAllChats,
                    onViewArchive = onNavigateToArchive,
                    onExportAllData = viewModel::exportAllData,
                    logsBufferBytes = uiState.logsBufferBytes,
                    isLogsExporting = uiState.isLogsExporting,
                    isLogsClearing = uiState.isLogsClearing,
                    onExportLogs = viewModel::exportLogs,
                    onClearLogs = viewModel::clearLogs,
                )
            }
            item(key = "data_extra_actions") {
                DataExtraActions(
                    onSharedLinksClick = onNavigateToSharedLinks,
                    onArtifactShortcutsClick = onNavigateToArtifactShortcuts,
                    onClearCacheClick = { showClearCacheDialog = true },
                    isCacheClearing = uiState.isCacheClearing,
                    onRevokeKeysClick = { showRevokeKeysDialog = true },
                    isKeyRevoking = uiState.isKeyRevoking,
                )
            }

            // Memories section — hidden entirely when the server's MEMORIES.USE role
            // permission is denied. The user-level opt-out (`memoriesEnabled`) stays
            // independent and only hides the list inside this section.
            if (uiState.serverMemoriesEnabled) {
                item(key = "memories_header") {
                    SectionHeader(stringResource(Res.string.section_memories))
                }
                item(key = "memories_settings") {
                    MemoriesSettingsSection(
                        memories = uiState.memories,
                        memoriesEnabled = uiState.memoriesEnabled,
                        showMemoryDialog = uiState.showMemoryDialog,
                        editingMemory = uiState.editingMemory,
                        onToggleEnable = viewModel::toggleMemoriesEnabled,
                        onAddMemory = viewModel::showAddMemoryDialog,
                        onEditMemory = viewModel::showEditMemoryDialog,
                        onDeleteMemory = viewModel::deleteMemory,
                        onDismissDialog = viewModel::dismissMemoryDialog,
                        onSaveMemory = viewModel::saveMemory,
                    )
                }
            }

            // MCP section — always shown, but the section body degrades to
            // "not available" and the "+ Add" button disappears when role denies.
            item(key = "mcp_header") {
                SectionHeader(stringResource(Res.string.section_mcp_servers))
            }
            item(key = "mcp_settings") {
                McpSettingsSection(
                    servers = uiState.mcpServers,
                    connectionStatus = uiState.mcpConnectionStatus,
                    reinitializingServers = uiState.mcpReinitializingServers,
                    error = uiState.mcpError,
                    mcpServersEnabled = uiState.mcpServersEnabled,
                    mcpServersCreateEnabled = uiState.mcpServersCreateEnabled,
                    onAddServer = viewModel::showAddMcpServerDialog,
                    onEditServer = viewModel::showEditMcpServerDialog,
                    onDeleteServer = viewModel::deleteMcpServer,
                    onReinitialize = viewModel::reinitializeMcpServer,
                )
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        // MCP server add/edit dialog
        if (uiState.showMcpServerDialog) {
            McpServerDialog(
                editingServer = uiState.editingMcpServer,
                onDismiss = viewModel::dismissMcpServerDialog,
                onSave = { name, description, url, type, apiKey, oauth ->
                    viewModel.saveMcpServer(name, description, url, type, apiKey, oauth)
                },
            )
        }

        // Clear cache confirmation
        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text(stringResource(Res.string.dialog_title_clear_cache)) },
                text = { Text(stringResource(Res.string.dialog_clear_cache_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearCacheDialog = false
                            viewModel.clearCache()
                        },
                    ) {
                        Text(stringResource(Res.string.action_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }

        // Revoke keys confirmation
        if (showRevokeKeysDialog) {
            AlertDialog(
                onDismissRequest = { showRevokeKeysDialog = false },
                title = { Text(stringResource(Res.string.dialog_title_revoke_keys)) },
                text = { Text(stringResource(Res.string.dialog_revoke_keys_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRevokeKeysDialog = false
                            viewModel.revokeAllKeys()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(Res.string.action_revoke_all))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRevokeKeysDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }
    } // Column
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun DataExtraActions(
    onSharedLinksClick: () -> Unit,
    onArtifactShortcutsClick: () -> Unit,
    onClearCacheClick: () -> Unit,
    isCacheClearing: Boolean,
    onRevokeKeysClick: () -> Unit,
    isKeyRevoking: Boolean,
) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Shared Links
            OutlinedButton(
                onClick = onSharedLinksClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.shared_links))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Home-screen artifact shortcuts
            OutlinedButton(
                onClick = onArtifactShortcutsClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.artifact_shortcuts_title))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Clear cache
            OutlinedButton(
                onClick = onClearCacheClick,
                enabled = !isCacheClearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (isCacheClearing) Res.string.clearing else Res.string.clear_cache))
            }

            // Revoke API keys
            OutlinedButton(
                onClick = onRevokeKeysClick,
                enabled = !isKeyRevoking,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(if (isKeyRevoking) Res.string.revoking else Res.string.revoke_all_api_keys))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
