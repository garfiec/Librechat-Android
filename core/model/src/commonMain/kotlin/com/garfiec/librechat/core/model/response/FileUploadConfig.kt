package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

@Serializable
data class FileUploadConfig(
    val fileLimit: Int? = null,
    val fileSizeLimit: Long? = null,
    val totalSizeLimit: Long? = null,
    val supportedMimeTypes: List<String> = emptyList(),
    val disabled: Boolean = false,
    /**
     * Per-endpoint overrides keyed by endpoint name (plus a `"default"` entry).
     * The server's `/api/files/config` returns this map; the web client resolves the
     * effective config for the selected endpoint from it (see data-provider
     * `getEndpointFileConfig`). Mobile uses it to gate the attach controls when an
     * endpoint sets `disabled = true`. Absent on older backends — callers fail open.
     */
    val endpoints: Map<String, EndpointFileConfig> = emptyMap(),
)

/**
 * Per-endpoint slice of the file-upload config. Only the fields mobile consults are
 * modeled; the response may carry more which is ignored here.
 */
@Serializable
data class EndpointFileConfig(
    val disabled: Boolean = false,
    val fileLimit: Int? = null,
    val fileSizeLimit: Long? = null,
    val supportedMimeTypes: List<String> = emptyList(),
)
