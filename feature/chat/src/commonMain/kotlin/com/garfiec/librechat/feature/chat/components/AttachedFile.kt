package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.model.response.UploadRoute

@Immutable
data class AttachedFile(
    val uri: Any,
    val name: String,
    val isImage: Boolean = false,
    val uploadProgress: Float? = null,
    /** Server-assigned file ID after successful upload. Null while uploading. */
    val fileId: String? = null,
    /** Server file path returned from upload. */
    val filepath: String? = null,
    /** MIME type of the file. */
    val type: String? = null,
    /** Image width in pixels (if applicable). */
    val width: Int? = null,
    /** Image height in pixels (if applicable). */
    val height: Int? = null,
    /** Whether the upload has failed. */
    val uploadFailed: Boolean = false,
    /**
     * How this file was delivered — set once the upload commits to a mode, which is after the
     * magic-byte re-detection may have overridden the route chosen from the picker's MIME type.
     *
     * Null means "not decided here": a file attached by reference from the server library, or one
     * still being described. The server resolves the mode from its own record either way, so this
     * is a display value, not part of the send.
     */
    val route: UploadRoute? = null,
)
