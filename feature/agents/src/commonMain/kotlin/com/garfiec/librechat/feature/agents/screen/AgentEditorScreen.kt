package com.garfiec.librechat.feature.agents.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.agents.components.AgentActionsPanel
import com.garfiec.librechat.feature.agents.components.AgentAdvancedPanel
import com.garfiec.librechat.feature.agents.components.AgentAvatarPicker
import com.garfiec.librechat.feature.agents.components.AgentCategorySelector
import com.garfiec.librechat.feature.agents.components.AgentCodeInterpreterSection
import com.garfiec.librechat.feature.agents.components.AgentFileSearchSection
import com.garfiec.librechat.feature.agents.components.AgentHandoffConfig
import com.garfiec.librechat.feature.agents.components.AgentMcpToolsSelector
import com.garfiec.librechat.feature.agents.components.AgentModelPicker
import com.garfiec.librechat.feature.agents.components.AgentSharingSection
import com.garfiec.librechat.feature.agents.components.AgentSupportContactSection
import com.garfiec.librechat.feature.agents.components.AgentVersionHistory
import com.garfiec.librechat.feature.agents.components.ToolSelectDialog
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorEvent
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorScreen(
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    agentId: String? = null,
    onDelete: () -> Unit = onBack,
    onDuplicate: (String) -> Unit = onSave,
    viewModel: AgentEditorViewModel = koinViewModel { parametersOf(agentId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showToolDialog by rememberSaveable { mutableStateOf(false) }
    val currentOnSaved by rememberUpdatedState(onSave)
    val currentOnDuplicated by rememberUpdatedState(onDuplicate)
    val currentOnDeleted by rememberUpdatedState(onDelete)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AgentEditorEvent.SaveSuccess -> currentOnSaved(event.agentId)
                is AgentEditorEvent.DuplicateSuccess -> currentOnDuplicated(event.agentId)
                is AgentEditorEvent.DeleteSuccess -> currentOnDeleted()
            }
        }
    }

    // Delete confirmation dialog
    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(stringResource(Res.string.delete_agent)) },
            text = { Text(stringResource(Res.string.delete_agent_editor_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // Duplicate confirmation dialog
    if (uiState.showDuplicateConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicateConfirmation,
            title = { Text(stringResource(Res.string.duplicate_agent)) },
            text = { Text(stringResource(Res.string.duplicate_agent_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::duplicate) {
                    Text(stringResource(Res.string.duplicate))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDuplicateConfirmation) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // Version history sheet
    if (uiState.showVersionHistory) {
        AgentVersionHistory(
            versions = uiState.versions,
            onRevert = viewModel::revertToVersion,
            onDismiss = viewModel::dismissVersionHistory,
        )
    }

    // Tool selection dialog
    if (showToolDialog) {
        ToolSelectDialog(
            tools = uiState.availableTools,
            selectedToolIds = uiState.selectedTools,
            onToolAdd = viewModel::onToolAdded,
            onToolRemove = viewModel::onToolRemoved,
            onDismiss = { showToolDialog = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditMode) stringResource(Res.string.edit_agent) else stringResource(Res.string.create_agent))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (uiState.isEditMode) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(Res.string.cd_more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.duplicate)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.showDuplicateConfirmation()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.version_history)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.showVersionHistory()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = null,
                                    )
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(Res.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.showDeleteConfirmation()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                LoadingIndicator(modifier = Modifier.padding(innerPadding))
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    if (uiState.error != null) {
                        ErrorBanner(
                            message = uiState.error ?: stringResource(Res.string.error_unknown),
                            onRetry = { viewModel.dismissError() },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // ==========================================
                    // FIELD ORDER MATCHING WEB APP (AgentConfig):
                    // 1. Avatar
                    // 2. Name *
                    // 3. Description
                    // 4. Category *
                    // 5. Instructions
                    // 6. Model *
                    // 7. Capabilities (Code, Web Search, File Search)
                    // 8. MCP Tools
                    // 9. Tools & Actions
                    // 10. Support Contact
                    // 11. Conversation Starters (Android-specific, useful)
                    // 12. Advanced (collapsed)
                    // ==========================================

                    // 1. Avatar picker at top center
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AgentAvatarPicker(
                            avatarUrl = uiState.avatarUrl,
                            agentName = uiState.name,
                            onImageSelect = viewModel::uploadAvatar,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(Res.string.tap_to_change_avatar),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Name field (required)
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = viewModel::onNameChanged,
                        label = {
                            Text(stringResource(Res.string.agent_name_label))
                        },
                        placeholder = { Text(stringResource(Res.string.agent_name_placeholder)) },
                        isError = uiState.nameError != null,
                        supportingText = uiState.nameError?.let { error ->
                            { Text(error) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Description field
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = viewModel::onDescriptionChanged,
                        label = { Text(stringResource(Res.string.agent_description_label)) },
                        placeholder = { Text(stringResource(Res.string.agent_description_placeholder)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Category selector (required, defaults to "general")
                    AgentCategorySelector(
                        selectedCategory = uiState.category,
                        categories = uiState.categories,
                        onCategorySelect = viewModel::onCategoryChanged,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 5. Instructions field (multi-line)
                    OutlinedTextField(
                        value = uiState.instructions,
                        onValueChange = viewModel::onInstructionsChanged,
                        label = { Text(stringResource(Res.string.agent_instructions_label)) },
                        placeholder = { Text(stringResource(Res.string.agent_instructions_placeholder)) },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 6. Model picker (required)
                    AgentModelPicker(
                        selectedModel = uiState.model,
                        availableModels = uiState.availableModels,
                        onModelSelect = viewModel::onModelSelected,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 7. Capabilities section
                    Text(
                        text = stringResource(Res.string.label_capabilities),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Code Interpreter toggle (only shown when the server supports it)
                    if (uiState.isCodeInterpreterAvailable) {
                        AgentCodeInterpreterSection(
                            enabled = uiState.codeInterpreterEnabled,
                            onToggle = viewModel::onCodeInterpreterToggled,
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // File Search toggle
                    AgentFileSearchSection(
                        enabled = uiState.fileSearchEnabled,
                        onToggle = viewModel::onFileSearchToggled,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 8. MCP Tools selector
                    if (uiState.mcpTools.isNotEmpty()) {
                        AgentMcpToolsSelector(
                            mcpTools = uiState.mcpTools,
                            selectedToolNames = uiState.selectedMcpTools,
                            onToolToggle = viewModel::onMcpToolToggled,
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 9. Tools & Actions section
                    Text(
                        text = stringResource(Res.string.label_tools_and_actions),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Show selected tools as removable items with icon
                    if (uiState.selectedTools.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            uiState.selectedTools.forEach { toolId ->
                                val toolData = uiState.availableTools.find {
                                    (it.toolId ?: it.name) == toolId
                                }
                                SelectedToolRow(
                                    toolName = toolData?.name ?: toolId,
                                    toolDescription = toolData?.description,
                                    onRemove = { viewModel.onToolRemoved(toolId) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Show selected actions
                    if (uiState.actions.isNotEmpty()) {
                        uiState.actions.forEach { action ->
                            val actionId = action.actionId
                            if (actionId != null) {
                                val authLabel = when (action.authType) {
                                    "service_http" -> stringResource(Res.string.auth_api_key)
                                    "oauth" -> stringResource(Res.string.auth_oauth)
                                    else -> stringResource(Res.string.auth_none)
                                }
                                SelectedToolRow(
                                    toolName = action.domain ?: stringResource(Res.string.label_action),
                                    toolDescription = stringResource(Res.string.action_auth_info, authLabel, action.functionCount),
                                    onRemove = { viewModel.deleteAction(actionId) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Add Tools button
                    if (uiState.availableTools.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showToolDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(Res.string.add_tools))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Actions panel (collapsible with full editor)
                    AgentActionsPanel(
                        actions = uiState.actions,
                        onSaveAction = viewModel::saveAction,
                        onDeleteAction = viewModel::deleteAction,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 10. Support Contact
                    AgentSupportContactSection(
                        supportContact = uiState.supportContact,
                        onSupportContactChange = viewModel::onSupportContactChanged,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 11. Conversation starters
                    Text(
                        text = stringResource(Res.string.label_conversation_starters),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    uiState.conversationStarters.forEachIndexed { index, starter ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(starter) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.onConversationStarterRemoved(index) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(Res.string.remove),
                                    )
                                }
                            },
                        )
                    }

                    var newStarter by rememberSaveable { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = newStarter,
                            onValueChange = { newStarter = it },
                            placeholder = { Text(stringResource(Res.string.add_starter_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                viewModel.onConversationStarterAdded(newStarter)
                                newStarter = ""
                            },
                            enabled = newStarter.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(Res.string.cd_add_starter),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sharing & Permissions
                    AgentSharingSection(
                        sharingState = uiState.sharingState,
                        onSharingChange = viewModel::onSharingChanged,
                        showCollaborativeToggle = uiState.showCollaborativeToggle,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Handoff configuration
                    AgentHandoffConfig(
                        handoffAgentIds = uiState.handoffAgentIds,
                        availableAgents = uiState.allAgents,
                        onAddHandoff = viewModel::addHandoffAgent,
                        onRemoveHandoff = viewModel::removeHandoffAgent,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 12. Advanced settings (collapsible)
                    AgentAdvancedPanel(
                        settings = uiState.advancedSettings,
                        onSettingsChange = viewModel::onAdvancedSettingsChanged,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Save button
                    Button(
                        onClick = { viewModel.save() },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            if (uiState.isEditMode) stringResource(Res.string.save_changes) else stringResource(Res.string.create_agent),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Row showing a selected tool/action with its name, optional description, and a remove button.
 */
@Composable
private fun SelectedToolRow(
    toolName: String,
    toolDescription: String?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolName,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!toolDescription.isNullOrBlank()) {
                Text(
                    text = toolDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.cd_remove_item, toolName),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
