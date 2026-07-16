package com.garfiec.librechat.feature.chat.viewmodel.delegate

import android.content.Context
import android.net.Uri
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.extensions.formatByteSize
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.response.effectiveFileSizeLimit
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.util.detectMimeTypeFromBytes
import com.garfiec.librechat.feature.chat.util.fixFilenameExtension
import com.garfiec.librechat.feature.chat.util.guessMimeType
import com.garfiec.librechat.feature.chat.util.processImageForUpload
import com.garfiec.librechat.feature.chat.util.resolveFileName
import com.garfiec.librechat.feature.chat.viewmodel.ErrorOnlyHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class FileAttachmentDelegate(
    private val handle: ErrorOnlyHandle,
    private val appContext: Context,
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val _attachedFiles = MutableStateFlow<List<AttachedFile>>(emptyList())
    val attachedFiles: StateFlow<List<AttachedFile>> = _attachedFiles.asStateFlow()

    /** Job that waits for pending uploads before sending a message. */
    var pendingUploadSendJob: Job? = null

    /**
     * Called when the user selects files from Camera, Photos, or Files picker.
     * Immediately starts uploading each file to the server and tracks progress.
     */
    fun onFilesSelected(uris: List<Uri>) {
        uris.forEach { uri -> uploadFile(uri) }
    }

    private fun uploadFile(uri: Uri) {
        val context = appContext
        val contentResolver = context.contentResolver

        // Resolve filename and initial MIME type from URI metadata.
        // This is a preliminary type -- after reading bytes we'll verify it via magic bytes.
        val filename = resolveFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        val preliminaryMimeType = contentResolver.getType(uri) ?: guessMimeType(filename)
        val isImage = preliminaryMimeType.startsWith("image/")

        Logger.d { "uploadFile: uri=$uri, filename=$filename, preliminaryMimeType=$preliminaryMimeType, isImage=$isImage" }

        // Add to the pending list with "uploading" state
        val pendingFile = AttachedFile(
            uri = uri,
            name = filename,
            isImage = isImage,
            uploadProgress = null,
            type = preliminaryMimeType,
        )

        _attachedFiles.update { currentList -> currentList + pendingFile }

        handle.scope.launch(ioDispatcher) {
            try {
                // Read file bytes
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    Logger.e { "uploadFile: could not read bytes from URI: $uri" }
                    markUploadFailed(uri)
                    handle.setError("Failed to upload $filename: Could not read file")
                    return@launch
                }

                Logger.d { "uploadFile: read ${bytes.size} bytes from $filename" }

                // Detect actual MIME type from file content magic bytes.
                val detectedMimeType = detectMimeTypeFromBytes(bytes)
                var mimeType = if (detectedMimeType != null && detectedMimeType != preliminaryMimeType) {
                    Logger.w {
                        "uploadFile: MIME type mismatch for $filename -- ContentResolver " +
                            "reported '$preliminaryMimeType' but actual content is '$detectedMimeType'. Using detected type."
                    }
                    detectedMimeType
                } else {
                    preliminaryMimeType
                }

                // Snapshot the endpoint/model/config ONCE here and reuse it for both sizing and the
                // upload below. Sizing the file against one endpoint's limit but registering it
                // against a different one (if the user switched endpoints mid-encode) could upload an
                // oversized file that the new endpoint 413-rejects — so the file is sized and
                // registered against the same endpoint, even if that means not reflecting a switch
                // made while this upload was already in flight.
                val state = handle.state

                // Resolve the server's per-file size limit up front so we can both (a) let the image
                // re-encode downsample to fit it and (b) backstop-reject anything still over it.
                // Non-positive limits (0 = disabled endpoint, gated elsewhere) mean "no check".
                val sizeLimit = state.fileUploadConfig
                    ?.effectiveFileSizeLimit(state.selectedEndpoint)
                    ?.takeIf { it > 0 }

                // Re-encode images to PNG (server media-type workaround), downsampling to fit the
                // size limit where possible. This also yields the pixel dimensions in one decode, so
                // there's no separate bounds read below.
                var uploadBytes = bytes
                var imageWidth: Int? = null
                var imageHeight: Int? = null
                val actualIsImage = mimeType.startsWith("image/")
                if (actualIsImage) {
                    val processed = processImageForUpload(bytes, mimeType, maxEncodedBytes = sizeLimit)
                    if (processed != null) {
                        if (processed.reEncoded) {
                            Logger.d { "uploadFile: re-encoded image from $mimeType to ${processed.mimeType} (${bytes.size} -> ${processed.bytes.size} bytes)" }
                        }
                        uploadBytes = processed.bytes
                        mimeType = processed.mimeType
                        imageWidth = processed.width
                        imageHeight = processed.height
                    }
                }

                // Backstop the pre-check against the server's limit so an over-limit file fails fast
                // with a clear message instead of after a long upload the server rejects. Fires for
                // non-images and for images the re-encode couldn't shrink under the limit.
                if (sizeLimit != null && uploadBytes.size > sizeLimit) {
                    Logger.w { "uploadFile: $filename is ${uploadBytes.size} bytes, over server limit of $sizeLimit" }
                    markUploadFailed(uri)
                    // State only the limit — not the file's own size. The limit is the actionable
                    // number, and rounding both to the same MB precision could otherwise print a
                    // self-contradictory "X MB is larger than the X MB limit".
                    handle.setError("$filename is larger than the server limit of ${formatByteSize(sizeLimit)}.")
                    return@launch
                }

                // Rename the file extension to match the detected/re-encoded MIME type.
                val uploadFilename = fixFilenameExtension(filename, mimeType)
                if (uploadFilename != filename) {
                    Logger.d { "uploadFile: renamed file from '$filename' to '$uploadFilename' to match MIME type '$mimeType'" }
                }

                // Update the attached file's type and name if detection/re-encoding changed them.
                val needsTypeUpdate = mimeType != preliminaryMimeType
                val needsNameUpdate = uploadFilename != filename
                if (needsTypeUpdate || needsNameUpdate) {
                    val isImageType = mimeType.startsWith("image/")
                    _attachedFiles.update { currentList ->
                        currentList.map { f ->
                            if (f.uri == uri) {
                                f.copy(
                                    type = mimeType,
                                    isImage = isImageType,
                                    name = uploadFilename,
                                )
                            } else {
                                f
                            }
                        }
                    }
                }

                if (actualIsImage) {
                    Logger.d { "uploadFile: image dimensions ${imageWidth}x$imageHeight for $uploadFilename" }
                }

                // Generate a UUID for the file_id -- the backend requires this field
                val fileId = UUID.randomUUID().toString()

                // Upload to server with context about current endpoint/model, using the same
                // snapshot the file was sized against above (see the comment there).
                val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
                Logger.d { "uploadFile: sending to server -- fileId=$fileId, endpoint=${state.selectedEndpoint}, model=${state.selectedModel}, isAgent=$isAgent, mimeType=$mimeType, filename=$uploadFilename" }
                val result = fileRepository.uploadFile(
                    bytes = uploadBytes,
                    filename = uploadFilename,
                    type = mimeType,
                    fileId = fileId,
                    endpoint = state.selectedEndpoint,
                    model = if (!isAgent) state.selectedModel else null,
                    agentId = if (isAgent) state.selectedModel else null,
                    messageFile = true,
                    width = imageWidth,
                    height = imageHeight,
                    onProgress = { pct -> updateFileProgress(uri, pct) },
                )

                when (result) {
                    is Result.Success -> {
                        val fileObject = result.data
                        Logger.d { "uploadFile: success -- serverFileId=${fileObject.fileId}, filepath=${fileObject.filepath}, type=${fileObject.type}, ${fileObject.width}x${fileObject.height}" }
                        // Atomically update the attached file with server data
                        _attachedFiles.update { currentList ->
                            currentList.map { f ->
                                if (f.uri == uri) {
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
                        Logger.e(result.exception) { "uploadFile: server error -- ${result.message}" }
                        // Atomically mark failed and remove from list in one update
                        _attachedFiles.update { currentList ->
                            currentList.map { f ->
                                if (f.uri == uri) {
                                    f.copy(uploadFailed = true, uploadProgress = null)
                                } else {
                                    f
                                }
                            }
                        }
                        handle.setError("Failed to upload $filename: ${result.message ?: "Unknown error"}")
                    }
                    is Result.Loading -> {
                        Logger.w { "uploadFile: unexpected Result.Loading received for $filename" }
                    }
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation must propagate — never mark a cancelled
                // upload as failed (matches safeApiCall behavior in core/common).
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "uploadFile: unexpected exception for $filename" }
                markUploadFailed(uri)
                handle.setError("Failed to upload $filename: ${e.message}")
            }
        }
    }

    private fun updateFileProgress(uri: Uri, progress: Float) {
        _attachedFiles.update { currentList ->
            currentList.map { f ->
                if (f.uri == uri) f.copy(uploadProgress = progress) else f
            }
        }
    }

    private fun markUploadFailed(uri: Uri) {
        _attachedFiles.update { currentList ->
            currentList.map { f ->
                if (f.uri == uri) f.copy(uploadFailed = true, uploadProgress = null) else f
            }
        }
    }

    fun removeFile(file: AttachedFile) {
        _attachedFiles.update { currentList -> currentList.filter { it.uri != file.uri } }
    }

    fun retryUpload(file: AttachedFile) {
        _attachedFiles.update { currentList -> currentList.filter { it.uri != file.uri } }
        uploadFile(file.uri as Uri)
    }

    /**
     * Waits for all pending file uploads to complete (up to 30 seconds),
     * then proceeds with sending the message via the provided callback.
     */
    suspend fun waitForUploadsAndSend(text: String, doSend: (String) -> Unit) {
        // Poll _attachedFiles until all files have either completed or failed.
        // Timeout after 30 seconds to avoid hanging forever.
        val timeoutMs = 30_000L
        val pollIntervalMs = 200L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            val pending = _attachedFiles.value.any { it.fileId == null && !it.uploadFailed }
            if (!pending) break
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
        }
        val stillPending = _attachedFiles.value.count { it.fileId == null && !it.uploadFailed }
        if (stillPending > 0) {
            Logger.w { "waitForUploadsAndSend: timed out with $stillPending file(s) still uploading" }
        }
        doSend(text)
    }

    fun hasPendingUploads(): Boolean =
        _attachedFiles.value.any { it.fileId == null && !it.uploadFailed }

    fun clearAttachedFiles() {
        _attachedFiles.update { emptyList() }
    }

    fun restoreAttachedFiles(files: List<AttachedFile>) {
        _attachedFiles.update { files }
    }

    fun addPreUploadedFiles(files: List<AttachedFile>) {
        _attachedFiles.update { it.appendDedupedByFileId(files) }
    }
}
