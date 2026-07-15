package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.feature.chat.components.AttachedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /**
     * Appends already-uploaded files to the tray (no re-upload), deduping by `fileId` against
     * what's already attached. Used by the "From server" picker to reuse existing server files;
     * unlike [restoreAttachedFiles] it preserves any files the user already attached this compose.
     */
    fun addPreUploadedFiles(files: List<AttachedFile>)
}

/**
 * Appends [files] to an already-uploaded tray, deduping by `fileId` against what's already present
 * and dropping any file without a `fileId` (not yet uploaded). Shared by the platform handlers'
 * [PlatformFileHandler.addPreUploadedFiles] so Android and iOS can't drift apart.
 */
fun List<AttachedFile>.appendDedupedByFileId(files: List<AttachedFile>): List<AttachedFile> {
    val existingIds = mapNotNull { it.fileId }.toSet()
    return this + files.filter { it.fileId != null && it.fileId !in existingIds }
}

/**
 * User-facing message shown when a send is attempted while an attachment upload is still in flight.
 * English literal — VM-layer error strings are not yet routed through compose resources (tracked by
 * the "VM error strings i18n" cleanup backlog), so it can't use `stringResource` here.
 */
private const val STILL_UPLOADING_MESSAGE = "Attachment still uploading — wait for it to finish, then send"

/**
 * Polls this attached-file tray until every attachment has either finished uploading (`fileId`
 * assigned) or failed, or [timeoutMs] elapses, then either aborts or sends. `buildSendSpec` drops
 * files without a `fileId`, so sending while an upload is still in flight would fire a message
 * missing the file the user attached (or, for an image-only message, silently send nothing). If any
 * attachment is still in flight after the wait, [setError] surfaces [STILL_UPLOADING_MESSAGE] and the
 * send is skipped so the user can retry once it settles; otherwise [doSend] fires with [text].
 *
 * Shared by both platform handlers' [PlatformFileHandler.waitForUploadsAndSend] so Android and iOS
 * can't drift apart on the abort semantics or the message.
 */
suspend fun StateFlow<List<AttachedFile>>.awaitUploadsThenSend(
    text: String,
    setError: (String) -> Unit,
    doSend: (String) -> Unit,
    timeoutMs: Long = 30_000L,
    pollIntervalMs: Long = 200L,
) {
    var elapsed = 0L
    while (elapsed < timeoutMs) {
        if (value.none { it.fileId == null && !it.uploadFailed }) break
        delay(pollIntervalMs)
        elapsed += pollIntervalMs
    }
    val stillPending = value.count { it.fileId == null && !it.uploadFailed }
    if (stillPending > 0) {
        Logger.w { "awaitUploadsThenSend: aborting send — $stillPending file(s) still uploading" }
        setError(STILL_UPLOADING_MESSAGE)
        return
    }
    doSend(text)
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
