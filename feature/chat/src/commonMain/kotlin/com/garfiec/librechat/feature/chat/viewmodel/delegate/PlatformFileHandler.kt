package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.feature.chat.components.AttachedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-abstracted file attachment handling.
 * Android: wraps FileAttachmentDelegate with ContentResolver/Uri logic.
 * iOS: no-op initially (file attachments not yet supported on iOS).
 */
interface PlatformFileHandler {
    val attachedFiles: StateFlow<List<AttachedFile>>
    var pendingUploadSendJob: Job?

    fun onFilesSelected(platformRefs: List<Any>)
    fun removeFile(file: AttachedFile)
    fun retryUpload(file: AttachedFile)
    suspend fun waitForUploadsAndSend(text: String, doSend: (String) -> Unit)
    fun hasPendingUploads(): Boolean
    fun clearAttachedFiles()

    /**
     * Replaces the attached-file tray with an already-uploaded snapshot (no re-upload). Used when
     * a queued message is pulled back into the composer for editing — the [AttachedFile]s carry
     * their original local uri, so the composer chips (and image thumbnails) render unchanged.
     */
    fun restoreAttachedFiles(files: List<AttachedFile>)
}

/**
 * Maps an uploaded [AttachedFile] to the [FileReference] the chat-send request carries. Callers
 * must pass only files whose upload completed (`fileId != null`).
 */
fun AttachedFile.toFileReference(): FileReference = FileReference(
    fileId = fileId,
    filename = name,
    filepath = filepath,
    type = type,
    width = width,
    height = height,
)
