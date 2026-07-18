package com.garfiec.librechat.feature.files.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.core.model.request.DeleteFileEntry
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.feature.files.FileDisplayData
import com.garfiec.librechat.feature.files.FilePreviewDisplayData
import com.garfiec.librechat.feature.files.platform.FileReader
import com.garfiec.librechat.feature.files.platform.formatFileSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class FileTypeFilter(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    DOCUMENTS("Documents"),
    AUDIO("Audio"),
    VIDEO("Video"),
}

// Stored strings are decoupled from the constant names so renaming a constant can't orphan a saved
// preference (matches StarredModelsDisplay/ContextBarPlacement/etc. in :core:data).
enum class FileSortField(val label: String) {
    NAME("Name"),
    DATE("Date"),
    SIZE("Size"),
    TYPE("Type");

    companion object {
        fun fromString(value: String?): FileSortField = when (value) {
            "name" -> NAME
            "size" -> SIZE
            "type" -> TYPE
            else -> DATE
        }
    }

    fun toStorageString(): String = when (this) {
        NAME -> "name"
        DATE -> "date"
        SIZE -> "size"
        TYPE -> "type"
    }
}

enum class FileSortOrder {
    ASCENDING,
    DESCENDING;

    companion object {
        fun fromString(value: String?): FileSortOrder =
            if (value == "ascending") ASCENDING else DESCENDING
    }

    fun toStorageString(): String = when (this) {
        ASCENDING -> "ascending"
        DESCENDING -> "descending"
    }
}

enum class FileViewMode {
    LIST,
    GRID;

    companion object {
        fun fromString(value: String?): FileViewMode = when (value) {
            "grid" -> GRID
            else -> LIST
        }
    }

    fun toStorageString(): String = when (this) {
        LIST -> "list"
        GRID -> "grid"
    }
}

@Immutable
data class FilesUiState(
    val displayFiles: List<FileDisplayData> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isUploading: Boolean = false,
    val uploadFilename: String = "",
    val uploadProgress: Float? = null,
    val error: String? = null,
    val selectedFilter: FileTypeFilter = FileTypeFilter.ALL,
    val isSelectionMode: Boolean = false,
    val isPickerMode: Boolean = false,
    val selectedFileIds: Set<String> = emptySet(),
    val sortField: FileSortField = FileSortField.DATE,
    val sortOrder: FileSortOrder = FileSortOrder.DESCENDING,
    val previewFile: FilePreviewDisplayData? = null,
    val mediaPreview: MediaPreviewState? = null,
    val hasFiles: Boolean = false,
    val viewMode: FileViewMode = FileViewMode.LIST,
)

