package com.garfiec.librechat.core.model.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `GET /api/files/:file_id/preview` (v0.8.6, upstream
 * `TFilePreview`). The deferred office-doc preview flow polls this until
 * [status] is terminal:
 *  - `pending` — HTML extraction still running; no [text].
 *  - `ready` — extraction done; [text] + [textFormat] populated iff inline
 *    content exists (binary/oversized files reach `ready` with no text =
 *    download-only).
 *  - `failed` — extraction errored or hit the ~60s ceiling; [previewError]
 *    carries the short reason (`timeout`, `parser-error`, `orphaned`).
 *
 * SECURITY: [text] may only be injected as HTML when [textFormat] == `"html"`.
 * For `"text"`/null it is plain text and MUST be escaped, never injected.
 */
@Serializable
data class FilePreviewResponse(
    @SerialName("file_id") val fileId: String,
    val status: String,
    val text: String? = null,
    val textFormat: String? = null,
    val previewError: String? = null,
) {
    val isTerminal: Boolean get() = isTerminalStatus(status)

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"

        /** True for a terminal preview lifecycle status (`ready`/`failed`). Shared
         *  by the instance [isTerminal] getter and callers that hold a raw status
         *  string (e.g. the streaming attachment delegate) rather than a response. */
        fun isTerminalStatus(status: String?): Boolean =
            status == STATUS_READY || status == STATUS_FAILED
    }
}
