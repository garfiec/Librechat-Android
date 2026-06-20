package com.garfiec.librechat.feature.agents.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.feature.agents.components.AgentAclSharingSection
import com.garfiec.librechat.feature.agents.components.AgentActionsPanel
import com.garfiec.librechat.feature.agents.components.AgentAdvancedPanel
import com.garfiec.librechat.feature.agents.components.AgentAvatarPicker
import com.garfiec.librechat.feature.agents.components.AgentCapabilitiesSection
import com.garfiec.librechat.feature.agents.components.AgentCategorySelector
import com.garfiec.librechat.feature.agents.components.AgentChainSection
import com.garfiec.librechat.feature.agents.components.AgentCodeInterpreterSection
import com.garfiec.librechat.feature.agents.components.AgentFileAttachments
import com.garfiec.librechat.feature.agents.components.AgentFileContextSection
import com.garfiec.librechat.feature.agents.components.AgentFileSearchSection
import com.garfiec.librechat.feature.agents.components.AgentHandoffsSection
import com.garfiec.librechat.feature.agents.components.AgentMcpToolsSelector
import com.garfiec.librechat.feature.agents.components.AgentModelPicker
import com.garfiec.librechat.feature.agents.components.AgentSharingSection
import com.garfiec.librechat.feature.agents.components.AgentSkillsSection
import com.garfiec.librechat.feature.agents.components.AgentSubagentsSection
import com.garfiec.librechat.feature.agents.components.AgentSupportContactSection
import com.garfiec.librechat.feature.agents.components.AgentWebSearchSection
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.viewmodel.AgentAclViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorUiState
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentFileSlot
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The scrollable editor body. Field order mirrors the web app's AgentConfig:
 * avatar, name, description, category, instructions, model, capabilities,
 * MCP tools, tools & actions, support contact, conversation starters, sharing,
 * chain/handoffs, advanced, save. Each gated section renders only when its
 * server capability is present.
 */
