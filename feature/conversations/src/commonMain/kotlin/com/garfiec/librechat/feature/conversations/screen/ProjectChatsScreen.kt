package com.garfiec.librechat.feature.conversations.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.conversations.components.ConversationActions
import com.garfiec.librechat.feature.conversations.components.ConversationItem
import com.garfiec.librechat.feature.conversations.components.DateGroupHeader
import com.garfiec.librechat.feature.conversations.components.ProvideRelativeTimeReference
import com.garfiec.librechat.feature.conversations.components.TagPicker
import com.garfiec.librechat.feature.conversations.export.ExportFormat
import com.garfiec.librechat.feature.conversations.export.ExportFormatPicker
import com.garfiec.librechat.feature.conversations.platform.FileSaver
import com.garfiec.librechat.feature.conversations.platform.copyToClipboard
import com.garfiec.librechat.feature.conversations.platform.showToast
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.resources.back
import com.garfiec.librechat.feature.conversations.resources.conversation_exported
import com.garfiec.librechat.feature.conversations.resources.copy_of
import com.garfiec.librechat.feature.conversations.resources.link_copied
import com.garfiec.librechat.feature.conversations.resources.new_chat
import com.garfiec.librechat.feature.conversations.resources.project_empty_chats
import com.garfiec.librechat.feature.conversations.resources.project_sort_created
import com.garfiec.librechat.feature.conversations.resources.project_sort_label
import com.garfiec.librechat.feature.conversations.resources.project_sort_updated
import com.garfiec.librechat.feature.conversations.viewmodel.ConversationListEvent
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectChatsSort
import com.garfiec.librechat.feature.conversations.viewmodel.ProjectChatsViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val ExportFileNameSanitizer = Regex("[^a-zA-Z0-9._-]")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectChatsScreen(
    projectId: String,
    projectName: String,
    onConversationClick: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectChatsViewModel = koinViewModel { parametersOf(projectId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }
    var showTagPicker by remember { mutableStateOf(false) }
    var tagPickerConversation by remember { mutableStateOf<Conversation?>(null) }
    var showExportFormatPicker by remember { mutableStateOf(false) }
    var exportConversation by remember { mutableStateOf<Conversation?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var pendingExportFileName by remember { mutableStateOf<String?>(null) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnConversationClick by rememberUpdatedState(onConversationClick)

    val copyOfPrefix = stringResource(Res.string.copy_of)
    val newChatLabel = stringResource(Res.string.new_chat)
    val conversationExportedMsg = stringResource(Res.string.conversation_exported)
    val linkCopiedMsg = stringResource(Res.string.link_copied)

    FileSaver(
        triggerFileName = pendingExportFileName,
        content = pendingExportContent,
        onComplete = { success, errorMessage ->
            if (success) showToast(conversationExportedMsg) else if (errorMessage != null) showToast(errorMessage)
        },
        onReset = {
            pendingExportFileName = null
            pendingExportContent = null
        },
    )

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationListEvent.ShareLinkCopied -> {
                    copyToClipboard(event.url, "Share Link")
                    snackbarHostState.showSnackbar(linkCopiedMsg)
                }
                is ConversationListEvent.NavigateToConversation -> currentOnConversationClick(event.conversationId)
                // Only the drawer deletes with active-conversation awareness, so this never fires here.
                is ConversationListEvent.NavigateToNewChat -> Unit
                is ConversationListEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is ConversationListEvent.ExportReady -> {
                    pendingExportContent = event.content
                    val ext = when (event.format) {
                        ExportFormat.JSON -> "json"
                        ExportFormat.MARKDOWN -> "md"
                    }
                    pendingExportFileName = "${event.title.replace(ExportFileNameSanitizer, "_")}.$ext"
                }
                is ConversationListEvent.ImportSuccess -> Unit
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
                title = {
                    Text(projectName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(Res.string.project_sort_label))
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.project_sort_updated)) },
                            onClick = { viewModel.setSort(ProjectChatsSort.UPDATED); sortMenuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.project_sort_created)) },
                            onClick = { viewModel.setSort(ProjectChatsSort.CREATED); sortMenuOpen = false },
                        )
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
            when {
                uiState.isLoading && uiState.conversationCount == 0 -> LoadingIndicator()
                uiState.conversationCount == 0 -> EmptyState(
                    title = stringResource(Res.string.project_empty_chats),
                    icon = Icons.Default.Forum,
                )
                else -> {
                    // One ticker for the whole list, so relative-time labels advance while it is open.
                    ProvideRelativeTimeReference {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            uiState.groupedConversations.forEach { (dateGroup, displayItems) ->
                                stickyHeader(key = "header_$dateGroup") { DateGroupHeader(label = dateGroup) }
                                items(
                                    items = displayItems,
                                    key = { it.conversationId },
                                    contentType = { "conversation" },
                                ) { displayData ->
                                    ConversationItem(
                                        data = displayData,
                                        onClick = { onConversationClick(displayData.conversationId) },
                                        onActionsClick = {
                                            selectedConversation = viewModel.getConversation(displayData.conversationId)
                                        },
                                        bookmarksEnabled = uiState.bookmarksEnabled,
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 52.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                            if (uiState.isLoading && uiState.conversationCount > 0) {
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
            }
        }
    }

    if (selectedConversation != null) {
        ConversationActions(
            conversation = selectedConversation!!,
            onDismiss = { selectedConversation = null },
            isBookmarked = SAVED_TAG in (selectedConversation?.tags ?: emptyList()),
            bookmarksEnabled = uiState.bookmarksEnabled,
            onBookmarkToggle = {
                selectedConversation?.let { viewModel.toggleFavorite(it) }
                selectedConversation = null
            },
            onRename = { newTitle ->
                selectedConversation?.conversationId?.let { viewModel.renameConversation(it, newTitle) }
                selectedConversation = null
            },
            onArchive = {
                selectedConversation?.conversationId?.let { viewModel.archiveConversation(it) }
                selectedConversation = null
            },
            onDelete = {
                selectedConversation?.conversationId?.let { viewModel.deleteConversation(it) }
                selectedConversation = null
            },
            onTags = {
                tagPickerConversation = selectedConversation
                showTagPicker = true
                selectedConversation = null
            },
            onShare = {
                selectedConversation?.conversationId?.let { viewModel.shareConversation(it) }
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

    if (showTagPicker && tagPickerConversation != null) {
        TagPicker(
            availableTags = uiState.tags,
            currentTags = tagPickerConversation?.tags?.filterNot { it == SAVED_TAG } ?: emptyList(),
            onTagsChange = { newTags -> tagPickerConversation?.let { viewModel.updateConversationTags(it, newTags) } },
            onDismiss = {
                showTagPicker = false
                tagPickerConversation = null
            },
        )
    }

    if (showExportFormatPicker && exportConversation != null) {
        ExportFormatPicker(
            onFormatSelect = { format ->
                exportConversation?.conversationId?.let { viewModel.exportConversation(it, exportConversation?.title, format) }
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
