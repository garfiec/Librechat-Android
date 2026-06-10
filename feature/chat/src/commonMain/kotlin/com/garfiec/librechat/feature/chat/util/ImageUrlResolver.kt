package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * Single source of truth for turning a message's image reference into a loadable URL.
 *
 * Every image surface — the inline renderers (`MessageFiles`, `SharedContentParts`), the
 * image-gen tool-call parser (`ToolCallParsing`), and the full-screen media collector
 * ([extractBranchMedia]) — calls these, so the viewer always resolves the exact URL the message
 * rendered. Changing how images resolve here changes every place at once; no per-call-site copy.
 */

/** Resolves a full URL for an attached [FileReference], handling relative paths. */
internal fun resolveFileReferenceUrl(file: FileReference, baseUrl: String): String? =
    resolveImageUrl(file.filepath, file.fileId, baseUrl, includeBareSlash = true)

/**
 * Resolves a full URL for an inline `image_file` content part.
 *
 * [includeBareSlash] is `false` here: the content-part form maps a bare absolute path (e.g.
 * `/foo/x.png`, not `/images/...`) through `/api/files/`, matching how `ContentPartRenderer` has
 * always rendered these. A [FileReference] instead serves it directly off `baseUrl`.
 */
internal fun resolveImageFilePartUrl(part: MessageContentPart, baseUrl: String): String? =
    resolveImageUrl(part.imageFile?.filepath, part.imageFile?.fileId, baseUrl, includeBareSlash = false)

/**
 * Resolves a full URL for an image-gen tool-call [Attachment].
 *
 * Differs from the content-part forms only in the relative-path fallback: a bare relative path is
 * served directly off `baseUrl` ([relativePathPrefix] = `/`), not through `/api/files/`, matching
 * how `parseImageGenResult` has always built generated-image URLs.
 */
internal fun resolveAttachmentUrl(attachment: Attachment?, baseUrl: String): String? {
    if (attachment == null) return null
    return resolveImageUrl(
        attachment.filepath,
        attachment.fileId,
        baseUrl,
        includeBareSlash = true,
        relativePathPrefix = "/",
    )
}

/**
 * Maps a [filepath]/[fileId] pair to a loadable URL. The two flags capture the only differences
 * between the call sites, so the branches can't silently drift apart:
 *  - [includeBareSlash]: route any bare absolute path (`/foo`) through `baseUrl` directly.
 *  - [relativePathPrefix]: how a bare relative path joins `baseUrl` (`/api/files/` vs `/`).
 */
private fun resolveImageUrl(
    filepath: String?,
    fileId: String?,
    baseUrl: String,
    includeBareSlash: Boolean,
    relativePathPrefix: String = "/api/files/",
): String? {
    if (filepath != null) {
        return when {
            filepath.startsWith("http") -> filepath
            filepath.startsWith("/images/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
            includeBareSlash && filepath.startsWith("/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
            baseUrl.isNotBlank() -> "$baseUrl$relativePathPrefix$filepath"
            else -> filepath
        }
    }
    if (fileId != null && baseUrl.isNotBlank()) {
        return "$baseUrl/api/files/$fileId"
    }
    return null
}
