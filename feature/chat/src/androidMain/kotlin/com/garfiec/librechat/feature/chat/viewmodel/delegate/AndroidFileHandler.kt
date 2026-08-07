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

    override fun describe(platformRefs: List<Any>): List<PickedFile> =
        delegate.describe(platformRefs.filterIsInstance<Uri>())

    override fun onFilesSelected(files: List<RoutedFile>) = delegate.onFilesSelected(files)

    override fun removeFile(file: AttachedFile) = delegate.removeFile(file)
    override fun retryUpload(file: AttachedFile) = delegate.retryUpload(file)
    override suspend fun waitForUploadsAndSend(text: String, doSend: (String) -> Unit) =
        delegate.waitForUploadsAndSend(text, doSend)
    override fun hasPendingUploads(): Boolean = delegate.hasPendingUploads()
    override fun clearAttachedFiles() = delegate.clearAttachedFiles()
    override fun restoreAttachedFiles(files: List<AttachedFile>) = delegate.restoreAttachedFiles(files)
    override fun addPreUploadedFiles(files: List<AttachedFile>) = delegate.addPreUploadedFiles(files)
}
