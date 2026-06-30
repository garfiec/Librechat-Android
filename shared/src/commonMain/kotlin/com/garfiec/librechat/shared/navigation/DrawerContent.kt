package com.garfiec.librechat.shared.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.ChatProject
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.ui.components.EndpointIcon
import com.garfiec.librechat.feature.conversations.components.ConversationActionDialogs
import com.garfiec.librechat.feature.conversations.components.ConversationActionEffects
import com.garfiec.librechat.feature.conversations.components.ConversationActionsMenu
import com.garfiec.librechat.feature.conversations.components.ProjectActionsMenu
import com.garfiec.librechat.feature.conversations.components.ProjectDeleteDialog
import com.garfiec.librechat.feature.conversations.components.ProjectNameDialog
import com.garfiec.librechat.feature.conversations.components.ProjectPicker
import com.garfiec.librechat.feature.conversations.components.TagPicker
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.export.ExportFormatPicker
import com.garfiec.librechat.shared.resources.Res
import com.garfiec.librechat.shared.resources.agents
import com.garfiec.librechat.shared.resources.bookmark
import com.garfiec.librechat.shared.resources.cd_clear_search
import com.garfiec.librechat.shared.resources.cd_conversation_actions
import com.garfiec.librechat.shared.resources.cd_search
import com.garfiec.librechat.shared.resources.favorites
import com.garfiec.librechat.shared.resources.files
import com.garfiec.librechat.shared.resources.new_chat
import com.garfiec.librechat.shared.resources.no_conversations_found
import com.garfiec.librechat.shared.resources.pinned
import com.garfiec.librechat.shared.resources.project_new
import com.garfiec.librechat.shared.resources.project_show_all
import com.garfiec.librechat.shared.resources.project_unassigned
import com.garfiec.librechat.shared.resources.projects
import com.garfiec.librechat.shared.resources.projects_all
import com.garfiec.librechat.shared.resources.remove_bookmark
import com.garfiec.librechat.shared.resources.search_conversations_placeholder
import com.garfiec.librechat.shared.resources.settings
import com.garfiec.librechat.shared.resources.skills
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Pre-computed shapes to avoid creating new ones per item per frame
private val ItemShape = RoundedCornerShape(8.dp)
private val ActiveIndicatorShape = RoundedCornerShape(2.dp)

// Gap between the conversation row's bottom edge and the long-press menu's top edge.
private val MenuVerticalGap = 4.dp

/**
 * Stateful DrawerContent that collects its own state from the ViewModel.
 */
