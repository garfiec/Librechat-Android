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

/**
 * Resolves the effective per-file size limit (in **bytes**) for [endpoint], mirroring the web
 * client's `getEndpointFileConfig`: the per-endpoint entry wins, falling back to the special
 * `"default"` endpoint entry. The server reports these limits in bytes (`mbToBytes`).
 *
 * Resolution order matters: the served `/api/files/config` object carries the per-file limits
 * **only** under `endpoints.<name>.fileSizeLimit` (with an `endpoints["default"]` catch-all) — its
 * top-level `fileSizeLimit` is never populated by the stock backend (that level exposes
 * `serverFileSizeLimit`, a separate server-wide cap we don't model). So an endpoint not explicitly
 * listed (openAI, google, bedrock, custom, …) resolves through the `"default"` entry, not the
 * always-null top-level field. The top-level [fileSizeLimit] is kept only as a last-ditch fallback
 * for a non-standard backend that happens to send it.
 *
 * Returns null when no limit is configured (older backend, or no default entry) so callers can
 * fail open. A limit of 0 — the server's marker for a disabled endpoint — is returned as-is;
 * enforcement callers should treat non-positive limits as "no check" since disabled uploads are
 * gated elsewhere.
 */
fun FileUploadConfig.effectiveFileSizeLimit(endpoint: String?): Long? {
    val endpointLimit = endpoint?.let { endpoints[it]?.fileSizeLimit }
    val defaultLimit = endpoints[DEFAULT_ENDPOINT_KEY]?.fileSizeLimit
    return endpointLimit ?: defaultLimit ?: fileSizeLimit
}

/** Catch-all key in [FileUploadConfig.endpoints] the server uses for endpoints without an override. */
private const val DEFAULT_ENDPOINT_KEY = "default"
