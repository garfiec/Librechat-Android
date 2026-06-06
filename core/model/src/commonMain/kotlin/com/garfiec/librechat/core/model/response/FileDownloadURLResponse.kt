package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Response of `GET /api/files/download-url/:userId/:file_id` (v0.8.6,
 * upstream `FileDownloadURLResponse`). [url] is a direct/presigned CDN URL
 * (S3 presigned or CloudFront signed) the client can fetch bytes from without
 * proxying through the LibreChat server. The endpoint returns 501 for sources
 * with no direct-URL strategy (e.g. local storage) — callers fall back to the
 * `/download/:userId/:file_id` proxy in that case.
 *
 * [metadata] is `Partial<TFile>`; modeled as an opaque [JsonObject] since the
 * download path doesn't consume it.
 */
@Serializable
data class FileDownloadURLResponse(
    val url: String,
    val filename: String? = null,
    val type: String? = null,
    val metadata: JsonObject? = null,
)
