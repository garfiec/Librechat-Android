package com.garfiec.librechat.core.ui.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.garfiec.librechat.core.common.media.detectImageMimeType
import com.garfiec.librechat.core.common.media.imageExtensionForMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

private const val STALE_THRESHOLD_MS = 5 * 60_000L

@Composable
actual fun rememberSaveImageToGallery(): ((url: String) -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val url = pendingUrl
        pendingUrl = null
        if (granted && url != null) {
            scope.launch { saveUrlToGallery(context, url) }
        } else if (!granted) {
            Toast.makeText(
                context,
                "Storage permission is required to save images",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    return remember(context, scope) {
        { url ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                scope.launch { saveUrlToGallery(context, url) }
            } else {
                val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    scope.launch { saveUrlToGallery(context, url) }
                } else {
                    pendingUrl = url
                    permissionLauncher.launch(permission)
                }
            }
        }
    }
}

@Composable
actual fun rememberShareImage(): (url: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { url ->
            scope.launch {
                try {
                    val image = loadEncodedImage(context, url)
                    if (image == null) {
                        shareUrlText(context, url)
                        return@launch
                    }
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "shared_images")
                        dir.mkdirs()
                        // Sweep previous shares that are now safely past any in-flight read,
                        // rather than deleting this file on a timer (a slow chooser could still
                        // be reading it). createTempFile guarantees a unique, collision-free name.
                        sweepStaleFiles(dir, STALE_THRESHOLD_MS)
                        val f = File.createTempFile("shared_image_", ".${image.extension}", dir)
                        f.writeBytes(image.bytes)
                        f
                    }

                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        type = image.mimeType
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                } catch (_: Exception) {
                    shareUrlText(context, url)
                }
            }
        }
    }
}

@Composable
actual fun rememberShareFile(): (bytes: ByteArray, filename: String, mime: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { bytes, filename, mime ->
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "shared_files")
                        dir.mkdirs()
                        // Sweep prior shares safely past any in-flight read (mirrors the image path).
                        sweepStaleFiles(dir, STALE_THRESHOLD_MS)
                        val f = File(dir, sanitizeFilename(filename))
                        f.writeBytes(bytes)
                        f
                    }
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                } catch (e: Exception) {
                    toast(context, "Failed to share file: ${e.localizedMessage ?: ""}")
                }
            }
        }
    }
}

/** Strips path separators so a server-supplied filename can't escape the cache dir. */
private fun sanitizeFilename(filename: String): String =
    filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }

private suspend fun saveUrlToGallery(context: Context, url: String) {
    try {
        val image = loadEncodedImage(context, url)
        if (image == null) {
            toast(context, "Failed to load image")
            return
        }
        withContext(Dispatchers.IO) { writeImageToGallery(context, image) }
        toast(context, "Image saved to gallery")
    } catch (e: Exception) {
        toast(context, "Failed to save image: ${e.localizedMessage ?: ""}")
    }
}

private fun writeImageToGallery(context: Context, image: EncodedImage) {
    val fileName = "librechat_${System.currentTimeMillis()}.${image.extension}"
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LibreChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        ?: throw IOException("MediaStore insert returned null")
    try {
        resolver.openOutputStream(uri)?.use { output -> output.write(image.bytes) }
            ?: throw IOException("Could not open output stream for $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val updateValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, updateValues, null, null)
        }
    } catch (e: Exception) {
        // Don't leave an orphaned IS_PENDING (invisible, space-consuming) row behind.
        resolver.delete(uri, null, null)
        throw e
    }
}

/**
 * An image's original encoded bytes plus the file extension / MIME type they represent.
 * [extension] is dotless (e.g. "png"); callers building a filename prepend the dot.
 */
private class EncodedImage(
    val bytes: ByteArray,
    val extension: String,
    val mimeType: String,
)

/**
 * Loads an image's encoded bytes for saving/sharing. Prefers the original bytes already in Coil's
 * disk cache (no decode, original format/animation, bounded memory); falls back to decoding then
 * re-encoding as PNG. Never recycles the decoded bitmap — it may be the shared bitmap Coil's
 * memory cache (and the on-screen viewer) is still drawing.
 */
private suspend fun loadEncodedImage(context: Context, url: String): EncodedImage? =
    readFromDiskCache(context, url) ?: decodeAndEncode(context, url)

private suspend fun readFromDiskCache(context: Context, url: String): EncodedImage? =
    withContext(Dispatchers.IO) {
        val diskCache = SingletonImageLoader.get(context).diskCache ?: return@withContext null
        // The network fetcher keys the disk cache on the URL (options.diskCacheKey ?: url).
        val snapshot = diskCache.openSnapshot(url) ?: return@withContext null
        try {
            val file = File(snapshot.data.toString())
            if (!file.exists() || file.length() == 0L) {
                null
            } else {
                val bytes = file.readBytes()
                encodedImageFor(bytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            snapshot.close()
        }
    }

private suspend fun decodeAndEncode(context: Context, url: String): EncodedImage? {
    // allowHardware(false): a software bitmap is required to read pixels for compress().
    val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
    val decoded = withContext(Dispatchers.IO) {
        SingletonImageLoader.get(context).execute(request).image?.toBitmap()
    } ?: return null
    // A memory-cache hit can still hand back the HARDWARE bitmap decoded by the on-screen display
    // load; compress() can't read its pixels, so copy to a software config first. Never recycle
    // `decoded` — Coil's memory cache may own it. The copy is ours, so free it after compressing.
    // This re-encodes to PNG regardless of source format; the disk-cache path above preserves the
    // original format (and animation), so this lossy fallback only fires on a true cache miss.
    val bytes = withContext(Dispatchers.IO) {
        val isOurCopy = decoded.config == Bitmap.Config.HARDWARE
        val software = if (isOurCopy) decoded.copy(Bitmap.Config.ARGB_8888, false) else decoded
        try {
            ByteArrayOutputStream().use { baos ->
                software.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            }
        } finally {
            if (isOurCopy) software.recycle()
        }
    }
    return EncodedImage(bytes, "png", "image/png")
}

/** Wraps already-encoded bytes, labelling them by their sniffed format (PNG if unrecognized). */
private fun encodedImageFor(bytes: ByteArray): EncodedImage {
    val mimeType = detectImageMimeType(bytes)
    val extension = mimeType?.let { imageExtensionForMimeType(it) }
    return EncodedImage(
        bytes = bytes,
        extension = extension ?: "png",
        mimeType = mimeType ?: "image/png",
    )
}

private suspend fun toast(context: Context, message: String) {
    withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun shareUrlText(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, url)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
