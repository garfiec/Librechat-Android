package com.garfiec.librechat.feature.agents.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.ui.platform.currentTopmostViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.lastPathComponent
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeContent
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * Strong references to currently-presented document picker delegates.
 * UIDocumentPickerViewController holds its delegate weakly, and Kotlin/Native
 * won't retain it for us. A user can rapidly tap "Add file" on two different
 * capability slots before the first picker has finished animating in — using
 * a single global slot would let the first delegate be GC'd. The set holds
 * each delegate until the picker fires its didPick or didCancel callback.
 */
private val activeAgentPickerDelegates = mutableSetOf<NSObject>()

/**
 * Strong references to NSURLs whose security-scoped resource is currently
 * held open. The agent ContentReader reads bytes from a coroutine after the
 * delegate returns, so the scope must stay open past the callback. We pre-
 * read bytes inside the delegate where the scope is guaranteed valid, hand
 * the in-memory data forward via [PreloadedFileRef], and balance the scope
 * with stopAccessing in the same delegate call — no leak.
 */

@Composable
actual fun rememberAgentFilePicker(
    onFilePick: (fileRef: Any) -> Unit,
): AgentFilePicker {
    val callback = rememberUpdatedState(onFilePick)
    val picker = remember { AgentFilePicker() }
    DisposableEffect(picker) {
        picker.onPicked = { ref -> callback.value(ref) }
        onDispose { picker.onPicked = null }
    }
    return picker
}

actual class AgentFilePicker {
    internal var onPicked: ((Any) -> Unit)? = null

    actual fun launch(mimeType: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val viewController = currentTopmostViewController() ?: run {
                Logger.w { "AgentFilePicker: no root view controller" }
                return@dispatch_async
            }
            // Accept any content type — capability slot (code / knowledge /
            // context) decides storage routing, not the picker.
            val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeContent))
            controller.allowsMultipleSelection = false
            val delegate = Delegate { ref ->
                onPicked?.invoke(ref)
            }
            controller.delegate = delegate
            activeAgentPickerDelegates.add(delegate)
            viewController.presentViewController(controller, animated = true, completion = null)
        }
    }
}

private class Delegate(
    private val onResult: (Any) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        try {
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
            // Hold the security-scoped resource only while we read bytes —
            // balanced inside this method. Reading inside the delegate
            // sidesteps the "user picks a file → coroutine reads later"
            // race where the scope would expire before the read.
            val accessing = url.startAccessingSecurityScopedResource()
            try {
                val data = NSData.dataWithContentsOfURL(url) ?: return
                val bytes = data.toByteArrayOrNull() ?: return
                val filename = url.lastPathComponent ?: "file"
                val ext = filename.substringAfterLast('.', "").lowercase()
                val mime = mimeTypeFromExtension(ext) ?: "application/octet-stream"
                onResult(PreloadedFileRef(bytes = bytes, filename = filename, mimeType = mime))
            } finally {
                if (accessing) url.stopAccessingSecurityScopedResource()
            }
        } finally {
            activeAgentPickerDelegates.remove(this)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeAgentPickerDelegates.remove(this)
    }
}

/**
 * A file reference already resolved to in-memory bytes. The iOS ContentReader
 * recognizes this and skips the second read (which would race the now-closed
 * security-scoped resource).
 */
data class PreloadedFileRef(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
)

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArrayOrNull(): ByteArray? {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

private fun mimeTypeFromExtension(ext: String): String? = when (ext) {
    "json" -> "application/json"
    "yaml", "yml" -> "application/x-yaml"
    "txt" -> "text/plain"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "application/javascript"
    "xml" -> "application/xml"
    "zip" -> "application/zip"
    "csv" -> "text/csv"
    "md" -> "text/markdown"
    else -> null
}
