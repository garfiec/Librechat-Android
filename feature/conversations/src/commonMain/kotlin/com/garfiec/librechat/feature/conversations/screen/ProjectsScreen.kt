package com.garfiec.librechat.feature.conversations.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.feature.conversations.components.ProjectActionsMenu
import com.garfiec.librechat.feature.conversations.components.ProjectDeleteDialog
import com.garfiec.librechat.feature.conversations.components.ProjectNameDialog
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.resources.back
import com.garfiec.librechat.feature.conversations.resources.project_count
import com.garfiec.librechat.feature.conversations.resources.project_new
import com.garfiec.librechat.feature.conversations.resources.project_unassigned
import com.garfiec.librechat.feature.conversations.resources.projects_empty
import com.garfiec.librechat.feature.conversations.resources.projects_title
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectsEvent
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectsViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onProjectClick: (projectId: String, projectName: String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatProject?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatProject?>(null) }

    val unassignedLabel = stringResource(Res.string.project_unassigned)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProjectsEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3 && uiState.hasMore && !uiState.isLoading
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { shouldLoadMore }.collect { if (it) viewModel.loadMore() }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.projects_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(Res.string.project_new))
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // Unassigned virtual folder (read-side filter; no overflow/CRUD).
                item(key = "unassigned") {
                    ProjectRow(
                        name = unassignedLabel,
                        subtitle = null,
                        icon = Icons.Default.FolderOff,
                        onClick = { onProjectClick(ChatProject.UNASSIGNED, unassignedLabel) },
                        overflow = null,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                if (uiState.projects.isEmpty() && !uiState.isLoading) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(Res.string.projects_empty),
                            icon = Icons.Default.Folder,
                        )
                    }
                }

                items(uiState.projects, key = { it.id }, contentType = { "project" }) { project ->
                    var menuOpen by remember(project.id) { mutableStateOf(false) }
                    ProjectRow(
                        name = project.name,
                        subtitle = stringResource(Res.string.project_count, project.conversationCount),
                        icon = Icons.Default.Folder,
                        onClick = { onProjectClick(project.id, project.name) },
                        overflow = {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            ProjectActionsMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                onOpen = { onProjectClick(project.id, project.name) },
                                onRename = { renameTarget = project },
                                onDelete = { deleteTarget = project },
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                if (uiState.isLoading && uiState.projects.isNotEmpty()) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ProjectNameDialog(
            title = stringResource(Res.string.project_new),
            initialName = "",
            onConfirm = {
                viewModel.createProject(it)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { target ->
        ProjectNameDialog(
            title = target.name,
            initialName = target.name,
            onConfirm = {
                viewModel.renameProject(target.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        ProjectDeleteDialog(
            projectName = target.name,
            onConfirm = {
                viewModel.deleteProject(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ProjectRow(
    name: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    overflow: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (overflow != null) {
            Box { overflow() }
        }
    }
}