class FilesViewModel(
    private val fileRepository: FileRepository,
    private val fileReader: FileReader,
    private val serverDataStore: ServerDataStore,
    private val settingsDataStore: SettingsDataStore,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _files = MutableStateFlow<List<FileObject>>(emptyList())

    // Type filter is session-local (resets to All on relaunch, by design).
    private val _selectedFilter = MutableStateFlow(FileTypeFilter.ALL)

    // Sort and view mode are optimistic local state (the UI source of truth) written through to
    // DataStore and hydrated from it once in init. They start null ("not yet hydrated"): the default
    // is shown for the brief window until the persisted value loads, and the seed uses
    // update { it ?: ... } so a change made in that window wins over the incoming persisted value.
    private val _sort = MutableStateFlow<SortSpec?>(null)
    private val _viewMode = MutableStateFlow<FileViewMode?>(null)

    private val _transientState = MutableStateFlow(TransientState())

    /** Cache display data by fileId to avoid re-running formatFileSize on every emission. */
    private val displayDataCache = mutableMapOf<String, FileDisplayData>()

    /**
     * The filtered + sorted display list, derived only from the inputs that affect it. Kept
     * separate from [_transientState] so that purely-overlay changes (opening/closing the image
     * viewer, upload progress, selection) don't re-run filter+sort over the whole file list.
     */
    private val displayList: Flow<DisplayList> = combine(
        _files,
        _selectedFilter,
        _sort,
    ) { files, filter, sortOrNull ->
        val sort = sortOrNull ?: DefaultSort
        val filtered = filterFiles(files, filter)
        val sorted = sortFiles(filtered, sort.field, sort.order)
        DisplayList(
            files = sorted.map { file -> displayDataCache.getOrPut(file.fileId) { file.toDisplayData() } },
            hasFiles = files.isNotEmpty(),
            filter = filter,
            sortField = sort.field,
            sortOrder = sort.order,
        )
    }

    val uiState: StateFlow<FilesUiState> = combine(
        displayList,
        _transientState,
        _viewMode,
    ) { list, transient, mode ->
        FilesUiState(
            displayFiles = list.files,
            isLoading = transient.isLoading,
            isRefreshing = transient.isRefreshing,
            isUploading = transient.isUploading,
            uploadFilename = transient.uploadFilename,
            uploadProgress = transient.uploadProgress,
            error = transient.error,
            selectedFilter = list.filter,
            isSelectionMode = transient.isSelectionMode,
            isPickerMode = transient.isPickerMode,
            selectedFileIds = transient.selectedFileIds,
            sortField = list.sortField,
            sortOrder = list.sortOrder,
            previewFile = transient.previewFile,
            mediaPreview = transient.mediaPreview,
            hasFiles = list.hasFiles,
            viewMode = mode ?: FileViewMode.LIST,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = FilesUiState(),
    )

    init {
        viewModelScope.launch {
            val field = FileSortField.fromString(settingsDataStore.filesSortField.first())
            val order = FileSortOrder.fromString(settingsDataStore.filesSortOrder.first())
            _sort.update { it ?: SortSpec(field, order) }
        }
        viewModelScope.launch {
            val mode = FileViewMode.fromString(settingsDataStore.filesViewMode.first())
            _viewMode.update { it ?: mode }
        }
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch {
            updateTransient { copy(isLoading = true, error = null) }
            when (val result = fileRepository.getFiles()) {
                is Result.Success -> {
                    displayDataCache.clear()
                    _files.value = result.data
                    updateTransient { copy(isLoading = false) }
                }
                is Result.Error -> {
                    updateTransient {
                        copy(
                            isLoading = false,
                            error = result.message ?: "Failed to load files",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            updateTransient { copy(isRefreshing = true) }
            when (val result = fileRepository.getFiles()) {
                is Result.Success -> {
                    displayDataCache.clear()
                    _files.value = result.data
                    updateTransient { copy(isRefreshing = false) }
                }
                is Result.Error -> {
                    updateTransient {
                        copy(
                            isRefreshing = false,
                            error = result.message ?: "Failed to refresh files",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private var uploadJob: Job? = null

    /**
     * Upload a file from a platform-specific file reference.
     * On Android this is a Uri; on iOS it will be an NSURL.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun uploadFile(fileRef: Any) {
        uploadJob?.cancel()
        lateinit var thisJob: Job
        thisJob = viewModelScope.launch(ioDispatcher) {
            val filename = fileReader.getFileName(fileRef) ?: "upload"
            updateTransient {
                copy(
                    isUploading = true,
                    uploadFilename = filename,
                    uploadProgress = null,
                    error = null,
                )
            }
            val mimeType = fileReader.getMimeType(fileRef) ?: "application/octet-stream"
            val bytes = fileReader.readBytes(fileRef)
            if (bytes == null) {
                Logger.e { "uploadFile: could not read bytes from file reference" }
                updateTransient {
                    copy(
                        isUploading = false,
                        uploadFilename = "",
                        uploadProgress = null,
                        error = "Could not read file",
                    )
                }
                return@launch
            }

            val fileId = Uuid.random().toString()
            Logger.d { "uploadFile: filename=$filename, mimeType=$mimeType, size=${bytes.size}, fileId=$fileId" }

            when (val result = fileRepository.uploadFile(
                bytes = bytes,
                filename = filename,
                type = mimeType,
                fileId = fileId,
                endpoint = "agents",
                // Gate progress writes on the launching job: a tardy onProgress
                // from a cancelled upload must not overwrite the new upload's state.
                onProgress = { pct ->
                    if (thisJob.isActive) updateTransient { copy(uploadProgress = pct) }
                },
            )) {
                is Result.Success -> {
                    Logger.d { "uploadFile: success -- serverFileId=${result.data.fileId}" }
                    _files.value = listOf(result.data) + _files.value
                    updateTransient {
                        copy(
                            isUploading = false,
                            uploadFilename = "",
                            uploadProgress = null,
                        )
                    }
                }
                is Result.Error -> {
                    Logger.e(result.exception) { "uploadFile: server error -- ${result.message}" }
                    updateTransient {
                        copy(
                            isUploading = false,
                            uploadFilename = "",
                            uploadProgress = null,
                            error = result.message ?: "Upload failed",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
        uploadJob = thisJob
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        updateTransient {
            copy(
                isUploading = false,
                uploadFilename = "",
                uploadProgress = null,
            )
        }
    }

    fun deleteFile(fileId: String) {
        viewModelScope.launch {
            val file = _files.value.find { it.fileId == fileId }
            val entry = DeleteFileEntry(
                fileId = fileId,
                filepath = file?.filepath ?: "",
            )
            when (val result = fileRepository.deleteFiles(listOf(entry))) {
                is Result.Success -> {
                    _files.value = _files.value.filter { it.fileId != fileId }
                }
                is Result.Error -> {
                    updateTransient {
                        copy(error = result.message ?: "Failed to delete file")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun setFilter(filter: FileTypeFilter) {
        _selectedFilter.value = filter
    }

    fun setSort(field: FileSortField, order: FileSortOrder) {
        _sort.value = SortSpec(field, order)
        viewModelScope.launch {
            settingsDataStore.setFilesSort(field.toStorageString(), order.toStorageString())
        }
    }

    fun toggleViewMode() {
        // Read-then-set is synchronous here (no async gap), so back-to-back taps can't lose a toggle.
        val next = when (_viewMode.value ?: FileViewMode.LIST) {
            FileViewMode.LIST -> FileViewMode.GRID
            FileViewMode.GRID -> FileViewMode.LIST
        }
        _viewMode.value = next
        viewModelScope.launch {
            settingsDataStore.setFilesViewMode(next.toStorageString())
        }
    }

    // File preview

    fun openFilePreview(fileId: String) {
        val file = _files.value.find { it.fileId == fileId } ?: return
        updateTransient { copy(previewFile = file.toPreviewDisplayData()) }
    }

    fun closeFilePreview() {
        updateTransient { copy(previewFile = null) }
    }

    /**
     * Opens the full-screen zoomable image viewer for [fileId]. The swipeable list is the image
     * subset of the grid as it's currently filtered and sorted, in grid order, so paging matches
     * what the user sees behind the viewer.
     */
    fun openImagePreview(fileId: String) {
        // Reuse the already filtered + sorted display list (URLs resolved once in `previewUrl`)
        // instead of re-running filter+sort and re-resolving every URL on the tap.
        val images = uiState.value.displayFiles.filter { it.previewUrl != null }
        if (images.isEmpty()) return
        // Dedup by URL: the pager keys pages on url, so duplicate URLs would crash it.
        val items = images
            .map { file ->
                MediaItem(
                    url = file.previewUrl!!,
                    contentDescription = file.filename,
                    filename = file.filename,
                )
            }
            .distinctBy { it.url }
        // Locate the tapped file by its resolved URL within the deduped list.
        val tappedUrl = images.firstOrNull { it.fileId == fileId }?.previewUrl
        val index = items.indexOfFirst { it.url == tappedUrl }.coerceAtLeast(0)
        updateTransient { copy(mediaPreview = MediaPreviewState(items, index)) }
    }

    fun closeMediaPreview() {
        updateTransient { copy(mediaPreview = null) }
    }

    suspend fun downloadFileBytes(fileId: String, userId: String?): ByteArray? {
        if (userId.isNullOrBlank()) {
            Logger.w { "downloadFileBytes: userId is null/blank for fileId=$fileId" }
            return null
        }
        return when (val result = fileRepository.downloadFile(userId, fileId)) {
            is Result.Success -> {
                Logger.d { "downloadFileBytes: success, ${result.data.size} bytes for fileId=$fileId" }
                result.data
            }
            is Result.Error -> {
                Logger.e(result.exception) { "downloadFileBytes: error for fileId=$fileId: ${result.message}" }
                null
            }
            is Result.Loading -> null
        }
    }

    // Multi-select methods

    /**
     * Enters the attachment-picker variant of selection mode: selection starts active and empty,
     * and (unlike [enterSelectionMode]) stays active even when the user deselects everything, so
     * the "Attach" confirm bar never disappears mid-pick. Idempotent — safe to call from a
     * `LaunchedEffect` that re-runs on recomposition.
     */
    fun enterPickerMode() {
        if (_transientState.value.isPickerMode) return
        updateTransient {
            copy(
                isSelectionMode = true,
                isPickerMode = true,
                selectedFileIds = emptySet(),
            )
        }
    }

    /** Returns the full [FileObject]s the user picked, for attaching by reference (no re-upload). */
    fun confirmSelection(): List<FileObject> {
        val selected = _transientState.value.selectedFileIds
        return _files.value.filter { it.fileId in selected }
    }

    fun enterEditMode() {
        updateTransient {
            copy(
                isSelectionMode = true,
                selectedFileIds = emptySet(),
            )
        }
    }

    fun enterSelectionMode(fileId: String) {
        updateTransient {
            copy(
                isSelectionMode = true,
                selectedFileIds = setOf(fileId),
            )
        }
    }

    fun exitSelectionMode() {
        updateTransient {
            copy(
                isSelectionMode = false,
                selectedFileIds = emptySet(),
            )
        }
    }

    fun toggleFileSelection(fileId: String) {
        val current = _transientState.value.selectedFileIds
        val updated = if (fileId in current) current - fileId else current + fileId
        // In picker mode selection mode is sticky — emptying the set must not dismiss the
        // "Attach" bar, so only the delete-flow auto-exits when nothing is left.
        if (updated.isEmpty() && !_transientState.value.isPickerMode) {
            updateTransient {
                copy(
                    isSelectionMode = false,
                    selectedFileIds = emptySet(),
                )
            }
        } else {
            updateTransient { copy(selectedFileIds = updated) }
        }
    }

    fun selectAll() {
        // "Select all" adds every currently-visible file to the selection rather than replacing
        // it, so picks made under another filter (selection is sticky across filter changes in
        // picker mode) are not silently dropped.
        val filtered = filterFiles(_files.value, _selectedFilter.value)
        val visibleIds = filtered.map { it.fileId }.toSet()
        updateTransient { copy(selectedFileIds = selectedFileIds + visibleIds) }
    }

    fun deleteSelected() {
        val selectedIds = _transientState.value.selectedFileIds
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            val entries = selectedIds.mapNotNull { id ->
                val file = _files.value.find { it.fileId == id }
                file?.let { DeleteFileEntry(fileId = it.fileId, filepath = it.filepath) }
            }
            when (val result = fileRepository.deleteFiles(entries)) {
                is Result.Success -> {
                    _files.value = _files.value.filter { it.fileId !in selectedIds }
                    updateTransient {
                        copy(
                            isSelectionMode = false,
                            selectedFileIds = emptySet(),
                        )
                    }
                }
                is Result.Error -> {
                    updateTransient {
                        copy(error = result.message ?: "Failed to delete files")
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissError() {
        updateTransient { copy(error = null) }
    }

    // Private helpers

    private fun filterFiles(files: List<FileObject>, filter: FileTypeFilter): List<FileObject> =
        when (filter) {
            FileTypeFilter.ALL -> files
            FileTypeFilter.IMAGES -> files.filter { it.type.startsWith("image/") }
            FileTypeFilter.DOCUMENTS -> files.filter {
                it.type.startsWith("application/") || it.type.startsWith("text/")
            }
            FileTypeFilter.AUDIO -> files.filter { it.type.startsWith("audio/") }
            FileTypeFilter.VIDEO -> files.filter { it.type.startsWith("video/") }
        }

    private fun sortFiles(
        files: List<FileObject>,
        field: FileSortField,
        order: FileSortOrder,
    ): List<FileObject> {
        val sorted = when (field) {
            FileSortField.NAME -> files.sortedBy { it.filename.lowercase() }
            FileSortField.DATE -> files.sortedBy { it.createdAt ?: "" }
            FileSortField.SIZE -> files.sortedBy { it.bytes }
            FileSortField.TYPE -> files.sortedBy { it.type }
        }
        return if (order == FileSortOrder.DESCENDING) sorted.reversed() else sorted
    }

    private fun FileObject.buildImagePreviewUrl(): String? {
        if (!type.startsWith("image/")) return null
        return buildFileUrl()
    }

    private fun FileObject.buildFileUrl(): String {
        val baseUrl = serverDataStore.getBaseUrl().trimEnd('/')
        val url = if (filepath.startsWith("http://") || filepath.startsWith("https://")) {
            filepath
        } else if (filepath.isNotBlank()) {
            "$baseUrl$filepath"
        } else {
            "$baseUrl/api/files/download/${user ?: ""}/$fileId"
        }
        Logger.d { "File URL for $filename ($fileId): $url" }
        return url
    }

    private fun FileObject.toDisplayData(): FileDisplayData {
        return FileDisplayData(
            fileId = fileId,
            filename = filename,
            type = type,
            formattedSize = formatFileSize(bytes),
            createdAt = createdAt,
            previewUrl = buildImagePreviewUrl(),
        )
    }

    private fun FileObject.toPreviewDisplayData(): FilePreviewDisplayData {
        return FilePreviewDisplayData(
            fileId = fileId,
            filename = filename,
            type = type,
            formattedSize = formatFileSize(bytes),
            createdAt = createdAt,
            source = source,
            userId = user,
        )
    }

    private inline fun updateTransient(crossinline transform: TransientState.() -> TransientState) {
        _transientState.update { it.transform() }
    }
}

/**
 * Precomputed filtered + sorted display list, recomputed only when files/filter/sort change.
 * Carries the filter/sort inputs it was built from so the UI-state combine doesn't have to
 * re-subscribe to those flows just to echo them back.
 */
private data class DisplayList(
    val files: List<FileDisplayData>,
    val hasFiles: Boolean,
    val filter: FileTypeFilter,
    val sortField: FileSortField,
    val sortOrder: FileSortOrder,
)

/** Sort field + order as one unit so a sort change is a single atomic emission. */
private data class SortSpec(val field: FileSortField, val order: FileSortOrder)

private val DefaultSort = SortSpec(FileSortField.DATE, FileSortOrder.DESCENDING)

private data class TransientState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isUploading: Boolean = false,
    val uploadFilename: String = "",
    val uploadProgress: Float? = null,
    val error: String? = null,
    val isSelectionMode: Boolean = false,
    val isPickerMode: Boolean = false,
    val selectedFileIds: Set<String> = emptySet(),
    val previewFile: FilePreviewDisplayData? = null,
    val mediaPreview: MediaPreviewState? = null,
)
