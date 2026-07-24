package com.garfiec.librechat.feature.agents.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.EModelEndpoint
import com.garfiec.librechat.core.ui.components.AvatarImage
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.core.ui.components.endpointIconPainter
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.viewmodel.AgentDetailEvent
import com.garfiec.librechat.feature.agents.viewmodel.AgentDetailViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentDetailScreen(
    onBack: () -> Unit,
    onStartChat: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    modifier: Modifier = Modifier,
    agentId: String? = null,
    viewModel: AgentDetailViewModel = koinViewModel { parametersOf(agentId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnDuplicated by rememberUpdatedState(onDuplicate)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AgentDetailEvent.Deleted -> currentOnBack()
                is AgentDetailEvent.Duplicated -> currentOnDuplicated(event.agentId)
            }
        }
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text(stringResource(Res.string.delete_agent)) },
            text = {
                Text(stringResource(Res.string.delete_agent_confirm, uiState.agent?.name ?: stringResource(Res.string.delete_this_agent)))
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteAgent() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(uiState.agent?.name ?: stringResource(Res.string.agent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (uiState.agent != null) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(Res.string.cd_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (uiState.canEdit) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.edit)) },
                                    onClick = {
                                        menuExpanded = false
                                        uiState.agent?.let { onEdit(it.id) }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.duplicate)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.duplicateAgent()
                                },
                                enabled = !uiState.isDuplicating,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                    )
                                },
                            )
                            if (uiState.canEdit) {
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
                                    enabled = !uiState.isDeleting,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
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

            uiState.error != null -> {
                ErrorBanner(
                    message = uiState.error ?: stringResource(Res.string.error_unknown),
                    modifier = Modifier.padding(innerPadding),
                    onRetry = { viewModel.loadAgent() },
                )
            }

            uiState.agent != null -> {
                val agent = uiState.agent!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AvatarImage(
                        imageUrl = agent.avatarUrl,
                        size = 80.dp,
                        fallbackText = agent.name,
                        fallbackIconPainter = endpointIconPainter(EModelEndpoint.AGENTS),
                        tintIcon = true,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(
                            Res.string.by_author,
                            agent.authorName ?: agent.author
                                ?: stringResource(Res.string.unknown_author),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onStartChat(agent.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(Res.string.start_chat))
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    HorizontalDivider()

                    // Description section
                    AgentDetailSection(
                        label = stringResource(Res.string.label_description),
                        content = agent.description?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.no_description),
                        dimContent = agent.description.isNullOrBlank(),
                    )
                    HorizontalDivider()

                    // Category section
                    val category = agent.category
                    if (!category.isNullOrBlank() && category != "general") {
                        AgentDetailSection(
                            label = stringResource(Res.string.label_category),
                            content = category.replaceFirstChar { it.uppercase() },
                        )
                        HorizontalDivider()
                    }

                    // Model section
                    AgentDetailSection(
                        label = stringResource(Res.string.label_model),
                        content = agent.model ?: stringResource(Res.string.default_value),
                    )
                    HorizontalDivider()

                    // Tools section
                    val tools = agent.tools
                    if (!tools.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(Res.string.label_tools),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            tools.forEach { tool ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tool) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                    }

                    // Conversation Starters section
                    if (agent.conversationStarters.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(Res.string.label_conversation_starters),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        agent.conversationStarters.forEach { starter ->
                            Text(
                                text = "- $starter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AgentDetailSection(
    label: String,
    content: String,
    modifier: Modifier = Modifier,
    dimContent: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dimContent) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
