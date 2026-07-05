package com.garfiec.librechat.shared.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.garfiec.librechat.feature.conversations.components.ProjectPicker
import com.garfiec.librechat.feature.conversations.components.TagPicker
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.export.ExportFormatPicker
import com.garfiec.librechat.shared.resources.Res
import com.garfiec.librechat.shared.resources.agents
import com.garfiec.librechat.shared.resources.bookmark
import com.garfiec.librechat.shared.resources.cd_clear_search
import com.garfiec.librechat.shared.resources.cd_collapse_section
import com.garfiec.librechat.shared.resources.cd_conversation_actions
import com.garfiec.librechat.shared.resources.cd_expand_section
import com.garfiec.librechat.shared.resources.cd_search
import com.garfiec.librechat.shared.resources.favorites
import com.garfiec.librechat.shared.resources.files
import com.garfiec.librechat.shared.resources.new_chat
import com.garfiec.librechat.shared.resources.no_conversations_found
import com.garfiec.librechat.shared.resources.pinned
import com.garfiec.librechat.shared.resources.projects
import com.garfiec.librechat.shared.resources.remove_bookmark
import com.garfiec.librechat.shared.resources.search_conversations_placeholder
import com.garfiec.librechat.shared.resources.settings
import com.garfiec.librechat.shared.resources.show_less
import com.garfiec.librechat.shared.resources.show_more
import com.garfiec.librechat.shared.resources.skills
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Pre-computed shapes to avoid creating new ones per item per frame
private val ItemShape = RoundedCornerShape(8.dp)
private val ActiveIndicatorShape = RoundedCornerShape(2.dp)

// Pulls a tappable drawer row in from the edges and clips its ripple to [ItemShape], so every row
// reads as the same inset, rounded button instead of a full-bleed rectangular highlight. Apply
// before .clickable so the indication is bounded by the rounded shape.
private fun Modifier.drawerRowShape(): Modifier =
    padding(horizontal = 4.dp, vertical = 1.dp).clip(ItemShape)

// Gap between the conversation row's bottom edge and the long-press menu's top edge.
private val MenuVerticalGap = 4.dp

