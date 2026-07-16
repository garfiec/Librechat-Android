package com.garfiec.librechat.feature.chat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.media.detectImageMimeType
import com.garfiec.librechat.core.common.media.imageExtensionForMimeType
import java.io.ByteArrayOutputStream

/**
 * Resolves a human-readable filename from a content URI using the ContentResolver.
 * Falls back to the last path segment if DISPLAY_NAME is not available.
 */
internal fun resolveFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
    }
    return uri.lastPathSegment
}

/**
 * Guesses MIME type from file extension. Defaults to application/octet-stream.
 */
internal fun guessMimeType(filename: String): String {
    val extension = filename.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

/**
 * Detects the actual MIME type of image data by inspecting magic bytes (file signature).
 *
 * This is critical because Android's ContentResolver.getType() and file extension-based
 * guessing can return incorrect MIME types. For example, a PNG screenshot might have a
 * .jpg extension, or a WebP image shared from another app may be reported as image/jpeg.
 * When the declared MIME type doesn't match the actual data, AI providers (e.g. Anthropic)
 * reject the image with a 400 error.
 *
 * Returns the detected MIME type for known image formats, or null if the bytes don't
 * match any recognized image signature.
 *
 * Delegates to the canonical [detectImageMimeType] in `core:common` (shared with the media
 * viewer's save/share path) so both surfaces recognize the same format set.
 */
internal fun detectMimeTypeFromBytes(bytes: ByteArray): String? = detectImageMimeType(bytes)

/**
 * Returns the canonical file extension for a given MIME type.
 * Used to ensure the filename extension matches the actual content type.
 */
internal fun extensionForMimeType(mimeType: String): String? = imageExtensionForMimeType(mimeType)

/**
 * Fixes the filename extension to match the detected MIME type.
 *
 * The LibreChat server uses the filename extension (via multer) to determine
 * whether image conversion is needed. If the extension doesn't match the
 * server's configured imageOutputType, the image gets converted but the
 * stored MIME type (from our Content-Type header) does not get updated,
 * causing a mismatch that AI providers reject.
 *
 * By ensuring the extension matches our detected MIME type, we maximize the
 * chance that extension == imageOutputType, avoiding unnecessary conversion.
 */
internal fun fixFilenameExtension(filename: String, mimeType: String): String {
    val expectedExtension = extensionForMimeType(mimeType) ?: return filename

    val dotIndex = filename.lastIndexOf('.')
    val currentExtension = if (dotIndex >= 0) {
        filename.substring(dotIndex + 1).lowercase()
    } else {
        ""
    }

    // Check if current extension already matches (accounting for jpeg/jpg equivalence)
    val normalizedCurrent = when (currentExtension) {
        "jpeg" -> "jpg"
        "tif" -> "tiff"
        else -> currentExtension
    }
    val normalizedExpected = when (expectedExtension) {
        "jpeg" -> "jpg"
        "tif" -> "tiff"
        else -> expectedExtension
    }

    if (normalizedCurrent == normalizedExpected) {
        return filename
    }

    // Replace the extension
    return if (dotIndex >= 0) {
        "${filename.substring(0, dotIndex)}.$expectedExtension"
    } else {
        "$filename.$expectedExtension"
    }
}

/**
 * The bytes, MIME type, and pixel dimensions to upload for an image, after any PNG re-encode /
 * downsample. [reEncoded] is false when the original bytes are used unchanged (already a PNG within
 * budget), true when [bytes]/[mimeType] were produced by re-encoding. [width]/[height] are the
 * decoded pixel dimensions of whatever [bytes] represents — always populated, since a result is
 * only produced once valid bounds are read.
 */
data class ProcessedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val reEncoded: Boolean,
)

/**
 * Longest-edge cap (px) applied when re-encoding an image. Bitmaps larger than this are
 * downsampled before PNG encoding, which keeps the re-encoded upload from inflating to tens
 * of MB and bounds peak memory (a full-res decode of a modern phone photo is a large ARGB_8888
 * allocation). 2048 preserves ample detail — the server and AI providers resize past this
 * anyway — while keeping the PNG small enough to upload reliably.
 */
private const val MAX_IMAGE_DIMENSION = 2048

/**
 * Floor for the longest edge when downsampling further to fit a size limit: below this the image
 * is too degraded to be useful, so we stop shrinking and let the caller's size pre-check reject it
 * rather than upload something unreadable.
 */
private const val MIN_IMAGE_DIMENSION = 512

/**
 * Computes the power-of-two [BitmapFactory.Options.inSampleSize] that keeps the decoded image's
 * longest edge at or below [maxDimension]. Returns 1 (no downsampling) when the source already
 * fits or its dimensions are unknown. BitmapFactory only honors power-of-two sample sizes, so
 * the result is always a power of two.
 */
