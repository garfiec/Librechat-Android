package com.garfiec.librechat.feature.skills.components

import androidx.compose.runtime.Composable

/**
 * A document picked by the user, already resolved to in-memory bytes + name +
 * mime so the caller can multipart-upload it without a second platform read.
 */
data class PickedDocument(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
) {
    // Content-equality so Compose can compare; ByteArray needs explicit equals.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedDocument) return false
        return filename == other.filename &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * Cross-platform arbitrary-document picker for skill file attachments + import.
 * Generalizes the agent avatar/file picker precedent (image-only → any doc):
 * Android SAF (`ACTION_OPEN_DOCUMENT`) and iOS `UIDocumentPickerViewController`.
 * Duplicated here (not depended on from :feature:agents) to keep the
 * features-depend-on-:core-only rule intact.
 */
/**
 * Best-effort MIME type for a picked document's lowercase file extension, shared
 * by both platform pickers (on Android it's the fallback after the SAF
 * ContentResolver type; on iOS it's the only source). Returns null for unknown
 * extensions — the caller then falls back to a generic type.
 */
fun skillFileMimeFromExtension(ext: String): String? = when (ext) {
    "md", "markdown" -> "text/markdown"
    "zip" -> "application/zip"
    "skill" -> "application/zip"
    "json" -> "application/json"
    "yaml", "yml" -> "application/x-yaml"
    "txt" -> "text/plain"
    "csv" -> "text/csv"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "html", "htm" -> "text/html"
    else -> null
}

@Composable
expect fun rememberSkillFilePicker(
    onPick: (PickedDocument) -> Unit,
): SkillFilePicker

expect class SkillFilePicker {
    /** [mimeTypes] is an SAF filter on Android; iOS accepts any content type. */
    fun launch(mimeTypes: List<String>)
}