// Favorites shown before the "Show more" toggle reveals the rest.
private const val FavoritesPreviewCount = 5

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
    onOpenProjects: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    viewModel: NavHostViewModel = koinViewModel(),
) {
    val uiState by viewModel.drawerUiState.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    // Account switcher: the header chip opens the roster sheet; remove asks for confirmation.
    // Switch/add callbacks come from the host (they also close the drawer); remove goes straight to
    // the ViewModel against the tapped row's id — no captured drawer state (Nav3 stale-closure rule).
    var showAccountSheet by remember { mutableStateOf(false) }
    var removeAccountTarget by remember { mutableStateOf<AccountUiModel?>(null) }

    // Side-effects for the long-press action menu (share-link copy, export file-save, navigate to
    // a duplicated conversation, error toasts). Lives in feature/conversations so it owns the
    // clipboard/toast/file-save plumbing and its localized strings.
    ConversationActionEffects(
        events = viewModel.events,
        onNavigateToConversation = onConversationClick,
    )

    DrawerContent(
        uiState = uiState,
        footerContent = {
            accounts.firstOrNull { it.isActive }?.let { active ->
                Spacer(modifier = Modifier.height(8.dp))
                // Footer row: Settings (icon + label) on the left takes the width; the account
                // avatar (icon only, tap to switch) sits on the right.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrawerFooterItem(
                        icon = Icons.Default.Settings,
                        label = stringResource(Res.string.settings),
                        onClick = onSettingsClick,
                        modifier = Modifier.weight(1f),
                    )
                    AccountChip(
                        account = active,
                        onClick = { showAccountSheet = true },
                        // Gmail/YouTube-style: swipe the avatar up/down to round-robin accounts
                        // without opening the sheet. Switches in place via the ViewModel so the user
                        // can swipe through several accounts against the same avatar. The sheet path
                        // keeps the drawer open too (the host no longer closes it on switch).
                        // Disabled (null) with a single account.
                        onSwitchAdjacent = if (accounts.size > 1) {
                            { delta -> adjacentAccountId(accounts, delta)?.let(viewModel::switchAccount) }
                        } else {
                            null
                        },
                    )
                }
            }
        },
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onNewChat = onNewChat,
        onConversationClick = onConversationClick,
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
        onOpenProjects = onOpenProjects,
        onShare = viewModel::shareConversation,
        onDuplicate = { id, title -> viewModel.duplicateConversation(id, title) },
        onUpdateTags = { data, tags -> viewModel.updateConversationTags(data.conversationId, data.tags, tags) },
        onExportFormat = { data, format -> viewModel.exportConversation(data.conversationId, data.title, format) },
        modifier = modifier,
    )

    if (showAccountSheet) {
        AccountSwitcherSheet(
            accounts = accounts,
            onSwitchAccount = { accountId ->
                showAccountSheet = false
                onSwitchAccount(accountId)
            },
            onRemoveAccountRequest = { removeAccountTarget = it },
            onAddAccount = {
                showAccountSheet = false
                onAddAccount()
            },
            onDismiss = { showAccountSheet = false },
        )
    }

    removeAccountTarget?.let { target ->
        RemoveAccountDialog(
            account = target,
            onConfirm = {
                viewModel.removeAccount(target.accountId)
                removeAccountTarget = null
            },
            onDismiss = { removeAccountTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(
    uiState: DrawerUiState,
    onSearchQueryChange: (String) -> Unit,
    onNewChat: () -> Unit,
    onConversationClick: (String) -> Unit,
    onAgentsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onSkillsClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Slot below the footer links (Files, Agents, …) — the stateful wrapper puts the Settings row
    // and the account avatar here, at the bottom of the drawer.
    footerContent: (@Composable () -> Unit)? = null,
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
    // Swaps the sidebar to the Projects mode ([ProjectsSidebarContent]) via SidebarScaffold, rather
    // than navigating to the full-page index — see the Projects entry below.
    onOpenProjects: () -> Unit = {},
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

    // Favorites section view state: whether the whole section is collapsed, and (when expanded)
    // whether it shows all favorites or just the top [FavoritesPreviewCount]. Saveable so the
    // choice survives config changes and the drawer's carousel mode switches.
    var favoritesCollapsed by rememberSaveable { mutableStateOf(false) }
    var showAllFavorites by rememberSaveable { mutableStateOf(false) }

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
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 16.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusAnchor)
                .focusable(),
        )

        // Search field is hidden by default and revealed by the toggle beside "New Chat". Seed the
        // toggle from the current query so a restored search stays visible across recompositions.
        var searchExpanded by remember { mutableStateOf(uiState.searchQuery.isNotEmpty()) }
        val searchFocusRequester = remember { FocusRequester() }

        // Focus the field (and pop the keyboard) only when the user opens search explicitly — the
        // focusAnchor above still steals initial focus so opening the drawer doesn't do this.
        LaunchedEffect(searchExpanded) {
            if (searchExpanded) {
                runCatching { searchFocusRequester.requestFocus() }
            }
        }

        // "New Chat" button at top, with a search toggle to its right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onNewChat,
                modifier = Modifier.weight(1f),
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

            Spacer(modifier = Modifier.width(8.dp))

            // Collapsing clears the query so the list resets to its normal (unsearched) state.
            Surface(
                onClick = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) onSearchQueryChange("")
                },
                modifier = Modifier.fillMaxHeight(),
                shape = ItemShape,
                color = if (searchExpanded) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = stringResource(Res.string.cd_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Search bar — revealed only when toggled on.
        AnimatedVisibility(visible = searchExpanded) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
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
                        .padding(horizontal = 12.dp)
                        .focusRequester(searchFocusRequester),
                )
            }
        }

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
                // Pinned section (v0.8.7) — pinned conversations surfaced above favorites. This is
                // their canonical home: when shown, they're filtered out of the date-grouped buckets
                // (see ConversationListStateHolder.withoutPinned) so they don't appear twice. The
                // section is hidden during search, where pinned rows instead surface in the results.
                if (uiState.pinnedConversations.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    item(key = "pinned_header") {
                        SectionHeader(
                            icon = Icons.Default.PushPin,
                            title = stringResource(Res.string.pinned),
                        )
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
                // The header collapses the whole section; when expanded, only the top
                // [FavoritesPreviewCount] show until "Show more" reveals the rest.
                if (uiState.bookmarksEnabled && uiState.favoriteConversations.isNotEmpty() && uiState.searchQuery.isEmpty()) {
                    val favorites = uiState.favoriteConversations
                    stickyHeader(key = "favorites_header") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            SectionHeader(
                                icon = Icons.Default.Star,
                                title = stringResource(Res.string.favorites),
                                collapsed = favoritesCollapsed,
                                onToggle = { favoritesCollapsed = !favoritesCollapsed },
                            )
                        }
                    }

                    // The whole body (preview rows + show-more + divider) lives in one item so it can
                    // expand/collapse as a unit; the extra rows past the preview get their own nested
                    // reveal. Favorites are a small curated set, so composing them eagerly is cheap.
                    item(key = "favorites_body") {
                        Column {
                            AnimatedVisibility(
                                visible = !favoritesCollapsed,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column {
                                    favorites.take(FavoritesPreviewCount).forEach { data ->
                                        renderConversationItem("fav_${data.conversationId}", data)
                                    }

                                    if (favorites.size > FavoritesPreviewCount) {
                                        AnimatedVisibility(
                                            visible = showAllFavorites,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut(),
                                        ) {
                                            Column {
                                                favorites.drop(FavoritesPreviewCount).forEach { data ->
                                                    renderConversationItem("fav_${data.conversationId}", data)
                                                }
                                            }
                                        }
                                        ShowMoreLessRow(
                                            expanded = showAllFavorites,
                                            onClick = { showAllFavorites = !showAllFavorites },
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
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
                    stickyHeader(key = "header_$dateGroup") {
                        Text(
                            text = dateGroup,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(
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

        if (uiState.projectsEnabled) {
            DrawerProjectsEntry(onClick = onOpenProjects)
        }

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

        footerContent?.invoke()

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
}

/** Drawer entry that swaps the sidebar to the Projects mode ([ProjectsSidebarContent]). */
@Composable
private fun DrawerProjectsEntry(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawerRowShape()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
            text = stringResource(Res.string.projects),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                .clip(ItemShape)
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

/**
 * Drawer section header (icon + title), shared by the Pinned and Favorites sections. With a non-null
 * [onToggle] the whole row is tappable (a full 48dp touch target) and shows a trailing chevron that
 * points down when expanded and right when collapsed; with a null [onToggle] it renders as a plain,
 * non-interactive, compact header.
 */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        label = "SectionChevronRotation",
    )
    // The interactive header keeps its label/icon visually small but claims a 48dp-tall hit area so
    // it's comfortably tappable; the static header stays compact so it doesn't waste vertical space.
    val rowModifier = if (onToggle != null) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .drawerRowShape()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(
                    if (collapsed) Res.string.cd_expand_section else Res.string.cd_collapse_section,
                ),
                onClick = onToggle,
            )
            .padding(horizontal = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (onToggle != null) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Tappable "Show more"/"Show less" row that toggles a section between its preview and full list. */
@Composable
private fun ShowMoreLessRow(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawerRowShape()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (expanded) Res.string.show_less else Res.string.show_more),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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
            .drawerRowShape()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
