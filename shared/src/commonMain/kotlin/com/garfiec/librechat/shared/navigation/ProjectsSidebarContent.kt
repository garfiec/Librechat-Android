package com.garfiec.librechat.shared.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.feature.conversations.components.ProjectActionsMenu
import com.garfiec.librechat.feature.conversations.components.ProjectDeleteDialog
import com.garfiec.librechat.feature.conversations.components.ProjectNameDialog
import com.garfiec.librechat.shared.resources.Res
import com.garfiec.librechat.shared.resources.cd_back_to_conversations
import com.garfiec.librechat.shared.resources.project_new
import com.garfiec.librechat.shared.resources.project_unassigned
import com.garfiec.librechat.shared.resources.projects
import com.garfiec.librechat.shared.resources.projects_all
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Projects "mode" of the sidebar — the carousel peer of the recents [DrawerContent]. Reached by
 * tapping the drawer's Projects entry, which swaps the recents list for this via [SidebarScaffold]'s
 * animated transition (and animates back on system back / the back arrow here). Folders open their
 * chats in the main content ([onOpenProject]); the full-page index ([onOpenProjectsIndex]) holds the
 * advanced controls. Shares the [NavHostViewModel] instance with the rest of the sidebar.
 */
@Composable
fun ProjectsSidebarContent(
    onBackToConversations: () -> Unit,
    onOpenProject: (projectId: String, projectName: String) -> Unit,
    onOpenProjectsIndex: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NavHostViewModel = koinViewModel(),
) {
    val folders by viewModel.projects.collectAsStateWithLifecycle()

    // Server is the source of truth for projects (no local cache), but the list lives in the shared
    // NavHostViewModel — load only when empty so re-entering this mode via the carousel reuses it
    // instead of refetching. CRUD and move-to-project actions refresh it themselves.
    LaunchedEffect(Unit) {
        if (viewModel.projects.value.isEmpty()) viewModel.loadProjects()
    }

    // Which folder's overflow menu is open, and the create/rename/delete dialog targets. Hoisted
    // here (single instance) so they outlive the per-row menu that requested them.
    var menuOpenId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatProject?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatProject?>(null) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackToConversations) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.cd_back_to_conversations),
                )
            }
            Text(
                text = stringResource(Res.string.projects),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            // Escape hatch to the full-page index for advanced controls (pagination, etc.).
            TextButton(onClick = onOpenProjectsIndex) {
                Text(
                    text = stringResource(Res.string.projects_all),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(Res.string.project_new),
                )
            }
        }

        Spacer(modifier = Modifier.padding(top = 4.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "project_unassigned") {
                val unassignedLabel = stringResource(Res.string.project_unassigned)
                ProjectFolderRow(
                    name = unassignedLabel,
                    conversationCount = null,
                    onClick = { onOpenProject(ChatProject.UNASSIGNED, unassignedLabel) },
                    menuContent = null,
                )
            }

            item(key = "projects_divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            items(items = folders, key = { it.id }) { folder ->
                ProjectFolderRow(
                    name = folder.name,
                    conversationCount = folder.conversationCount,
                    onClick = { onOpenProject(folder.id, folder.name) },
                    menuContent = {
                        IconButton(onClick = { menuOpenId = folder.id }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        ProjectActionsMenu(
                            expanded = menuOpenId == folder.id,
                            onDismiss = { menuOpenId = null },
                            onOpen = { onOpenProject(folder.id, folder.name) },
                            onRename = { renameTarget = folder },
                            onDelete = { deleteTarget = folder },
                        )
                    },
                )
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
private fun ProjectFolderRow(
    name: String,
    conversationCount: Int?,
    onClick: () -> Unit,
    menuContent: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (conversationCount != null) {
            Text(
                text = conversationCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (menuContent != null) {
            Box { menuContent() }
        }
    }
}
