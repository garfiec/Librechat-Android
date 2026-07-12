package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileObject(
    @SerialName("file_id") val fileId: String,
    @SerialName("temp_file_id") val tempFileId: String? = null,
    val filename: String,
    val filepath: String,
    val type: String,
    val bytes: Long,
    val source: String? = null,
    val user: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    // Deferred office-doc preview + storage/tenant metadata (v0.8.6). All
    // optional/forward-compat; surfaced by the office-preview poll.
    /** Extracted inline preview content (office-doc HTML or plain text). */
    val text: String? = null,
    /**
     * Format of [text]. `"html"` = sanitized full-document HTML safe to inject
     * into the office-artifact iframe; `"text"`/null = plain text that MUST be
     * escaped (rendered via the markdown/escaping path), never injected as HTML.
     */
    val textFormat: String? = null,
    /**
     * Preview lifecycle: `"pending"` (extraction in flight), `"ready"`
     * (text/textFormat populated, or binary/oversized with no text), `"failed"`.
     * null ⇒ treat as `"ready"` (legacy records / files with no preview).
     */
    val status: String? = null,
    /** Short machine-readable failure reason when [status] == `"failed"`. */
    val previewError: String? = null,
    val storageKey: String? = null,
    val storageRegion: String? = null,
    val tenantId: String? = null,
    val metadata: FileMetadata? = null,
)

/** `TFile.metadata` (v0.8.6) — code-sandbox file-cache references. */
@Serializable
data class FileMetadata(
    val fileIdentifier: String? = null,
    val codeEnvRef: CodeEnvRef? = null,
)

/**
 * `CodeEnvRef` (v0.8.6) — typed reference to a file in the code-execution
 * sandbox. Upstream is a discriminated union on [kind] (`skill`/`agent`/`user`)
 * where `version` is required only for `skill`; modeled flat here with
 * everything optional for forward-compat round-trip.
 */
@Serializable
data class CodeEnvRef(
    val kind: String? = null,
    val id: String? = null,
    @SerialName("storage_session_id") val storageSessionId: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    val version: Int? = null,
)

@Serializable
data class FileReference(
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val filepath: String? = null,
    val type: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    val source: String? = null,
)

@Serializable
data class Attachment(
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val filepath: String? = null,
    val type: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
    val toolCallId: String? = null,
    val expiresAt: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Deferred office-doc preview lifecycle (v0.8.6): `pending` while the server
     *  extracts HTML, `ready` once [text]/[textFormat] are set, `failed` on error.
     *  Null for ordinary attachments (treated as already-ready). */
    val status: String? = null,
    /** Extracted preview content for a ready office-doc attachment. */
    val text: String? = null,
    /** `"html"` (inject as sanitized HTML) or `"text"`/null (escape — never inject). */
    val textFormat: String? = null,
    /** Short machine-readable reason when [status] == `failed`. */
    val previewError: String? = null,
    /** Web-search sources, present only when [type] == `web_search` (no file_id/filename).
     *  Null for ordinary file attachments. */
    @SerialName("web_search") val webSearch: WebSearchData? = null,
)