@Composable
internal fun AgentEditorForm(
    uiState: AgentEditorUiState,
    viewModel: AgentEditorViewModel,
    onAddCodeFile: () -> Unit,
    onAddKnowledgeFile: () -> Unit,
    onAddContextFile: () -> Unit,
    onShowToolDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .imePadding()
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
            if (uiState.isEditMode && !uiState.avatarUrl.isNullOrBlank()) {
                TextButton(onClick = viewModel::resetAvatar) {
                    Text(stringResource(Res.string.remove_avatar))
                }
            }
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
            isError = uiState.descriptionError != null,
            supportingText = uiState.descriptionError?.let { error ->
                { Text(error) }
            },
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

        // 5. Instructions field (multi-line) + Insert-variable menu
        InstructionsField(
            value = uiState.instructions,
            onValueChange = viewModel::onInstructionsChanged,
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

        // 7. Capabilities section (Artifacts, EndAfterTools, HideSeq, RecursionLimit)
        AgentCapabilitiesSection(
            capabilities = uiState.capabilities,
            onCapabilitiesChange = viewModel::onCapabilitiesChanged,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Code Interpreter toggle (only shown when the server supports it)
        if (uiState.isCodeInterpreterAvailable) {
            AgentCodeInterpreterSection(
                enabled = uiState.codeInterpreterEnabled,
                onToggle = viewModel::onCodeInterpreterToggled,
            )
            if (uiState.codeInterpreterEnabled) {
                AgentFileAttachments(
                    files = uiState.codeFiles,
                    isUploading = AgentFileSlot.CODE in uiState.uploadingSlots,
                    onAddClick = onAddCodeFile,
                    onRemove = { id ->
                        viewModel.removeAgentFile(id, AgentFileSlot.CODE)
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Web Search toggle (only shown when the server has it configured)
        if (uiState.isWebSearchAvailable) {
            AgentWebSearchSection(
                enabled = uiState.webSearchEnabled,
                onToggle = viewModel::onWebSearchToggled,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Skills selector (gated on agents `skills` capability + SKILLS permission)
        if (uiState.isSkillsAvailable) {
            AgentSkillsSection(
                enabled = uiState.skillsEnabled,
                selectedSkillIds = uiState.selectedSkillIds,
                availableSkills = uiState.availableSkills,
                onToggle = viewModel::onSkillsToggled,
                onSkillToggle = viewModel::onSkillSelectionToggled,
                onSkillRemove = viewModel::onSkillRemoved,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Subagents config (gated on agents `subagents` capability)
        if (uiState.isSubagentsAvailable) {
            AgentSubagentsSection(
                enabled = uiState.subagentsEnabled,
                allowSelf = uiState.subagentAllowSelf,
                selectedSubagentIds = uiState.selectedSubagentIds,
                // Self can't be a listed subagent — it spawns itself via allowSelf.
                availableAgents = uiState.allAgents.filter { it.id != uiState.agentId },
                maxSubagents = AgentEditorViewModel.MAX_SUBAGENTS,
                onToggle = viewModel::onSubagentsToggled,
                onAllowSelfToggle = viewModel::onSubagentAllowSelfToggled,
                onAddSubagent = viewModel::addSubagent,
                onRemoveSubagent = viewModel::removeSubagent,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // File Search toggle
        AgentFileSearchSection(
            enabled = uiState.fileSearchEnabled,
            onToggle = viewModel::onFileSearchToggled,
        )
        if (uiState.fileSearchEnabled) {
            AgentFileAttachments(
                files = uiState.knowledgeFiles,
                isUploading = AgentFileSlot.KNOWLEDGE in uiState.uploadingSlots,
                onAddClick = onAddKnowledgeFile,
                onRemove = { id ->
                    viewModel.removeAgentFile(id, AgentFileSlot.KNOWLEDGE)
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // File Context toggle (persistent files attached as context)
        AgentFileContextSection(
            enabled = uiState.fileContextEnabled,
            onToggle = viewModel::onFileContextToggled,
        )
        if (uiState.fileContextEnabled) {
            AgentFileAttachments(
                files = uiState.contextFiles,
                isUploading = AgentFileSlot.CONTEXT in uiState.uploadingSlots,
                onAddClick = onAddContextFile,
                onRemove = { id ->
                    viewModel.removeAgentFile(id, AgentFileSlot.CONTEXT)
                },
            )
        }

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
                onClick = onShowToolDialog,
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
            nameError = uiState.supportContactNameError,
            emailError = uiState.supportContactEmailError,
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

        // Sharing & Permissions:
        // v0.8.5+ servers expose a granular ACL surface; older servers keep the
        // legacy Private / Team / Public + Collaborative toggle.
        // The ACL section is only meaningful for an existing agent (the API
        // operates on a specific agentId). In create-mode on v0.8.5+ we show
        // a notice instead of the legacy Private/Team/Public toggle — which
        // would silently no-op since v0.8.5+ dropped the projects/isCollaborative
        // model the toggle wrote to.
        when {
            uiState.isAclAvailable && uiState.agentId != null -> {
                val aclViewModel: AgentAclViewModel = koinViewModel()
                LaunchedEffect(uiState.agentId) {
                    aclViewModel.load(uiState.agentId!!)
                }
                AgentAclSharingSection(viewModel = aclViewModel)
            }
            uiState.isAclAvailable && uiState.agentId == null -> {
                Text(
                    text = stringResource(Res.string.acl_create_mode_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                AgentSharingSection(
                    sharingState = uiState.sharingState,
                    onSharingChange = viewModel::onSharingChanged,
                    showCollaborativeToggle = uiState.showCollaborativeToggle,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chain (sequential multi-agent), gated on agents-endpoint capabilities
        if (uiState.isChainAvailable) {
            AgentChainSection(
                chainAgentIds = uiState.chainAgentIds,
                availableAgents = uiState.allAgents,
                chainMax = AgentEditorViewModel.CHAIN_MAX,
                onAddAgent = viewModel::addChainAgent,
                onRemoveAgent = viewModel::removeChainAgent,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Handoffs graph editor, v0.8.5+ only
        if (uiState.isHandoffsAvailable) {
            AgentHandoffsSection(
                edges = uiState.handoffEdges,
                availableAgents = uiState.allAgents,
                currentAgentId = uiState.agentId,
                onAddEdge = viewModel::addHandoffEdge,
                onUpdateEdge = viewModel::updateHandoffEdge,
                onRemoveEdge = viewModel::removeHandoffEdge,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 12. Advanced settings (collapsible)
        AgentAdvancedPanel(
            settings = uiState.advancedSettings,
            onSettingsChange = viewModel::onAdvancedSettingsChanged,
            provider = uiState.provider,
            model = uiState.model,
            extendedEffortSupported = uiState.isHandoffsAvailable,
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