@Composable
fun DrawerContent(
    onNewChat: () -> Unit,
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAgentsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onSkillsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenProject: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onOpenProjectsIndex: () -> Unit = {},
    viewModel: NavHostViewModel = koinViewModel(),
) {
    val uiState by viewModel.drawerUiState.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectsSection by viewModel.projectsSection.collectAsStateWithLifecycle()
    val projectsSectionExpanded by viewModel.projectsSectionExpanded.collectAsStateWithLifecycle()

    // Side-effects for the long-press action menu (share-link copy, export file-save, navigate to
    // a duplicated conversation, error toasts). Lives in feature/conversations so it owns the
    // clipboard/toast/file-save plumbing and its localized strings.
    ConversationActionEffects(
        events = viewModel.events,
        onNavigateToConversation = onConversationClick,
    )

    DrawerContent(
        uiState = uiState,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onNewChat = onNewChat,
        onConversationClick = onConversationClick,
        onSettingsClick = onSettingsClick,
        onAgentsClick = onAgentsClick,
        onFilesClick = onFilesClick,
        onSkillsClick = onSkillsClick,
        onToggleFavorite = { data -> viewModel.toggleFavorite(data.conversationId, data.tags) },
        onRefresh = viewModel::refreshConversations,
        onLoadMore = viewModel::loadMoreConversations,
        onRename = { id, newTitle -> viewModel.renameConversation(id, newTitle) },
        onArchive = viewModel::archiveConversation,
        onDelete = viewModel::deleteConversation,
        onPin = viewModel::pinConversation,
        projects = projects,
        onLoadProjects = viewModel::loadProjects,
        onMoveToProject = viewModel::moveConversationToProject,
        onCreateProjectAndAssign = viewModel::createProjectAndAssign,
        projectsSection = projectsSection,
        projectsSectionExpanded = projectsSectionExpanded,
        onToggleProjectsSection = viewModel::toggleProjectsSection,
        onToggleProjectExpansion = viewModel::toggleProjectExpanded,
        onCreateProject = viewModel::createProject,
        onRenameProject = viewModel::renameProject,
        onDeleteProject = viewModel::deleteProject,
        onOpenProject = onOpenProject,
        onOpenProjectsIndex = onOpenProjectsIndex,
        onShare = viewModel::shareConversation,
        onDuplicate = { id, title -> viewModel.duplicateConversation(id, title) },
        onUpdateTags = { data, tags -> viewModel.updateConversationTags(data.conversationId, data.tags, tags) },
        onExportFormat = { data, format -> viewModel.exportConversation(data.conversationId, data.title, format) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerContent(
    uiState: DrawerUiState,
    onSearchQueryChange: (String) -> Unit,
    onNewChat: () -> Unit,
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAgentsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onSkillsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (DrawerConversationDisplayData) -> Unit = {},
    onRefresh: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onArchive: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onPin: (String, Boolean) -> Unit = { _, _ -> },
    projects: List<ChatProject> = emptyList(),
    onLoadProjects: () -> Unit = {},
    onMoveToProject: (String, String?) -> Unit = { _, _ -> },
    onCreateProjectAndAssign: (String, String) -> Unit = { _, _ -> },
    projectsSection: List<DrawerProjectFolder> = emptyList(),
    projectsSectionExpanded: Boolean = true,
    onToggleProjectsSection: () -> Unit = {},
    onToggleProjectExpansion: (String) -> Unit = {},
    onCreateProject: (String) -> Unit = {},
    onRenameProject: (String, String) -> Unit = { _, _ -> },
    onDeleteProject: (String) -> Unit = {},
    onOpenProject: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onOpenProjectsIndex: () -> Unit = {},
    onShare: (String) -> Unit = {},
    onDuplicate: (String, String) -> Unit = { _, _ -> },
    onUpdateTags: (DrawerConversationDisplayData, List<String>) -> Unit = { _, _ -> },
    onExportFormat: (DrawerConversationDisplayData, ExportFormat) -> Unit = { _, _ -> },
) {
    // Which row's long-press action menu is currently open (null = none). Keyed by the row's
    // LazyColumn key, not the conversation id: a favorited conversation appears in BOTH the
    // favorites section and its date group, so an id-keyed menu would open in both rows at once.
    var menuRowKey by remember { mutableStateOf<String?>(null) }
    // Targets for the dialogs/pickers opened from the action menu. Hoisted here (single instance)
    // so they survive the per-row menu — which is composed only for the open row — leaving the tree.
    var renameTarget by remember { mutableStateOf<DrawerConversationDisplayData?>(null) }
    var deleteTarget by remember { mutableStateOf<DrawerConversationDisplayData?>(null) }
    var tagPickerTarget by remember { mutableStateOf<DrawerConversationDisplayData?>(null) }
    var exportPickerTarget by remember { mutableStateOf<DrawerConversationDisplayData?>(null) }
    var projectPickerTarget by remember { mutableStateOf<DrawerConversationDisplayData?>(null) }
    // Projects folder-section state (v0.8.7): which folder's overflow menu is open, and the
    // create/rename/delete dialog targets.
    var projectMenuOpenId by remember { mutableStateOf<String?>(null) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var renameProjectTarget by remember { mutableStateOf<DrawerProjectFolder?>(null) }
    var deleteProjectTarget by remember { mutableStateOf<DrawerProjectFolder?>(null) }

    // Invisible anchor that claims initial focus so the search field below
    // doesn't auto-focus and pop the keyboard when the drawer opens. Tapping
    // the search field still focuses it normally. See Android focus docs
    // ("Change focus behavior"): redirect initial focus to a non-input element.
    val focusAnchor = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusAnchor.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .statusBarsPadding()
            .padding(top = 16.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusAnchor)
                .focusable(),
        )

        // "New Chat" button at top
        Surface(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = ItemShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.new_chat),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search bar
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(Res.string.cd_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_clear_search),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            placeholder = {
                Text(
                    text = stringResource(Res.string.search_conversations_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            singleLine = true,
            shape = ItemShape,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Conversation list with favorites section and date groups
        val listState = rememberLazyListState()
        val currentOnLoadMore by rememberUpdatedState(onLoadMore)

        // Single item renderer shared by the favorites section and the date groups so the
        // long-press action menu wiring isn't duplicated across both call sites. rowKey is the
        // row's LazyColumn key (unique per rendered row, unlike the conversation id).
        val renderConversationItem: @Composable (String, DrawerConversationDisplayData) -> Unit = { rowKey, data ->
            DrawerConversationItem(
                data = data,
                onClick = { onConversationClick(data.conversationId) },
                onToggleFavorite = { onToggleFavorite(data) },
                showBookmarkToggle = uiState.bookmarksEnabled,
                onLongPress = { menuRowKey = rowKey },
                menuContent = { menuOffset ->
                    // Only the open row materializes the menu, so there's one menu in the tree at
                    // a time (and the dialogs it triggers are hoisted below, outside this row).
                    if (menuRowKey == rowKey) {
                        ConversationActionsMenu(
                            expanded = true,
                            onDismiss = { menuRowKey = null },
                            title = data.title,
                            offset = menuOffset,
                            isBookmarked = data.isFavorite,
                            bookmarksEnabled = uiState.bookmarksEnabled,
                            isPinned = data.isPinned,
                            showPinAction = uiState.pinEnabled,
                            onPinToggle = { onPin(data.conversationId, !data.isPinned) },
                            showMoveToProject = uiState.projectsEnabled,
                            onMoveToProject = {
                                onLoadProjects()
                                projectPickerTarget = data
                            },
                            // Share is shown here when the server enables shared links; the
                            // full-screen list intentionally omits it (passes showShareAction=false).
                            showShareAction = uiState.sharedLinksEnabled,
                            onBookmarkToggle = { onToggleFavorite(data) },
                            onRenameRequest = { renameTarget = data },
                            onArchive = { onArchive(data.conversationId) },
                            onDeleteRequest = { deleteTarget = data },
                            onShare = { onShare(data.conversationId) },
                            onDuplicate = { newTitle -> onDuplicate(data.conversationId, newTitle) },
                            onTags = { tagPickerTarget = data },
                            onExport = { exportPickerTarget = data },
                        )
                    }
                },
            )
        }

        val shouldLoadMore = remember {
            derivedStateOf {
                val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                lastVisibleItem >= totalItems - 8 && totalItems > 0
            }
        }

        LaunchedEffect(shouldLoadMore.value) {
            if (shouldLoadMore.value && uiState.hasMore && !uiState.isLoadingMore) {
                currentOnLoadMore()
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Projects folder section (v0.8.7) — expandable folders with inline chats.
                if (uiState.projectsEnabled && uiState.searchQuery.isEmpty()) {
                    item(key = "projects_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tapping the title area collapses/expands the whole section so it's
                            // easy to get the projects out of the way and reach plain chats below.
                            val sectionChevronRotation by animateFloatAsState(
                                if (projectsSectionExpanded) 90f else 0f,
                                label = "projectsSectionChevron",
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClick = onToggleProjectsSection),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).rotate(sectionChevronRotation),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(Res.string.projects),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            TextButton(onClick = onOpenProjectsIndex) {
                                Text(
                                    text = stringResource(Res.string.projects_all),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            IconButton(onClick = { showCreateProjectDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(Res.string.project_new),
                                )
                            }
                        }
                    }

                    if (projectsSectionExpanded) {
                    item(key = "project_unassigned") {
                        val unassignedLabel = stringResource(Res.string.project_unassigned)
                        DrawerProjectFolderRow(
                            name = unassignedLabel,
                            conversationCount = null,
                            isExpanded = false,
                            showChevron = false,
                            onRowClick = { onOpenProject(ChatProject.UNASSIGNED, unassignedLabel) },
                            menuContent = null,
                        )
                    }

                    projectsSection.forEach { folder ->
                        item(key = "project_${folder.id}") {
                            DrawerProjectFolderRow(
                                name = folder.name,
                                conversationCount = folder.conversationCount,
                                isExpanded = folder.isExpanded,
                                showChevron = true,
                                onRowClick = { onToggleProjectExpansion(folder.id) },
                                menuContent = {
                                    IconButton(onClick = { projectMenuOpenId = folder.id }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = null)
                                    }
                                    ProjectActionsMenu(
                                        expanded = projectMenuOpenId == folder.id,
                                        onDismiss = { projectMenuOpenId = null },
                                        onOpen = { onOpenProject(folder.id, folder.name) },
                                        onRename = { renameProjectTarget = folder },
                                        onDelete = { deleteProjectTarget = folder },
                                    )
                                },
                            )
                        }
                        if (folder.isExpanded) {
                            items(
                                items = folder.inlineChats,
                                key = { "projchat_${folder.id}_${it.conversationId}" },
                                contentType = { "conversation" },
                            ) { data ->
                                renderConversationItem("projchat_${folder.id}_${data.conversationId}", data)
                            }
                            item(key = "project_showall_${folder.id}") {
                                Text(
                                    text = stringResource(Res.string.project_show_all),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenProject(folder.id, folder.name) }
                                        .padding(start = 48.dp, top = 8.dp, bottom = 8.dp),
                                )
                            }
                        }
                    }
                    } // projectsSectionExpanded

                    item(key = "projects_divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }

                // Pinned section (v0.8.7) — pinned conversations surfaced above favorites. This is
                // their canonical home: when shown, they're filtered out of the date-grouped buckets
                // (see ConversationListStateHolder.withoutPinned) so they don't appear twice. The
                // section is hidden during search, where pinned rows instead surface in the results.
                if (uiState.pinnedConversations.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    item(key = "pinned_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 4.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(Res.string.pinned),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                    }

                    items(
                        items = uiState.pinnedConversations,
                        key = { "pin_${it.conversationId}" },
                        contentType = { "conversation" },
                    ) { data ->
                        renderConversationItem("pin_${data.conversationId}", data)
                    }

                    item(key = "pinned_divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }

                // Favorites section — hidden entirely when BOOKMARKS.USE is denied so
                // any locally-cached favorites from a prior permissive session don't leak.
                if (uiState.bookmarksEnabled && uiState.favoriteConversations.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    item(key = "favorites_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 4.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(Res.string.favorites),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                    }

                    items(
                        items = uiState.favoriteConversations,
                        key = { "fav_${it.conversationId}" },
                        contentType = { "conversation" },
                    ) { data ->
                        renderConversationItem("fav_${data.conversationId}", data)
                    }

                    item(key = "favorites_divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }

                if (uiState.groupedConversations.isEmpty() && uiState.searchQuery.isNotEmpty()) {
                    item(key = "empty_search") {
                        Text(
                            text = stringResource(Res.string.no_conversations_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                }

                uiState.groupedConversations.forEach { (dateGroup, displayItems) ->
                    item(key = "header_$dateGroup") {
                        Text(
                            text = dateGroup,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }

                    items(
                        items = displayItems,
                        key = { it.conversationId },
                        contentType = { "conversation" },
                    ) { data ->
                        renderConversationItem(data.conversationId, data)
                    }
                }

                if (uiState.isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }

        // Bottom section: divider + footer links
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        if (uiState.agentsEnabled) {
            DrawerFooterItem(
                icon = Icons.Default.SmartToy,
                label = stringResource(Res.string.agents),
                onClick = onAgentsClick,
            )
        }
        if (uiState.skillsEnabled) {
            DrawerFooterItem(
                icon = Icons.Default.Extension,
                label = stringResource(Res.string.skills),
                onClick = onSkillsClick,
            )
        }
        DrawerFooterItem(
            icon = Icons.Default.Folder,
            label = stringResource(Res.string.files),
            onClick = onFilesClick,
        )
        DrawerFooterItem(
            icon = Icons.Default.Settings,
            label = stringResource(Res.string.settings),
            onClick = onSettingsClick,
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    // Rename/Delete confirmation dialogs for the long-press action menu. Single hoisted instance
    // (a non-null target shows the dialog) so it outlives the per-row menu that requested it.
    ConversationActionDialogs(
        renameTitle = renameTarget?.title,
        deleteTitle = deleteTarget?.title,
        onDismissRename = { renameTarget = null },
        onConfirmRename = { newTitle ->
            renameTarget?.let { onRename(it.conversationId, newTitle) }
            renameTarget = null
        },
        onDismissDelete = { deleteTarget = null },
        onConfirmDelete = {
            deleteTarget?.let { onDelete(it.conversationId) }
            deleteTarget = null
        },
    )

    // Secondary pickers opened from the long-press action menu. Rendered as overlay bottom
    // sheets, so their position in the tree doesn't matter.
    tagPickerTarget?.let { target ->
        TagPicker(
            availableTags = uiState.availableTags,
            currentTags = target.tags.filterNot { it == SAVED_TAG },
            onTagsChange = { newTags -> onUpdateTags(target, newTags) },
            onDismiss = { tagPickerTarget = null },
        )
    }

    exportPickerTarget?.let { target ->
        ExportFormatPicker(
            onFormatSelect = { format ->
                onExportFormat(target, format)
                exportPickerTarget = null
            },
            onDismiss = { exportPickerTarget = null },
        )
    }

    projectPickerTarget?.let { target ->
        ProjectPicker(
            projects = projects,
            currentProjectId = target.chatProjectId,
            onSelect = { projectId -> onMoveToProject(target.conversationId, projectId) },
            onCreate = { name -> onCreateProjectAndAssign(target.conversationId, name) },
            onDismiss = { projectPickerTarget = null },
        )
    }

    if (showCreateProjectDialog) {
        ProjectNameDialog(
            title = stringResource(Res.string.project_new),
            initialName = "",
            onConfirm = {
                onCreateProject(it)
                showCreateProjectDialog = false
            },
            onDismiss = { showCreateProjectDialog = false },
        )
    }

    renameProjectTarget?.let { target ->
        ProjectNameDialog(
            title = target.name,
            initialName = target.name,
            onConfirm = {
                onRenameProject(target.id, it)
                renameProjectTarget = null
            },
            onDismiss = { renameProjectTarget = null },
        )
    }

    deleteProjectTarget?.let { target ->
        ProjectDeleteDialog(
            projectName = target.name,
            onConfirm = {
                onDeleteProject(target.id)
                deleteProjectTarget = null
            },
            onDismiss = { deleteProjectTarget = null },
        )
    }
}

@Composable
private fun DrawerProjectFolderRow(
    name: String,
    conversationCount: Int?,
    isExpanded: Boolean,
    showChevron: Boolean,
    onRowClick: () -> Unit,
    menuContent: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showChevron) {
            val rotation by animateFloatAsState(if (isExpanded) 90f else 0f, label = "projectChevron")
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.width(24.dp))
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversationItem(
    data: DrawerConversationDisplayData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: () -> Unit = {},
    onLongPress: () -> Unit = {},
    showBookmarkToggle: Boolean = true,
    menuContent: @Composable (DpOffset) -> Unit = {},
) {
    val backgroundColor = if (data.isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }

    // Horizontal pixel position of the last press, so the long-press menu can open with its
    // left edge under the finger. Kept in pixels (no density captured in the gesture) and
    // converted to dp at use-site to stay correct across density changes.
    var pressXpx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    // onLongClickLabel announces the long-press action to TalkBack — without it the action menu
    // (the only way to reach rename/delete/share/etc. from the drawer) is undiscoverable.
    val longPressLabel = stringResource(Res.string.cd_conversation_actions)

    // Box wraps the row so the long-press action menu (a DropdownMenu) anchors to this row.
    Box(modifier = modifier) {
        Row(
            // pointerInput is outermost so the captured x shares the Box's coordinate space
            // (the menu anchor), and recorded without consuming the down so combinedClickable
            // still handles tap + long-press normally.
            modifier = Modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressXpx = down.position.x
                    }
                }
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .fillMaxWidth()
                .background(backgroundColor, ItemShape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = longPressLabel,
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (data.isActive) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.primary, ActiveIndicatorShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (data.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                EndpointIcon(
                    endpointName = data.endpoint,
                    iconUrl = data.endpointIconUrl,
                    size = 18.dp,
                    glyphTint = if (data.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (data.isActive) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val subtitle = remember(data.model, data.relativeTime) {
                    buildString {
                        data.model?.let { model ->
                            append(model.take(20))
                        }
                        if (data.relativeTime.isNotEmpty()) {
                            if (isNotEmpty()) append(" \u00B7 ")
                            append(data.relativeTime)
                        }
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (showBookmarkToggle) {
                Icon(
                    imageVector = if (data.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (data.isFavorite) {
                        stringResource(Res.string.remove_bookmark)
                    } else {
                        stringResource(Res.string.bookmark)
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onToggleFavorite)
                        .padding(8.dp),
                    tint = if (data.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // Open the menu just below the row, with its left edge under the press point. The
        // DropdownMenu position provider flips it above the row near the screen bottom and
        // clamps it within a margin, so it never clips off-screen.
        menuContent(with(density) { DpOffset(x = pressXpx.toDp(), y = MenuVerticalGap) })
    }
}

@Composable
private fun DrawerFooterItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
