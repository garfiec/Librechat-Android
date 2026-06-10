package com.garfiec.librechat.core.common.media

/**
 * Detects an image's MIME type from its magic bytes (file signature).
 *
 * File extensions and declared content types lie; the bytes don't. This is the single canonical
 * sniffer shared by image upload (`feature:chat`) and the media viewer's save/share
 * (`core:ui`), so both recognize the same format set and a saved/shared file always gets the
 * right extension + MIME.
 *
 * Supported binary formats: JPEG, PNG, GIF, WebP, BMP, TIFF, HEIF/HEIC, AVIF, ICO.
 * (Text-based formats like SVG are not magic-byte detectable and are handled by the caller.)
 *
 * @return the detected MIME type, or `null` if the bytes match no recognized image signature.
 */
fun detectImageMimeType(bytes: ByteArray): String? {
    if (bytes.size < 12) return null

    // JPEG: FF D8 FF
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }

    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
    ) {
        return "image/png"
    }

    // GIF: "GIF87a" or "GIF89a"
    if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() &&
        (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
        bytes[5] == 0x61.toByte()
    ) {
        return "image/gif"
    }

    // WebP: "RIFF" at offset 0 and "WEBP" at offset 8
    if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    ) {
        return "image/webp"
    }

    // BMP: "BM"
    if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) {
        return "image/bmp"
    }

    // TIFF: "II" (little-endian) or "MM" (big-endian) followed by 42
    if ((bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) ||
        (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte())
    ) {
        return "image/tiff"
    }

    // HEIF/HEIC and AVIF: ftyp box at offset 4, brand at offset 8
    if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
        bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
    ) {
        val brand = bytes.copyOfRange(8, 12).decodeToString()
        return when {
            brand.startsWith("heic") || brand.startsWith("heix") ||
                brand.startsWith("heim") || brand.startsWith("heis") ||
                brand.startsWith("mif1") -> "image/heic"
            brand.startsWith("avif") || brand.startsWith("avis") -> "image/avif"
            else -> null
        }
    }

    // ICO: 00 00 01 00
    if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0x01.toByte() && bytes[3] == 0x00.toByte()
    ) {
        return "image/x-icon"
    }

    return null
}

/**
 * The canonical file extension (no leading dot) for an image MIME type, or `null` if unknown.
 *
 * Covers a few MIME types [detectImageMimeType] never returns itself (e.g. `image/heif`,
 * `image/svg+xml`) because callers also pass server- or OS-declared MIME types here, not only
 * magic-byte-sniffed ones.
 */
fun imageExtensionForMimeType(mimeType: String): String? = when (mimeType) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    "image/tiff" -> "tiff"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/avif" -> "avif"
    "image/x-icon" -> "ico"
    "image/svg+xml" -> "svg"
    else -> null
}
