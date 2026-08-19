package com.garfiec.librechat.feature.files.platform

/**
 * Shared extension-to-MIME-type mapping used by both platforms.
 * iOS uses this as the primary resolver; Android uses it as a fallback
 * when ContentResolver.getType() returns null.
 */
object CommonMimeTypes {

    private val EXTENSION_TO_MIME = mapOf(
        // Images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        // Documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        // Resolution only, deliberately ungated: a picked .potx must carry its real type on every
        // server (the offer is what's version-gated — see PickerMimeTypes).
        "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "md" to "text/markdown",
        "rtf" to "application/rtf",
        // Code / data
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "application/x-yaml",
        "yml" to "application/x-yaml",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "ts" to "application/typescript",
        "py" to "text/x-python",
        "kt" to "text/x-kotlin",
        "java" to "text/x-java-source",
        "swift" to "text/x-swift",
        "c" to "text/x-c",
        "cpp" to "text/x-c++src",
        "h" to "text/x-c",
        "rs" to "text/x-rust",
        "go" to "text/x-go",
        "rb" to "text/x-ruby",
        "sh" to "application/x-sh",
        "sql" to "application/sql",
        // Audio
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        // Video
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        // Archives
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
    )

    /**
     * Returns the MIME type for the given file extension, or null if unknown.
     * Extension matching is case-insensitive.
     */
    fun fromExtension(extension: String): String? =
        EXTENSION_TO_MIME[extension.lowercase()]
}
