package com.garfiec.librechat.feature.files.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.FileObject
import com.garfiec.librechat.feature.files.platform.FileReader
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fileRepository = mockk<FileRepository>(relaxed = true)
    private val fileReader = mockk<FileReader>(relaxed = true)
    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)

    // In-memory backing for the persisted sort/view-mode so setters round-trip through the flows.
    private val filesViewModeFlow = MutableStateFlow<String?>(null)
    private val filesSortFieldFlow = MutableStateFlow<String?>(null)
    private val filesSortOrderFlow = MutableStateFlow<String?>(null)

    private lateinit var viewModel: FilesViewModel

    private val testFiles = listOf(
        FileObject(
            fileId = "file-1",
            filename = "document.pdf",
            filepath = "/files/user1/document.pdf",
            type = "application/pdf",
            bytes = 1024L,
            createdAt = "2026-02-19T10:00:00.000Z",
        ),
        FileObject(
            fileId = "file-2",
            filename = "photo.jpg",
            filepath = "/images/user1/photo.jpg",
            type = "image/jpeg",
            bytes = 2048L,
            createdAt = "2026-02-18T10:00:00.000Z",
        ),
        FileObject(
            fileId = "file-3",
            filename = "song.mp3",
            filepath = "/audio/user1/song.mp3",
            type = "audio/mpeg",
            bytes = 4096L,
            createdAt = "2026-02-17T10:00:00.000Z",
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { fileRepository.getFiles() } returns Result.Success(testFiles)
        every { serverDataStore.getBaseUrl() } returns "https://chat.example.com"
        every { settingsDataStore.filesViewMode } returns filesViewModeFlow
        every { settingsDataStore.filesSortField } returns filesSortFieldFlow
        every { settingsDataStore.filesSortOrder } returns filesSortOrderFlow
        coEvery { settingsDataStore.setFilesViewMode(any()) } answers { filesViewModeFlow.value = firstArg() }
        coEvery { settingsDataStore.setFilesSort(any(), any()) } answers {
            filesSortFieldFlow.value = firstArg()
            filesSortOrderFlow.value = secondArg()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = FilesViewModel(
        fileRepository = fileRepository,
        fileReader = fileReader,
        serverDataStore = serverDataStore,
        settingsDataStore = settingsDataStore,
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `initial state loads files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayFiles).hasSize(3)
        assertThat(state.isLoading).isFalse()
        assertThat(state.hasFiles).isTrue()
    }

    @Test
    fun `loadFiles error shows error message`() = runTest {
        coEvery { fileRepository.getFiles() } returns Result.Error(message = "Network error")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("Network error")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `empty file list shows hasFiles false`() = runTest {
        coEvery { fileRepository.getFiles() } returns Result.Success(emptyList())

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasFiles).isFalse()
        assertThat(viewModel.uiState.value.displayFiles).isEmpty()
    }

    @Test
    fun `setFilter with IMAGES shows only image files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFilter(FileTypeFilter.IMAGES)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayFiles).hasSize(1)
        assertThat(state.displayFiles[0].fileId).isEqualTo("file-2")
        assertThat(state.selectedFilter).isEqualTo(FileTypeFilter.IMAGES)
    }

    @Test
    fun `setFilter with DOCUMENTS shows only document files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFilter(FileTypeFilter.DOCUMENTS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayFiles).hasSize(1)
        assertThat(state.displayFiles[0].fileId).isEqualTo("file-1")
    }

    @Test
    fun `setFilter with AUDIO shows only audio files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFilter(FileTypeFilter.AUDIO)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.displayFiles).hasSize(1)
        assertThat(viewModel.uiState.value.displayFiles[0].fileId).isEqualTo("file-3")
    }

    @Test
    fun `setFilter with ALL shows all files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFilter(FileTypeFilter.IMAGES)
        advanceUntilIdle()
        viewModel.setFilter(FileTypeFilter.ALL)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.displayFiles).hasSize(3)
    }

    @Test
    fun `deleteFile removes file from list on success`() = runTest {
        coEvery { fileRepository.deleteFiles(any()) } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteFile("file-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayFiles).hasSize(2)
        assertThat(state.displayFiles.any { it.fileId == "file-1" }).isFalse()
    }

    @Test
    fun `deleteFile error shows error message`() = runTest {
        coEvery { fileRepository.deleteFiles(any()) } returns
            Result.Error(message = "Delete failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteFile("file-1")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Delete failed")
        assertThat(viewModel.uiState.value.displayFiles).hasSize(3)
    }

    @Test
    fun `refresh reloads files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isRefreshing).isFalse()
        coVerify(exactly = 2) { fileRepository.getFiles() }
    }

    @Test
    fun `dismissError clears error state`() = runTest {
        coEvery { fileRepository.getFiles() } returns Result.Error(message = "Error")

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()

        viewModel.dismissError()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `enterSelectionMode enables selection with initial file`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterSelectionMode("file-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isSelectionMode).isTrue()
        assertThat(state.selectedFileIds).containsExactly("file-1")
    }

    @Test
    fun `toggleFileSelection adds and removes files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterSelectionMode("file-1")
        advanceUntilIdle()

        viewModel.toggleFileSelection("file-2")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.selectedFileIds).containsExactly("file-1", "file-2")

        viewModel.toggleFileSelection("file-1")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.selectedFileIds).containsExactly("file-2")
    }

    @Test
    fun `toggleFileSelection exits selection mode when last file deselected`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterSelectionMode("file-1")
        advanceUntilIdle()

        viewModel.toggleFileSelection("file-1")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isSelectionMode).isFalse()
        assertThat(viewModel.uiState.value.selectedFileIds).isEmpty()
    }

    @Test
    fun `exitSelectionMode clears selection`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterSelectionMode("file-1")
        advanceUntilIdle()

        viewModel.exitSelectionMode()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isSelectionMode).isFalse()
        assertThat(viewModel.uiState.value.selectedFileIds).isEmpty()
    }

    @Test
    fun `deleteSelected removes selected files on success`() = runTest {
        coEvery { fileRepository.deleteFiles(any()) } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterSelectionMode("file-1")
        advanceUntilIdle()
        viewModel.toggleFileSelection("file-2")
        advanceUntilIdle()

        viewModel.deleteSelected()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayFiles).hasSize(1)
        assertThat(state.displayFiles[0].fileId).isEqualTo("file-3")
        assertThat(state.isSelectionMode).isFalse()
    }

    @Test
    fun `toggleViewMode switches between list and grid`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.viewMode).isEqualTo(FileViewMode.LIST)

        viewModel.toggleViewMode()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.viewMode).isEqualTo(FileViewMode.GRID)

        viewModel.toggleViewMode()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.viewMode).isEqualTo(FileViewMode.LIST)
    }

    @Test
    fun `toggleViewMode persists the new mode`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleViewMode()
        advanceUntilIdle()

        coVerify { settingsDataStore.setFilesViewMode(FileViewMode.GRID.toStorageString()) }
    }

    @Test
    fun `persisted view mode hydrates initial state`() = runTest {
        filesViewModeFlow.value = FileViewMode.GRID.toStorageString()

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.viewMode).isEqualTo(FileViewMode.GRID)
    }

    @Test
    fun `setSort updates sort field and order`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setSort(FileSortField.NAME, FileSortOrder.ASCENDING)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.sortField).isEqualTo(FileSortField.NAME)
        assertThat(state.sortOrder).isEqualTo(FileSortOrder.ASCENDING)
    }

    @Test
    fun `setSort persists the field and order`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setSort(FileSortField.NAME, FileSortOrder.ASCENDING)
        advanceUntilIdle()

        coVerify {
            settingsDataStore.setFilesSort(
                FileSortField.NAME.toStorageString(),
                FileSortOrder.ASCENDING.toStorageString(),
            )
        }
    }

    @Test
    fun `persisted sort hydrates initial state`() = runTest {
        filesSortFieldFlow.value = FileSortField.NAME.toStorageString()
        filesSortOrderFlow.value = FileSortOrder.ASCENDING.toStorageString()

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.sortField).isEqualTo(FileSortField.NAME)
        assertThat(state.sortOrder).isEqualTo(FileSortOrder.ASCENDING)
    }

    @Test
    fun `filter is not persisted across sessions`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setFilter(FileTypeFilter.IMAGES)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.selectedFilter).isEqualTo(FileTypeFilter.IMAGES)

        // A fresh ViewModel (new app session) starts back at ALL — the filter was never persisted.
        val next = createViewModel()
        advanceUntilIdle()
        assertThat(next.uiState.value.selectedFilter).isEqualTo(FileTypeFilter.ALL)
    }

    @Test
    fun `downloadFileBytes returns null for blank userId`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.downloadFileBytes("file-1", null)
        assertThat(result).isNull()
    }

    @Test
    fun `downloadFileBytes returns bytes on success`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { fileRepository.downloadFile("user-1", "file-1") } returns Result.Success(bytes)

        viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.downloadFileBytes("file-1", "user-1")
        assertThat(result).isEqualTo(bytes)
    }

    @Test
    fun `downloadFileBytes returns null on error`() = runTest {
        coEvery { fileRepository.downloadFile("user-1", "file-1") } returns
            Result.Error(message = "Not found")

        viewModel = createViewModel()
        advanceUntilIdle()

        val result = viewModel.downloadFileBytes("file-1", "user-1")
        assertThat(result).isNull()
    }

    @Test
    fun `selectAll selects all visible files`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterEditMode()
        advanceUntilIdle()
        viewModel.selectAll()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedFileIds)
            .containsExactly("file-1", "file-2", "file-3")
    }

    @Test
    fun `selectAll keeps picks made under a different filter`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterPickerMode()
        // Pick the image while the Images filter is active...
        viewModel.setFilter(FileTypeFilter.IMAGES)
        advanceUntilIdle()
        viewModel.toggleFileSelection("file-2")
        // ...then switch to Documents and Select All. The image pick must survive.
        viewModel.setFilter(FileTypeFilter.DOCUMENTS)
        advanceUntilIdle()
        viewModel.selectAll()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedFileIds)
            .containsExactly("file-1", "file-2")
    }

    @Test
    fun `enterPickerMode enables sticky empty selection`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterPickerMode()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isPickerMode).isTrue()
        assertThat(state.isSelectionMode).isTrue()
        assertThat(state.selectedFileIds).isEmpty()
    }

    @Test
    fun `picker mode keeps selection mode when last file deselected`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterPickerMode()
        advanceUntilIdle()
        viewModel.toggleFileSelection("file-1")
        advanceUntilIdle()
        viewModel.toggleFileSelection("file-1")
        advanceUntilIdle()

        // Unlike the delete flow, emptying the picker selection must not exit selection mode.
        assertThat(viewModel.uiState.value.isPickerMode).isTrue()
        assertThat(viewModel.uiState.value.isSelectionMode).isTrue()
        assertThat(viewModel.uiState.value.selectedFileIds).isEmpty()
    }

    @Test
    fun `confirmSelection returns the picked file objects`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterPickerMode()
        advanceUntilIdle()
        viewModel.toggleFileSelection("file-1")
        viewModel.toggleFileSelection("file-3")
        advanceUntilIdle()

        val picked = viewModel.confirmSelection()
        assertThat(picked.map { it.fileId }).containsExactly("file-1", "file-3")
    }

    @Test
    fun `confirmSelection is empty when nothing picked`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enterPickerMode()
        advanceUntilIdle()

        assertThat(viewModel.confirmSelection()).isEmpty()
    }
}
