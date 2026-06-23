package com.garfiec.librechat.feature.chat.viewmodel.delegate

import android.net.Uri
import com.garfiec.librechat.feature.chat.components.AttachedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Android implementation of [PlatformFileHandler] that wraps [FileAttachmentDelegate].
 */
class AndroidFileHandler(
    private val delegate: FileAttachmentDelegate,
) : PlatformFileHandler {

    override val attachedFiles: StateFlow<List<AttachedFile>> get() = delegate.attachedFiles
    override var pendingUploadSendJob: Job?
        get() = delegate.pendingUploadSendJob
        set(value) { delegate.pendingUploadSendJob = value }

    @Suppress("UNCHECKED_CAST")
    override fun onFilesSelected(platformRefs: List<Any>) {
        delegate.onFilesSelected(platformRefs as List<Uri>)
    }

    override fun removeFile(file: AttachedFile) = delegate.removeFile(file)
    override fun retryUpload(file: AttachedFile) = delegate.retryUpload(file)
    override suspend fun waitForUploadsAndSend(text: String, doSend: (String) -> Unit) =
        delegate.waitForUploadsAndSend(text, doSend)
    override fun hasPendingUploads(): Boolean = delegate.hasPendingUploads()
    override fun clearAttachedFiles() = delegate.clearAttachedFiles()
    override fun restoreAttachedFiles(files: List<AttachedFile>) = delegate.restoreAttachedFiles(files)
    override fun addPreUploadedFiles(files: List<AttachedFile>) = delegate.addPreUploadedFiles(files)
}
