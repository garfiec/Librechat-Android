package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.feature.settings.model.SharedLinkDisplayData
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.util.copyToClipboard
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLinksScreen(
    links: List<SharedLinkDisplayData>,
    isLoading: Boolean,
    hasNextPage: Boolean,
    serverUrl: String,
    onLoadMore: () -> Unit,
    /** SHARED_LINKS CREATE. False hides the update action; delete stays available. */
    canUpdate: Boolean,
    onUpdateLink: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var deleteTarget by remember { mutableStateOf<SharedLinkDisplayData?>(null) }
    var updateTarget by remember { mutableStateOf<SharedLinkDisplayData?>(null) }
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    // Pagination: load more when near the end
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 3
        }.collect { shouldLoad ->
            if (shouldLoad && hasNextPage && !isLoading) {
                currentOnLoadMore()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_shared_links)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            isLoading && links.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            links.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    EmptyState(
                        title = stringResource(Res.string.no_shared_links),
                        description = stringResource(Res.string.no_shared_links_desc),
                        icon = Icons.Default.Link,
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(
                        items = links,
                        key = { it.shareId.ifEmpty { it.hashCode().toString() } },
                        contentType = { "shared_link" },
                    ) { link ->
                        SharedLinkItem(
                            link = link,
                            serverUrl = serverUrl,
                            onCopy = { url ->
                                copyToClipboard(url, "Share Link")
                            },
                            canUpdate = canUpdate,
                            onUpdateLink = { updateTarget = link },
                            onDelete = { deleteTarget = link },
                        )
                    }
                    if (isLoading) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Update confirmation. Upstream added one for the same reason: the action re-publishes the
    // conversation as it now stands behind a URL that has already been handed out, and the link
    // keeps working either way — so nothing about the outcome is visible enough to undo.
    val linkToUpdate = updateTarget
    if (linkToUpdate != null) {
        AlertDialog(
            onDismissRequest = { updateTarget = null },
            title = { Text(stringResource(Res.string.dialog_title_update_shared_link)) },
            text = {
                Text(stringResource(Res.string.dialog_update_shared_link_message, linkToUpdate.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (linkToUpdate.shareId.isNotEmpty()) {
                            onUpdateLink(linkToUpdate.shareId)
                        }
                        updateTarget = null
                    },
                ) {
                    Text(stringResource(Res.string.action_update_shared_link))
                }
            },
            dismissButton = {
                TextButton(onClick = { updateTarget = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // Delete confirmation
    val linkToDelete = deleteTarget
    if (linkToDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(Res.string.dialog_title_delete_shared_link)) },
            text = {
                Text(stringResource(Res.string.dialog_delete_shared_link_message, linkToDelete.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (linkToDelete.shareId.isNotEmpty()) {
                            onDelete(linkToDelete.shareId)
                        }
                        deleteTarget = null
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SharedLinkItem(
    link: SharedLinkDisplayData,
    serverUrl: String,
    onCopy: (String) -> Unit,
    canUpdate: Boolean,
    onUpdateLink: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shareUrl = buildString {
        append(serverUrl.trimEnd('/'))
        append("/share/")
        append(link.shareId)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = link.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = shareUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            link.createdAt?.let { created ->
                Text(
                    text = stringResource(Res.string.created_prefix, created),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Visibility status indicator
                Text(
                    text = stringResource(if (link.isPublic) Res.string.visibility_public else Res.string.visibility_private),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (link.isPublic) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Update (re-publish) the link. Hidden — not disabled — for a role without
                // SHARED_LINKS CREATE: the other two actions stay live, so a greyed-out third
                // icon reads as a transient state rather than as a permission the user does not
                // have and cannot obtain from here.
                if (canUpdate) {
                    IconButton(onClick = onUpdateLink, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(Res.string.cd_update_shared_link),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // Copy link
                IconButton(
                    onClick = { onCopy(shareUrl) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(Res.string.cd_copy_link),
                        modifier = Modifier.size(18.dp),
                    )
                }
                // Delete
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_shared_link),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
