package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.util.IosFileData
import com.garfiec.librechat.feature.chat.util.IosImageData
import com.garfiec.librechat.feature.chat.viewmodel.ErrorOnlyHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * iOS implementation of [PlatformFileHandler].
 * Handles file uploads from clipboard paste and (future) photo picker.
 */
class IosFileHandler(
    private val handle: ErrorOnlyHandle,
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : PlatformFileHandler {
    private val _attachedFiles = MutableStateFlow<List<AttachedFile>>(emptyList())
    override val attachedFiles: StateFlow<List<AttachedFile>> = _attachedFiles.asStateFlow()
    override var pendingUploadSendJob: Job? = null

    override fun onFilesSelected(platformRefs: List<Any>) {
        platformRefs.forEach { ref ->
            when (ref) {
                is IosImageData -> uploadImage(ref)
                is IosFileData -> uploadFile(ref)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun uploadImage(imageData: IosImageData) {
        val uniqueId = Uuid.random().toString()

        val pendingFile = AttachedFile(
            uri = uniqueId,
            name = imageData.filename,
            isImage = true,
            uploadProgress = null,
            type = imageData.mimeType,
        )
        _attachedFiles.update { it + pendingFile }

        handle.scope.launch(ioDispatcher) {
            try {
                val state = handle.state
                val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
                val fileId = Uuid.random().toString()

                val result = fileRepository.uploadFile(
                    bytes = imageData.bytes,
                    filename = imageData.filename,
                    type = imageData.mimeType,
                    fileId = fileId,
                    endpoint = state.selectedEndpoint,
                    model = if (!isAgent) state.selectedModel else null,
                    agentId = if (isAgent) state.selectedModel else null,
                    messageFile = true,
                    width = imageData.width,
                    height = imageData.height,
                    onProgress = { pct ->
                        _attachedFiles.update { list ->
                            list.map { f -> if (f.uri == uniqueId) f.copy(uploadProgress = pct) else f }
                        }
                    },
                )

                when (result) {
                    is Result.Success -> {
                        val fileObject = result.data
                        Logger.d { "IosFileHandler: upload success -- fileId=${fileObject.fileId}" }
                        _attachedFiles.update { list ->
                            list.map { f ->
                                if (f.uri == uniqueId) {
                                    f.copy(
                                        uploadProgress = 1f,
                                        fileId = fileObject.fileId,
                                        filepath = fileObject.filepath,
                                        type = fileObject.type,
                                        width = fileObject.width,
                                        height = fileObject.height,
                                    )
                                } else {
                                    f
                                }
                            }
                        }
                    }
                    is Result.Error -> {
                        Logger.e(result.exception) { "IosFileHandler: upload failed -- ${result.message}" }
                        _attachedFiles.update { list ->
                            list.map { f ->
                                if (f.uri == uniqueId) f.copy(uploadFailed = true, uploadProgress = null) else f
                            }
                        }
                        handle.setError("Failed to upload ${imageData.filename}: ${result.message ?: "Unknown error"}")
                    }
                    is Result.Loading -> { /* unexpected */ }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "IosFileHandler: unexpected exception for ${imageData.filename}" }
                _attachedFiles.update { list ->
                    list.map { f ->
                        if (f.uri == uniqueId) f.copy(uploadFailed = true, uploadProgress = null) else f
                    }
                }
                handle.setError("Failed to upload ${imageData.filename}: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun uploadFile(fileData: IosFileData) {
        val uniqueId = Uuid.random().toString()

        val pendingFile = AttachedFile(
            uri = uniqueId,
            name = fileData.filename,
            isImage = false,
            uploadProgress = null,
            type = fileData.mimeType,
        )
        _attachedFiles.update { it + pendingFile }

        handle.scope.launch(ioDispatcher) {
            try {
                val state = handle.state
                val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
                val fileId = Uuid.random().toString()

                val result = fileRepository.uploadFile(
                    bytes = fileData.bytes,
                    filename = fileData.filename,
                    type = fileData.mimeType,
                    fileId = fileId,
                    endpoint = state.selectedEndpoint,
                    model = if (!isAgent) state.selectedModel else null,
                    agentId = if (isAgent) state.selectedModel else null,
                    messageFile = true,
                    onProgress = { pct ->
                        _attachedFiles.update { list ->
                            list.map { f -> if (f.uri == uniqueId) f.copy(uploadProgress = pct) else f }
                        }
                    },
                )

                when (result) {
                    is Result.Success -> {
                        val fileObject = result.data
                        Logger.d { "IosFileHandler: file upload success -- fileId=${fileObject.fileId}" }
                        _attachedFiles.update { list ->
                            list.map { f ->
                                if (f.uri == uniqueId) {
                                    f.copy(
                                        uploadProgress = 1f,
                                        fileId = fileObject.fileId,
                                        filepath = fileObject.filepath,
                                        type = fileObject.type,
                                    )
                                } else {
                                    f
                                }
                            }
                        }
                    }
                    is Result.Error -> {
                        Logger.e(result.exception) { "IosFileHandler: file upload failed -- ${result.message}" }
                        _attachedFiles.update { list ->
                            list.map { f ->
                                if (f.uri == uniqueId) f.copy(uploadFailed = true, uploadProgress = null) else f
                            }
                        }
                        handle.setError("Failed to upload ${fileData.filename}: ${result.message ?: "Unknown error"}")
                    }
                    is Result.Loading -> { /* unexpected */ }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "IosFileHandler: unexpected exception for ${fileData.filename}" }
                _attachedFiles.update { list ->
                    list.map { f ->
                        if (f.uri == uniqueId) f.copy(uploadFailed = true, uploadProgress = null) else f
                    }
                }
                handle.setError("Failed to upload ${fileData.filename}: ${e.message}")
            }
        }
    }

    override fun removeFile(file: AttachedFile) {
        _attachedFiles.update { list -> list.filter { it.uri != file.uri } }
    }

    override fun retryUpload(file: AttachedFile) {
        // Can't retry clipboard paste — user must paste again
        removeFile(file)
    }

    override suspend fun waitForUploadsAndSend(text: String, doSend: (String) -> Unit) =
        _attachedFiles.awaitUploadsThenSend(text, setError = handle::setError, doSend = doSend)

    override fun hasPendingUploads(): Boolean =
        _attachedFiles.value.any { it.fileId == null && !it.uploadFailed }

    override fun clearAttachedFiles() {
        _attachedFiles.update { emptyList() }
    }

    override fun restoreAttachedFiles(files: List<AttachedFile>) {
        _attachedFiles.update { files }
    }

    override fun addPreUploadedFiles(files: List<AttachedFile>) {
        _attachedFiles.update { it.appendDedupedByFileId(files) }
    }
}
