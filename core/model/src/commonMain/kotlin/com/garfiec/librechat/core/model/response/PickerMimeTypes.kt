package com.garfiec.librechat.core.model.response

/**
 * Verbatim mirror of `fullMimeTypesList` in upstream `packages/data-provider/src/file-config.ts`
 * (its trailing `...excelFileTypes` spread inlined), in upstream order.
 *
 * Keep it a straight transcription so a `/sync-upstream` pass can diff the two lists directly;
 * anything mobile wants beyond upstream goes in [MOBILE_EXTRA_MIME_TYPES], not here.
 */
private val UPSTREAM_MIME_TYPES: List<String> = listOf(
    "text/x-c",
    "text/x-c++",
    "application/csv",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/html",
    "text/x-java",
    "application/json",
    "text/markdown",
    "application/pdf",
    "text/x-php",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/x-python",
    "text/x-script.python",
    "text/x-ruby",
    "text/x-tex",
    "text/plain",
    "text/css",
    "text/calendar",
    "text/vtt",
    "image/jpeg",
    "text/javascript",
    "image/gif",
    "image/png",
    "image/heic",
    "image/heif",
    "application/x-tar",
    "application/x-sh",
    "application/typescript",
    "application/sql",
    "application/yaml",
    "application/vnd.coffeescript",
    "application/xml",
    "application/zip",
    "application/x-zip-compressed",
    "application/x-parquet",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/vnd.oasis.opendocument.graphics",
    "image/svg",
    "image/svg+xml",
    // .eml email messages (added upstream in the 0.8.8 line).
    "message/rfc822",
    "video/mp4",
    "video/avi",
    "video/mov",
    "video/wmv",
    "video/flv",
    "video/webm",
    "video/mkv",
    "video/m4v",
    "video/3gp",
    "video/ogv",
    "audio/mp3",
    "audio/wav",
    "audio/ogg",
    "audio/m4a",
    "audio/aac",
    "audio/flac",
    "audio/wma",
    "audio/opus",
    "audio/mpeg",
    // ...excelFileTypes
    "application/vnd.ms-excel",
    "application/msexcel",
    "application/x-msexcel",
    "application/x-ms-excel",
    "application/x-excel",
    "application/x-dos_ms_excel",
    "application/xls",
    "application/x-xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

/**
 * Types absent from upstream's `fullMimeTypesList` that a stock backend nonetheless accepts, so an
 * allowlist naming one still translates into a usable picker filter:
 *
 * - `image/webp` — in upstream's `imageMimeTypes` regex, i.e. an accepted image upload.
 * - `text/csv` — upstream's own `.csv` extension mapping and the default `retrievalMimeTypesList`
 *   accept string both use it (`fullMimeTypesList` only carries the `application/csv` alias).
 */
private val MOBILE_EXTRA_MIME_TYPES: List<String> = listOf(
    "image/webp",
    "text/csv",
)

/**
 * The MIME types a stock LibreChat backend can be configured to accept.
 *
 * This list exists to *translate* an admin's `supportedMimeTypes` regex allowlist into the
 * concrete types a native file picker can filter on — a picker can't evaluate a regex. It is
 * not itself an allowlist: mobile never rejects an upload on the strength of this list, and
 * the server re-validates every upload regardless.
 */
private val KNOWN_MIME_TYPES: List<String> = UPSTREAM_MIME_TYPES + MOBILE_EXTRA_MIME_TYPES

/**
 * Resolves the `supportedMimeTypes` allowlist in effect for [endpoint], mirroring the
 * per-endpoint-then-`"default"` fallback used by [effectiveFileSizeLimit].
 */
fun FileUploadConfig.effectiveSupportedMimeTypes(endpoint: String?): List<String> {
    val endpointTypes = endpoint?.let { endpoints[it]?.supportedMimeTypes }?.takeIf { it.isNotEmpty() }
    val defaultTypes = endpoints[DEFAULT_ENDPOINT_KEY]?.supportedMimeTypes?.takeIf { it.isNotEmpty() }
    return endpointTypes ?: defaultTypes ?: supportedMimeTypes
}

/**
 * Translates the server's `supportedMimeTypes` regex allowlist into concrete MIME types a
 * native file picker can filter on.
 *
 * Returns an **empty list meaning "no restriction"** — the caller should fall back to `* / *`.
 * That is deliberately the answer for every case we can't represent faithfully:
 *
 * - no allowlist configured (the server accepts its built-in set);
 * - a permissive pattern such as `.*` or `^.*$`;
 * - a pattern that matches nothing in [KNOWN_MIME_TYPES], which means the admin allowed a type
 *   this list doesn't know about — narrowing the picker there would hide files the server would
 *   have happily accepted.
 *
 * Filtering is a UX convenience, never an enforcement point: an over-permissive picker only costs
 * the user a rejected upload, whereas an over-narrow one makes a legitimate file unpickable.
 */
fun FileUploadConfig.pickerMimeTypes(endpoint: String? = null): List<String> {
    val patterns = effectiveSupportedMimeTypes(endpoint)
    if (patterns.isEmpty()) return emptyList()

    val matched = LinkedHashSet<String>()
    for (pattern in patterns) {
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
        val hits = KNOWN_MIME_TYPES.filter { regex.containsMatchIn(it) }
        // A pattern matching every known type is permissive; one matching none is unrepresentable.
        if (hits.isEmpty() || hits.size == KNOWN_MIME_TYPES.size) return emptyList()
        matched += hits
    }
    return matched.toList()
}
