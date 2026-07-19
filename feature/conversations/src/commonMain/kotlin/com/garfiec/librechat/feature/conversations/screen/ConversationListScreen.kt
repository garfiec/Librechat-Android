package com.garfiec.librechat.feature.conversations.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.conversations.components.ConversationActions
import com.garfiec.librechat.feature.conversations.components.ConversationItem
import com.garfiec.librechat.feature.conversations.components.ConversationSearchBar
import com.garfiec.librechat.feature.conversations.components.DateGroupHeader
import com.garfiec.librechat.feature.conversations.components.ProvideRelativeTimeReference
import com.garfiec.librechat.feature.conversations.components.TagFilterBar
import com.garfiec.librechat.feature.conversations.components.TagPicker
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.export.ExportFormatPicker
import com.garfiec.librechat.feature.conversations.platform.FileSaver
import com.garfiec.librechat.feature.conversations.platform.copyToClipboard
import com.garfiec.librechat.feature.conversations.platform.showToast
import com.garfiec.librechat.feature.conversations.resources.*
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListEvent
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Hoisted: compiled once instead of per export event.
private val ExportFileNameSanitizer = Regex("[^a-zA-Z0-9._-]")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewChatClick: () -> Unit = {},
    onNavigateToArchive: () -> Unit = {},
    viewModel: ConversationListViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    var showTagPicker by remember { mutableStateOf(false) }
    var tagPickerConversation by remember { mutableStateOf<Conversation?>(null) }
    var showExportFormatPicker by remember { mutableStateOf(false) }
    var exportConversation by remember { mutableStateOf<Conversation?>(null) }

    // File saver state
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnConversationClick by rememberUpdatedState(onConversationClick)

    val copyOfPrefix = stringResource(Res.string.copy_of)
    val newChatLabel = stringResource(Res.string.new_chat)
    val conversationExportedMsg = stringResource(Res.string.conversation_exported)
    val linkCopiedMsg = stringResource(Res.string.link_copied)
    val importedTitleTemplate = stringResource(Res.string.imported_title)

    // Platform file saver composable
    FileSaver(
        triggerFileName = pendingExportFileName,
        content = pendingExportContent,
        onComplete = { success, errorMessage ->
            if (success) {
                showToast(conversationExportedMsg)
            } else if (errorMessage != null) {
                showToast(errorMessage)
            }
        },
        onReset = {
            pendingExportFileName = null
            pendingExportContent = null
        },
    )

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationListEvent.ShareLinkCopied -> {
                    copyToClipboard(event.url, "Share Link")
                    snackbarHostState.showSnackbar(linkCopiedMsg)
                }
                is ConversationListEvent.NavigateToConversation -> {
                    currentOnConversationClick(event.conversationId)
                }
                // Only the drawer deletes with active-conversation awareness, so this never fires here.
                is ConversationListEvent.NavigateToNewChat -> Unit
                is ConversationListEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is ConversationListEvent.ExportReady -> {
                    pendingExportContent = event.content
                    val ext = when (event.format) {
                        ExportFormat.JSON -> "json"
                        ExportFormat.MARKDOWN -> "md"
                    }
                    val safeTitle = event.title.replace(ExportFileNameSanitizer, "_")
                    pendingExportFileName = "$safeTitle.$ext"
                }
                is ConversationListEvent.ImportSuccess -> {
                    snackbarHostState.showSnackbar(importedTitleTemplate.replace("%1\$s", event.title))
                }
            }
        }
    }

    val listState = rememberLazyListState()

    // Detect when we scroll near the end to trigger load more
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3 && uiState.hasMore && !uiState.isLoading
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { shouldLoadMore }
            .collect { shouldLoad ->
                if (shouldLoad) {
                    viewModel.loadMore()
                }
            }
    }

    val groupedConversations = uiState.groupedConversations

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(Res.string.cd_new_chat),
                )
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search bar with archive button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConversationSearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChanged,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNavigateToArchive) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = stringResource(Res.string.cd_archived_conversations),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Tag filter bar
                TagFilterBar(
                    tags = uiState.tags,
                    selectedTags = uiState.selectedTags,
                    onTagToggle = viewModel::toggleTag,
                    onClearFilter = viewModel::clearTagFilter,
                )

                when {
                    // Initial loading state
                    uiState.isLoading && uiState.conversationCount == 0 -> {
                        LoadingIndicator()
                    }

                    // Searching state
                    uiState.isSearching -> {
                        LoadingIndicator()
                    }

                    // Error state when no conversations loaded
                    !uiState.isLoading && uiState.conversationCount == 0 && uiState.error != null -> {
                        ErrorBanner(
                            message = uiState.error ?: stringResource(Res.string.could_not_load_conversations),
                            onRetry = {
                                viewModel.dismissError()
                                viewModel.loadConversations()
                            },
                        )
                    }

                    // Empty state (not loading, no conversations)
                    !uiState.isLoading && uiState.conversationCount == 0 -> {
                        EmptyState(
                            title = if (uiState.searchQuery.isNotEmpty()) {
                                stringResource(Res.string.no_results_found)
                            } else {
                                stringResource(Res.string.no_conversations_yet)
                            },
                            description = if (uiState.searchQuery.isNotEmpty()) {
                                stringResource(Res.string.try_different_search)
                            } else {
                                stringResource(Res.string.start_new_chat)
                            },
                            icon = Icons.Default.Forum,
                        )
                    }

                    // Content state
                    else -> {
                        // One ticker for the whole list, so relative-time labels advance while it is open.
                        ProvideRelativeTimeReference {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 80.dp),
                            ) {
                                // Error banner at top if present
                                if (uiState.error != null) {
                                    item(key = "error") {
                                        ErrorBanner(
                                            message = uiState.error ?: stringResource(Res.string.unknown_error),
                                            onRetry = {
                                                viewModel.dismissError()
                                                viewModel.loadConversations()
                                            },
                                        )
                                    }
                                }

                                groupedConversations.forEach { (dateGroup, displayItems) ->
                                    stickyHeader(key = "header_$dateGroup") {
                                        DateGroupHeader(label = dateGroup)
                                    }

                                    // Conversation items in this group
                                    items(
                                        items = displayItems,
                                        key = { it.conversationId },
                                        contentType = { "conversation" },
                                    ) { displayData ->
                                        ConversationItem(
                                            data = displayData,
                                            onClick = {
                                                onConversationClick(displayData.conversationId)
                                            },
                                            onActionsClick = {
                                                selectedConversation = viewModel.getConversation(
                                                    displayData.conversationId,
                                                )
                                            },
                                            bookmarksEnabled = uiState.bookmarksEnabled,
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 52.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    }
                                }

                                // Loading indicator at bottom when loading more
                                if (uiState.isLoading && uiState.conversationCount > 0) {
                                    item(key = "loading_more") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Bottom sheet for conversation actions
    if (selectedConversation != null) {
        ConversationActions(
            conversation = selectedConversation!!,
            onDismiss = { selectedConversation = null },
            isBookmarked = SAVED_TAG in (selectedConversation?.tags ?: emptyList()),
            bookmarksEnabled = uiState.bookmarksEnabled,
            onBookmarkToggle = {
                selectedConversation?.let { convo ->
                    viewModel.toggleFavorite(convo)
                }
                selectedConversation = null
            },
            onRename = { newTitle ->
                selectedConversation?.conversationId?.let { id ->
                    viewModel.renameConversation(id, newTitle)
                }
                selectedConversation = null
            },
            onArchive = {
                selectedConversation?.conversationId?.let { id ->
                    viewModel.archiveConversation(id)
                }
                selectedConversation = null
            },
            onDelete = {
                selectedConversation?.conversationId?.let { id ->
                    viewModel.deleteConversation(id)
                }
                selectedConversation = null
            },
            onTags = {
                tagPickerConversation = selectedConversation
                showTagPicker = true
                selectedConversation = null
            },
            onShare = {
                selectedConversation?.conversationId?.let { id ->
                    viewModel.shareConversation(id)
                }
                selectedConversation = null
            },
            onDuplicate = {
                val convo = selectedConversation
                convo?.conversationId?.let { id ->
                    viewModel.duplicateConversation(id, copyOfPrefix.replace("%1\$s", convo.title ?: newChatLabel))
                }
                selectedConversation = null
            },
            onExport = {
                exportConversation = selectedConversation
                showExportFormatPicker = true
                selectedConversation = null
            },
        )
    }

    // Tag picker bottom sheet
    if (showTagPicker && tagPickerConversation != null) {
        TagPicker(
            availableTags = uiState.tags,
            currentTags = tagPickerConversation?.tags?.filterNot { it == SAVED_TAG } ?: emptyList(),
            onTagsChange = { newTags ->
                tagPickerConversation?.let { convo ->
                    viewModel.updateConversationTags(convo, newTags)
                }
            },
            onDismiss = {
                showTagPicker = false
                tagPickerConversation = null
            },
        )
    }

    // Export format picker
    if (showExportFormatPicker && exportConversation != null) {
        ExportFormatPicker(
            onFormatSelect = { format ->
                exportConversation?.conversationId?.let { id ->
                    viewModel.exportConversation(id, exportConversation?.title, format)
                }
                showExportFormatPicker = false
                exportConversation = null
            },
            onDismiss = {
                showExportFormatPicker = false
                exportConversation = null
            },
        )
    }
}
