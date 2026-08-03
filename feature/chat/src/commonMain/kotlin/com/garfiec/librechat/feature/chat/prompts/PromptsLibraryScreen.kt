package com.garfiec.librechat.feature.chat.prompts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.core.ui.components.LibreChatTopBar
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.chat.prompts.components.PromptFilterSheet
import com.garfiec.librechat.feature.chat.prompts.components.PromptShareDialog
import com.garfiec.librechat.feature.chat.prompts.components.PromptSortOrder
import com.garfiec.librechat.feature.chat.prompts.components.VariableInputDialog
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PromptsLibraryScreen(
    onNavigateBack: () -> Unit,
    onUseInChat: (String) -> Unit,
    onNavigateToEditor: (groupId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PromptsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.dismissError()
        }
    }

    if (uiState.selectedGroup != null) {
        val selectedGroup = uiState.selectedGroup!!
        PromptDetailScreen(
            group = selectedGroup,
            onBack = viewModel::clearSelectedGroup,
            onDelete = { viewModel.deleteGroup(selectedGroup.id) },
            onUseInChat = { promptText ->
                // Only user-fillable variables warrant the dialog. A prompt whose only placeholders
                // are server-substituted specials ({{current_date}} and friends) goes straight
                // through — prompting for those would both freeze the value and show empty fields.
                if (hasFillableVariables(promptText)) {
                    viewModel.showVariableDialog(promptText)
                } else {
                    onUseInChat(promptText)
                }
            },
            onEdit = { groupId ->
                viewModel.clearSelectedGroup()
                onNavigateToEditor(groupId)
            },
            onShare = {
                viewModel.showShareDialog(selectedGroup.id)
            },
        )

        // Variable input dialog
        if (uiState.showVariableDialog) {
            VariableInputDialog(
                promptTemplate = uiState.variablePromptTemplate,
                variables = uiState.variableNames,
                onInsert = { interpolated, _ ->
                    viewModel.dismissVariableDialog()
                    onUseInChat(interpolated)
                },
                onDismiss = viewModel::dismissVariableDialog,
            )
        }

        // Share dialog
        if (uiState.showShareDialog) {
            PromptShareDialog(
                promptName = selectedGroup.name,
                isCurrentlyShared = false,
                onShareToggle = { _, _ -> viewModel.dismissShareDialog() },
                onDismiss = viewModel::dismissShareDialog,
            )
        }

        return
    }

    // Filter sheet
    if (uiState.showFilterSheet) {
        PromptFilterSheet(
            categories = uiState.availableCategories,
            selectedCategory = uiState.selectedCategory,
            onCategorySelect = viewModel::onCategorySelected,
            sortOrder = uiState.sortOrder,
            onSortOrderChange = viewModel::onSortOrderChanged,
            onDismiss = viewModel::dismissFilterSheet,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LibreChatTopBar(
                title = stringResource(Res.string.prompts_library),
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = viewModel::showFilterSheet) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = stringResource(Res.string.cd_filter_sort),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uiState.promptsCreateEnabled) {
                FloatingActionButton(onClick = { onNavigateToEditor(null) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.cd_create_prompt))
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Active filter chips
                val hasActiveFilters = uiState.selectedCategory != null ||
                    uiState.sortOrder != PromptSortOrder.RECENT
                if (hasActiveFilters) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val selectedCat = uiState.selectedCategory
                        if (selectedCat != null) {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.onCategorySelected(null) },
                                label = { Text(selectedCat) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(Res.string.cd_remove_filter),
                                        modifier = Modifier.width(16.dp),
                                    )
                                },
                            )
                        }
                        if (uiState.sortOrder != PromptSortOrder.RECENT) {
                            FilterChip(
                                selected = true,
                                onClick = {
                                    viewModel.onSortOrderChanged(PromptSortOrder.RECENT)
                                },
                                label = { Text(stringResource(Res.string.prompt_sort_label, uiState.sortOrder.label)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(Res.string.cd_reset_sort),
                                        modifier = Modifier.width(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                val displayGroups = uiState.filteredGroups.ifEmpty { uiState.groups }

                if (uiState.isLoading && displayGroups.isEmpty()) {
                    LoadingIndicator(modifier = Modifier.fillMaxSize())
                } else if (uiState.error != null && displayGroups.isEmpty()) {
                    ErrorBanner(
                        message = uiState.error ?: "Failed to load prompts",
                        onRetry = {
                            viewModel.dismissError()
                            viewModel.loadGroups()
                        },
                    )
                } else if (displayGroups.isEmpty()) {
                    EmptyState(
                        title = if (uiState.selectedCategory != null) {
                            "No prompts in this category"
                        } else {
                            "No prompts yet"
                        },
                        description = if (uiState.selectedCategory != null) {
                            "Try a different category or clear the filter"
                        } else {
                            "Tap + to create one"
                        },
                        icon = Icons.Default.Add,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 80.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(displayGroups, key = { it.id }, contentType = { "prompt_group" }) { group ->
                            PromptGroupItem(
                                group = group,
                                onClick = { viewModel.selectGroup(group.id) },
                                onShare = { viewModel.showShareDialog(group.id) },
                                showShareButton = uiState.promptsShareEnabled,
                            )
                        }
                    }
                }
            }
        }
    }

    // Share dialog on list view
    if (uiState.showShareDialog && uiState.selectedGroup == null) {
        val shareGroup = uiState.groups.firstOrNull { it.id == uiState.shareGroupId }
        PromptShareDialog(
            promptName = shareGroup?.name ?: "",
            isCurrentlyShared = false,
            onShareToggle = { _, _ -> viewModel.dismissShareDialog() },
            onDismiss = viewModel::dismissShareDialog,
        )
    }
}

@Composable
private fun PromptGroupItem(
    group: PromptGroupDisplayData,
    onClick: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    showShareButton: Boolean = true,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val oneliner = group.oneliner
                    if (!oneliner.isNullOrBlank()) {
                        Text(
                            text = oneliner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showShareButton) {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(Res.string.cd_share),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val promptText = group.promptText
            if (!promptText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = promptText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                val category = group.category
                if (!category.isNullOrBlank()) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(Res.string.prompt_author, group.authorName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
