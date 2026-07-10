package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.response.FilePreviewResponse
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.viewmodel.OfficePreviewHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the deferred office-doc preview lifecycle (v0.8.6) for streaming
 * attachments — the live counterpart to the persisted `attachment.text`/`status`
 * that a reloaded message already carries.
 *
 * An office doc arrives on the SSE `attachment` event twice for one `file_id`:
 * first `status: "pending"` (no text), then `"ready"` (+ text/textFormat) or
 * `"failed"` (+ previewError). This delegate:
 *  - [onAttachment]: UPSERTS by `file_id` into the chat state's `streamingAttachments`
 *    — merging over an existing record rather than appending. NO-REGRESS: a
 *    terminal (`ready`/`failed`) record is never downgraded by a late `pending`
 *    for the same `file_id` (cross-turn file_id reuse), and a terminal incoming
 *    always wins.
 *  - [onStreamEnded]: if the stream closed while an office attachment is still
 *    `pending`, the SSE `ready` update may never arrive — so it polls
 *    `GET /api/files/:id/preview` (the bounded [FileRepository.pollFilePreview])
 *    and merges the resolved text/status back in. De-duplicated per `file_id`; a
 *    poll that resolves after the record already went terminal is ignored.
 *  - [reset]/[cancelPolls]: cancel all in-flight polls at run/conversation
 *    boundaries and on ViewModel clear so a poll never outlives the session.
 *
 * Non-office attachments are not the delegate's concern — the caller only routes
 * office-preview MIME types here; everything else keeps the plain append path.
 */
class OfficePreviewDelegate(
    private val handle: OfficePreviewHandle,
    private val fileRepository: FileRepository,
) {

    /** In-flight preview polls keyed by `file_id` (de-dup + cancellation). */
    private val activePolls = mutableMapOf<String, Job>()

    /** Upserts an office-doc attachment by `file_id` with the no-regress guard. */
    fun onAttachment(attachment: Attachment) {
        val fileId = attachment.fileId ?: return
        handle.update {
            content = content.copy(streamingAttachments = upsertByFileId(content.streamingAttachments, attachment))
        }
        // A terminal SSE update supersedes any in-flight poll for this file.
        if (attachment.status.isTerminal()) {
            activePolls.remove(fileId)?.cancel()
        }
    }

    /**
     * Called when the stream ends. Launches a bounded preview poll for every
     * office attachment still `pending` (and not already being polled), then
     * merges the resolved record back in. Idempotent per `file_id`.
     */
    fun onStreamEnded() {
        val pending = handle.state.streamingAttachments.filter {
            ArtifactType.isOfficePreviewMime(it.type) &&
                it.fileId != null &&
                it.status == FilePreviewResponse.STATUS_PENDING
        }
        for (attachment in pending) {
            val fileId = attachment.fileId ?: continue
            if (activePolls.containsKey(fileId)) continue
            activePolls[fileId] = handle.scope.launch {
                try {
                    when (val result = fileRepository.pollFilePreview(fileId)) {
                        is Result.Success -> mergePreviewResult(fileId, result.data)
                        is Result.Error ->
                            Logger.w(result.exception) { "Office preview poll failed for $fileId" }
                        is Result.Loading -> { /* never emitted by pollFilePreview */ }
                    }
                } finally {
                    activePolls.remove(fileId)
                }
            }
        }
    }

    /** Cancels all in-flight polls (run/conversation boundary). */
    fun reset() = cancelPolls()

    /** Cancels all in-flight polls (ViewModel clear). */
    fun cancelPolls() {
        activePolls.values.forEach { it.cancel() }
        activePolls.clear()
    }

    private fun mergePreviewResult(fileId: String, preview: FilePreviewResponse) {
        handle.update {
            val merged = content.streamingAttachments.map { att ->
                if (att.fileId != fileId) {
                    att
                } else {
                    // Don't regress a record that already went terminal via SSE.
                    if (att.status.isTerminal()) {
                        att
                    } else {
                        att.copy(
                            status = preview.status,
                            text = preview.text ?: att.text,
                            textFormat = preview.textFormat ?: att.textFormat,
                            previewError = preview.previewError ?: att.previewError,
                        )
                    }
                }
            }
            content = content.copy(streamingAttachments = merged)
        }
    }

    private fun String?.isTerminal(): Boolean = FilePreviewResponse.isTerminalStatus(this)

    /**
     * Merge-over upsert by `file_id`. Replaces an existing record with the same
     * `file_id`; appends when new. NO-REGRESS: if the existing record is terminal
     * and the incoming is `pending`, the existing terminal record is kept (a
     * stray late `pending` from cross-turn file_id reuse must not blank out a
     * resolved preview).
     */
    internal fun upsertByFileId(
        existing: List<Attachment>,
        incoming: Attachment,
    ): List<Attachment> {
        val fileId = incoming.fileId ?: return existing + incoming
        val index = existing.indexOfFirst { it.fileId == fileId }
        if (index < 0) return existing + incoming
        val current = existing[index]
        val resolved = if (current.status.isTerminal() && !incoming.status.isTerminal()) {
            current
        } else {
            incoming
        }
        return existing.toMutableList().also { it[index] = resolved }
    }
}