internal fun computeInSampleSize(srcWidth: Int, srcHeight: Int, maxDimension: Int): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxDimension <= 0) return 1
    var sampleSize = 1
    while (srcWidth / sampleSize > maxDimension || srcHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Prepares an image for upload: re-encodes it to PNG (unless it is already a PNG within the
 * dimension budget) and reports the bytes, MIME type, and dimensions to send.
 *
 * **Why re-encode to PNG at all.** The LibreChat server has a bug in processAgentFileUpload: it
 * converts uploaded images to its configured imageOutputType (default: PNG) using sharp, but stores
 * the *original* MIME type from the upload Content-Type header. When the AI provider (e.g.
 * Anthropic) later receives the base64 image, it gets converted bytes (e.g. PNG) with the original
 * media_type (e.g. image/jpeg), causing a 400 "Image does not match the provided media type" error.
 * The only reliable client-side fix is to ensure NO conversion happens on the server: the server
 * skips conversion when the file extension matches its imageOutputType, so we always send PNG bytes
 * with a `.png` extension and the server leaves them untouched.
 *
 * **Sizing.** An image that is already PNG and already within [maxEncodedBytes] is uploaded
 * untouched at full resolution — no dimension cap — since it needs no re-encode and full detail is
 * more useful to the model (this matches the web client's default). Downsampling applies only when
 * the pixels must be touched anyway: a lossless PNG of a JPEG photo would inflate a 4-12 MB photo to
 * tens of MB, so a re-encoded bitmap is downsampled to [MAX_IMAGE_DIMENSION] on its longest edge
 * before encoding (this also bounds peak decode memory). When [maxEncodedBytes] is given and the PNG
 * still exceeds it, the image is downsampled further (halving each pass) until it fits or the longest
 * edge would drop below [MIN_IMAGE_DIMENSION] — so a detailed photo lands under the server's limit at
 * production time instead of being rejected after the fact. If even the floor doesn't fit, the
 * smallest result is returned and the caller's size pre-check rejects it.
 *
 * Returns null only when the bytes cannot be decoded as an image at all (caller should upload the
 * raw bytes, e.g. for a non-image file or a format BitmapFactory can't read).
 */
internal fun processImageForUpload(
    bytes: ByteArray,
    mimeType: String,
    maxEncodedBytes: Long? = null,
): ProcessedImage? {
    return try {
        // Read dimensions first without allocating the full bitmap so we can size the decode.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            null // Not a decodable image — caller uploads the raw bytes.
        } else {
            // Already PNG and within the limit → upload untouched at full resolution (no decode, no
            // dimension cap). Everything else falls through to re-encode/downsample. See the
            // "Sizing" paragraph in the KDoc above for why fitting PNGs are passed through as-is.
            val alreadyPng = mimeType == "image/png" && detectMimeTypeFromBytes(bytes) == "image/png"
            val fitsLimit = maxEncodedBytes == null || bytes.size <= maxEncodedBytes
            if (alreadyPng && fitsLimit) {
                ProcessedImage(bytes, mimeType, srcWidth, srcHeight, reEncoded = false)
            } else {
                val initialSampleSize = computeInSampleSize(srcWidth, srcHeight, MAX_IMAGE_DIMENSION)
                encodeToPngUnderLimit(bytes, mimeType, srcWidth, srcHeight, initialSampleSize, maxEncodedBytes)
            }
        }
    } catch (e: Exception) {
        Logger.w(e) { "processImageForUpload: failed to process $mimeType, using original bytes" }
        null
    }
}

/**
 * Decodes [bytes] at [initialSampleSize] and PNG-encodes it, halving dimensions each pass until the
 * result is within [maxEncodedBytes] (if set) or the longest edge would fall below
 * [MIN_IMAGE_DIMENSION]. Returns the original bytes (unchanged, [reEncoded] = false) if the PNG
 * can't be verified, or null if the bitmap can't be decoded. [srcWidth]/[srcHeight] are the source
 * dimensions used only for the fall-back result.
 */
private fun encodeToPngUnderLimit(
    bytes: ByteArray,
    mimeType: String,
    srcWidth: Int,
    srcHeight: Int,
    initialSampleSize: Int,
    maxEncodedBytes: Long?,
): ProcessedImage {
    var sampleSize = initialSampleSize
    while (true) {
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            // Header decoded (we have valid srcWidth/srcHeight) but the full pixel decode failed —
            // upload the original bytes, but still report the dimensions read from the bounds pass.
            ?: return ProcessedImage(bytes, mimeType, srcWidth, srcHeight, reEncoded = false)
        val outWidth = bitmap.width
        val outHeight = bitmap.height

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bitmap.recycle()
        val encodedBytes = outputStream.toByteArray()

        // Verify the re-encoded bytes are valid PNG; if not, fall back to the original bytes but
        // keep the source dimensions (so the caller still reports width/height).
        val verifiedType = detectMimeTypeFromBytes(encodedBytes)
        if (verifiedType != "image/png") {
            Logger.w { "encodeToPngUnderLimit: re-encoded bytes are $verifiedType, not image/png; using original" }
            return ProcessedImage(bytes, mimeType, srcWidth, srcHeight, reEncoded = false)
        }

        // Stop when it fits, or when halving again would push the longest edge below the floor —
        // checked against the *next* pass (longestEdge / 2), not the current edge, so we never
        // overshoot and return an image well under MIN_IMAGE_DIMENSION.
        val longestEdge = maxOf(outWidth, outHeight)
        val underLimit = maxEncodedBytes == null || encodedBytes.size <= maxEncodedBytes
        if (underLimit || longestEdge / 2 < MIN_IMAGE_DIMENSION) {
            Logger.d {
                "encodeToPngUnderLimit: $mimeType -> image/png (${bytes.size} -> ${encodedBytes.size} bytes, " +
                    "${outWidth}x$outHeight, sampleSize=$sampleSize)"
            }
            return ProcessedImage(encodedBytes, "image/png", outWidth, outHeight, reEncoded = true)
        }

        // Overshot the limit and there's still room to shrink — halve dimensions and retry.
        sampleSize *= 2
    }
}
