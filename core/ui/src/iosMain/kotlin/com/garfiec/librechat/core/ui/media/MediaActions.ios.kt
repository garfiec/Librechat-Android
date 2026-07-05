package com.garfiec.librechat.core.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.garfiec.librechat.core.ui.platform.currentTopmostViewController
import com.garfiec.librechat.core.ui.platform.presentSheet
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController

// iOS has no in-app gallery-save today (parity with the previous FullscreenImageViewer, which
// only shared by URL). Surfaces hide their "save" affordance when this returns null.
// Backlog: implement via PHPhotoLibrary/UIImageWriteToSavedPhotosAlbum to match Android's save.
@Composable
actual fun rememberSaveImageToGallery(): ((url: String) -> Unit)? = null

@Composable
actual fun rememberShareImage(): (url: String) -> Unit = { url -> shareUrl(url) }

@Composable
actual fun rememberShareFile(): (bytes: ByteArray, filename: String, mime: String?) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        { bytes, filename, mime -> scope.launch { shareFile(bytes, filename, mime) } }
    }
}

private fun shareUrl(url: String) {
    val viewController = currentTopmostViewController() ?: return
    val activityVC = UIActivityViewController(
        activityItems = listOf(url),
        applicationActivities = null,
    )
    presentSheet(activityVC, from = viewController)
}

// Writes the downloaded bytes to a temp file and shares the file URL, so the user can open it in
// another app / save to Files / AirDrop. Mirrors the diagnostic-log saver's NSData + temp-file
// pattern (see feature/settings LogFileSaver.ios.kt and the project iOS cinterop gotchas).
// The NSData copy and disk write are sized by the file, so they run off the main thread; only the
// UIKit presentation hops back to main.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private suspend fun shareFile(bytes: ByteArray, filename: String, mime: String?) {
    val tempPath = NSTemporaryDirectory() + ensureExtension(sanitizeFilename(filename), mime)
    val wrote = withContext(Dispatchers.Default) {
        bytes.toNSData().writeToFile(tempPath, atomically = true)
    }
    if (!wrote) return
    withContext(Dispatchers.Main) {
        val viewController = currentTopmostViewController() ?: return@withContext
        val activityVC = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(tempPath)),
            applicationActivities = null,
        )
        presentSheet(activityVC, from = viewController)
    }
}

/** Strips path separators so a server-supplied filename can't escape the temp dir. */
private fun sanitizeFilename(filename: String): String =
    filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }

/**
 * Ensures the temp filename carries an extension so the iOS share sheet / receiving apps can infer
 * the type (a PDF whose display name lacks `.pdf` otherwise shares as an unrecognized blob). If the
 * name already has an extension it's left alone; otherwise one is derived from the MIME subtype
 * (`application/pdf` → `pdf`).
 */
private fun ensureExtension(filename: String, mime: String?): String {
    if (filename.contains('.')) return filename
    val ext = mime?.substringAfterLast('/', "")
        ?.substringBefore(';')
        ?.substringBefore('+')
        ?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } }
        ?: return filename
    return "$filename.$ext"
}

/**
 * Copies this [ByteArray] into an [NSData]. The `isEmpty()` guard is load-bearing:
 * `pinned.addressOf(0)` on an empty array is an out-of-bounds access. Shared so callers that hand
 * bytes to UIKit/PDFKit APIs don't each re-implement (and each risk dropping) the empty guard.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
