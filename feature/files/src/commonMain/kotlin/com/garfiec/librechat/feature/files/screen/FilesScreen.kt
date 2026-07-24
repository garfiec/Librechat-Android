package com.garfiec.librechat.feature.files.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.core.ui.components.PlatformBackHandler
import com.garfiec.librechat.core.ui.media.MediaActionBar
import com.garfiec.librechat.core.ui.media.ZoomableMediaPager
import com.garfiec.librechat.core.ui.media.rememberSaveImageToGallery
import com.garfiec.librechat.core.ui.media.rememberShareImage
import com.garfiec.librechat.feature.files.FileDisplayData
import com.garfiec.librechat.feature.files.components.UploadProgressCard
import com.garfiec.librechat.feature.files.platform.rememberFilePickerLauncher
import com.garfiec.librechat.feature.files.resources.*
import com.garfiec.librechat.feature.files.resources.Res
import com.garfiec.librechat.feature.files.viewmodel.FileTypeFilter
import com.garfiec.librechat.feature.files.viewmodel.FileViewMode
import com.garfiec.librechat.feature.files.viewmodel.FilesViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /**
     * When true the screen acts as an attachment picker: selection is always on, tapping a row
     * (including images) toggles selection rather than opening a preview, and the top bar offers
     * an "Attach" confirm that emits the chosen files via [onConfirmSelection] instead of deleting.
     */
    pickerMode: Boolean = false,
    onConfirmSelection: ((List<FileObject>) -> Unit)? = null,
    viewModel: FilesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // `enterPickerMode()` makes selection sticky in the VM (no auto-exit when the set empties). The
    // VM flag it sets lags the first frame, so the view chrome below is driven off the stable
    // [pickerMode] param instead — picker UI is correct from frame zero, no upload-FAB/preview flash.
    if (pickerMode) {
        LaunchedEffect(Unit) { viewModel.enterPickerMode() }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var singleDeleteFileId by remember { mutableStateOf<String?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCancelSelectionConfirmation by remember { mutableStateOf(false) }

    val requestExitSelection = {
        if (uiState.selectedFileIds.isEmpty()) {
            viewModel.exitSelectionMode()
        } else {
            showCancelSelectionConfirmation = true
        }
    }

    // Gated so it never arms in picker mode (back should close the picker) or outside selection mode.
    PlatformBackHandler(enabled = uiState.isSelectionMode && !pickerMode) {
        requestExitSelection()
    }

    val filePickerLauncher = rememberFilePickerLauncher(
        onFilePick = { fileRef -> viewModel.uploadFile(fileRef) },
    )

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.dismissError()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (pickerMode) {
                TopAppBar(
                    title = { SelectionCountTitle(uiState.selectedFileIds.size) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(Res.string.cd_close_picker),
                                )
                            }
                        }
                    },
                    actions = {
                        SelectAllAction(onClick = { viewModel.selectAll() })
                        ViewModeToggleAction(
                            viewMode = uiState.viewMode,
                            onClick = { viewModel.toggleViewMode() },
                        )
                        TextButton(
                            onClick = { onConfirmSelection?.invoke(viewModel.confirmSelection()) },
                            enabled = uiState.selectedFileIds.isNotEmpty(),
                        ) {
                            Text(stringResource(Res.string.attach_count, uiState.selectedFileIds.size))
                        }
                    },
                )
            } else if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { SelectionCountTitle(uiState.selectedFileIds.size) },
                    navigationIcon = {
                        IconButton(onClick = requestExitSelection) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Res.string.cd_exit_edit_mode),
                            )
                        }
                    },
                    actions = {
                        SelectAllAction(onClick = { viewModel.selectAll() })
                        IconButton(
                            onClick = { showDeleteConfirmation = true },
                            enabled = uiState.selectedFileIds.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(Res.string.cd_delete_selected),
                                tint = if (uiState.selectedFileIds.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(onClick = { viewModel.exitSelectionMode() }) {
                            Text(stringResource(Res.string.done))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(Res.string.files)) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    actions = {
                        if (uiState.hasFiles) {
                            IconButton(onClick = { viewModel.enterEditMode() }) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(Res.string.cd_edit_files),
                                )
                            }
                        }
                        ViewModeToggleAction(
                            viewMode = uiState.viewMode,
                            onClick = { viewModel.toggleViewMode() },
                        )
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(Res.string.cd_sort_files),
                                )
                            }
                            FileSortMenu(
                                expanded = showSortMenu,
                                currentSortField = uiState.sortField,
                                currentSortOrder = uiState.sortOrder,
                                onSortSelect = { field, order ->
                                    viewModel.setSort(field, order)
                                },
                                onDismiss = { showSortMenu = false },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode && !pickerMode) {
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.cd_upload_file),
                    )
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // File type filter tabs
                val filters = FileTypeFilter.entries
                val selectedIndex = filters.indexOf(uiState.selectedFilter)
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 16.dp,
                ) {
                    filters.forEach { filter ->
                        Tab(
                            selected = filter == uiState.selectedFilter,
                            onClick = { viewModel.setFilter(filter) },
                            text = { Text(filter.label) },
                        )
                    }
                }

                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    uiState.error != null && !uiState.hasFiles -> {
                        ErrorBanner(
                            message = uiState.error ?: stringResource(Res.string.could_not_load_files),
                            onRetry = {
                                viewModel.dismissError()
                                viewModel.loadFiles()
                            },
                        )
                    }
                    uiState.displayFiles.isEmpty() && !uiState.isRefreshing -> {
                        EmptyState(
                            title = if (uiState.selectedFilter == FileTypeFilter.ALL) {
                                stringResource(Res.string.no_files)
                            } else {
                                stringResource(Res.string.no_filter_files, uiState.selectedFilter.label.lowercase())
                            },
                            description = if (uiState.selectedFilter == FileTypeFilter.ALL) {
                                stringResource(Res.string.upload_to_get_started)
                            } else {
                                stringResource(Res.string.no_filter_files_found, uiState.selectedFilter.label.lowercase())
                            },
                            icon = Icons.Default.Folder,
                        )
                    }
                    else -> {
                        when (uiState.viewMode) {
                            FileViewMode.LIST -> {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(
                                        items = uiState.displayFiles,
                                        key = { it.fileId },
                                        contentType = { "file" },
                                    ) { file ->
                                        FileItem(
                                            file = file,
                                            isEditMode = uiState.isSelectionMode || pickerMode,
                                            isSelected = file.fileId in uiState.selectedFileIds,
                                            // The picker must never delete — it only attaches by reference.
                                            showDelete = !pickerMode,
                                            onDelete = { singleDeleteFileId = file.fileId },
                                            onLongClick = {
                                                if (!pickerMode) {
                                                    viewModel.enterSelectionMode(file.fileId)
                                                }
                                            },
                                            onClick = {
                                                if (uiState.isSelectionMode || pickerMode) {
                                                    viewModel.toggleFileSelection(file.fileId)
                                                } else if (file.type.startsWith("image/")) {
                                                    viewModel.openImagePreview(file.fileId)
                                                } else {
                                                    viewModel.openFilePreview(file.fileId)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            FileViewMode.GRID -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 150.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(
                                        items = uiState.displayFiles,
                                        key = { it.fileId },
                                        contentType = { "file_grid" },
                                    ) { file ->
                                        FileGridItem(
                                            file = file,
                                            isSelectionMode = uiState.isSelectionMode || pickerMode,
                                            isSelected = file.fileId in uiState.selectedFileIds,
                                            onLongClick = {
                                                if (!pickerMode) {
                                                    viewModel.enterSelectionMode(file.fileId)
                                                }
                                            },
                                            onClick = {
                                                if (uiState.isSelectionMode || pickerMode) {
                                                    viewModel.toggleFileSelection(file.fileId)
                                                } else if (file.type.startsWith("image/")) {
                                                    viewModel.openImagePreview(file.fileId)
                                                } else {
                                                    viewModel.openFilePreview(file.fileId)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Upload progress overlay
            UploadProgressCard(
                visible = uiState.isUploading,
                filename = uiState.uploadFilename,
                progress = uiState.uploadProgress,
                onCancel = viewModel::cancelUpload,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    // Multi-file delete confirmation
    if (showDeleteConfirmation) {
        val count = uiState.selectedFileIds.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(Res.string.delete_count_files, count, if (count > 1) "s" else "")) },
            text = {
                Text(
                    text = stringResource(Res.string.action_cannot_be_undone),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showDeleteConfirmation = false
                }) {
                    Text(
                        text = stringResource(Res.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // Cancel-selection confirmation (back gesture / close with files selected)
    if (showCancelSelectionConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelSelectionConfirmation = false },
            title = { Text(stringResource(Res.string.discard_selection_title)) },
            text = {
                Text(
                    text = stringResource(Res.string.discard_selection_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.exitSelectionMode()
                    showCancelSelectionConfirmation = false
                }) {
                    Text(
                        text = stringResource(Res.string.discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelSelectionConfirmation = false }) {
                    Text(stringResource(Res.string.keep_selecting))
                }
            },
        )
    }

    // Single-file delete confirmation
    val pendingDeleteId = singleDeleteFileId
    if (pendingDeleteId != null) {
        val fileName = uiState.displayFiles
            .find { it.fileId == pendingDeleteId }?.filename ?: stringResource(Res.string.this_file)
        AlertDialog(
            onDismissRequest = { singleDeleteFileId = null },
            title = { Text(stringResource(Res.string.delete_file_question)) },
            text = {
                Text(
                    text = stringResource(Res.string.delete_file_message, fileName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(pendingDeleteId)
                    singleDeleteFileId = null
                }) {
                    Text(
                        text = stringResource(Res.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { singleDeleteFileId = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // File preview dialog
    val previewFile = uiState.previewFile
    if (previewFile != null) {
        FilePreviewDialog(
            file = previewFile,
            onDismiss = viewModel::closeFilePreview,
            onDownloadFile = viewModel::downloadFileBytes,
        )
    }

    // Full-screen zoomable image viewer.
    // Remembered above the `if` so the save/share scope (and the permission launcher) live as
    // long as the screen, letting an in-flight save/share survive the viewer being dismissed.
    val mediaPreview = uiState.mediaPreview
    val saveImage = rememberSaveImageToGallery()
    val shareImage = rememberShareImage()
    if (mediaPreview != null) {
        // Resolved once here (not inside the per-item actions slot) so paging doesn't re-resolve
        // string resources on every swipe.
        val saveDescription = stringResource(Res.string.cd_save_image)
        val shareDescription = stringResource(Res.string.cd_share_image)
        val imageDescription = stringResource(Res.string.cd_image)
        ZoomableMediaPager(
            items = mediaPreview.items,
            initialIndex = mediaPreview.initialIndex,
            onDismiss = viewModel::closeMediaPreview,
            closeContentDescription = stringResource(Res.string.cd_close_preview),
            defaultContentDescription = imageDescription,
            actions = { item ->
                MediaActionBar(
                    item = item,
                    onSave = saveImage,
                    onShare = shareImage,
                    saveContentDescription = saveDescription,
                    shareContentDescription = shareDescription,
                )
            },
        )
    }
}

/** Title for the selection/picker top bars: a generic prompt until something is picked. */
@Composable
private fun SelectionCountTitle(count: Int) {
    Text(
        if (count == 0) {
            stringResource(Res.string.select_files)
        } else {
            stringResource(Res.string.selected_count, count)
        },
    )
}

/** Top-bar action that selects every visible file. */
@Composable
private fun SelectAllAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Default.SelectAll,
            contentDescription = stringResource(Res.string.cd_select_all),
        )
    }
}

/** Top-bar action that toggles between the list and grid layouts. */
@Composable
private fun ViewModeToggleAction(viewMode: FileViewMode, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = when (viewMode) {
                FileViewMode.LIST -> Icons.Default.GridView
                FileViewMode.GRID -> Icons.AutoMirrored.Filled.ViewList
            },
            contentDescription = when (viewMode) {
                FileViewMode.LIST -> stringResource(Res.string.cd_switch_to_grid)
                FileViewMode.GRID -> stringResource(Res.string.cd_switch_to_list)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: FileDisplayData,
    isEditMode: Boolean,
    isSelected: Boolean,
    onDelete: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDelete: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Icon(
                imageVector = fileTypeIcon(file.type),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(file.formattedSize)
                        file.createdAt?.let { append(" \u00B7 $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isEditMode && showDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_file, file.filename),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(
    file: FileDisplayData,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Box {
            Column {
                // Thumbnail area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    if (file.type.startsWith("image/") && file.previewUrl != null) {
                        AsyncImage(
                            model = file.previewUrl,
                            contentDescription = stringResource(Res.string.cd_thumbnail, file.filename),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = fileTypeIconLarge(file.type),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // File info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = file.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // Selection checkbox overlay
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                )
            }
        }
    }
}

private fun fileTypeIcon(type: String): ImageVector = when {
    type.startsWith("image/") -> Icons.Default.Image
    type.startsWith("video/") -> Icons.Default.VideoFile
    type.startsWith("audio/") -> Icons.Default.AudioFile
    else -> Icons.Default.Description
}

private fun fileTypeIconLarge(type: String): ImageVector = when {
    type.startsWith("image/") -> Icons.Default.Image
    type.startsWith("video/") -> Icons.Default.VideoFile
    type.startsWith("audio/") -> Icons.Default.AudioFile
    type == "application/pdf" -> Icons.Default.PictureAsPdf
    type.startsWith("text/") -> Icons.Default.Description
    type == "application/json" || type == "application/xml" -> Icons.Default.Code
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
