package com.garfiec.librechat.feature.skills.components

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
 * Strong references to presented picker delegates — UIDocumentPickerViewController
 * holds its delegate weakly and Kotlin/Native won't retain it. Mirrors the agent
 * picker's retention pattern. Removed once didPick/didCancel fires.
 */
private val activeSkillPickerDelegates = mutableSetOf<NSObject>()

@Composable
actual fun rememberSkillFilePicker(
    onPick: (PickedDocument) -> Unit,
): SkillFilePicker {
    val callback = rememberUpdatedState(onPick)
    val picker = remember { SkillFilePicker() }
    DisposableEffect(picker) {
        picker.onPicked = { doc -> callback.value(doc) }
        onDispose { picker.onPicked = null }
    }
    return picker
}

actual class SkillFilePicker {
    internal var onPicked: ((PickedDocument) -> Unit)? = null

    actual fun launch(mimeTypes: List<String>) {
        dispatch_async(dispatch_get_main_queue()) {
            val viewController = currentTopmostViewController() ?: run {
                Logger.w { "SkillFilePicker: no root view controller" }
                return@dispatch_async
            }
            // Accept any content type — the upload path validates relativePath /
            // file kind server-side; the picker doesn't restrict by UTType here
            // so .md / .zip / .skill are all selectable.
            val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeContent))
            controller.allowsMultipleSelection = false
            val delegate = Delegate { doc -> onPicked?.invoke(doc) }
            controller.delegate = delegate
            activeSkillPickerDelegates.add(delegate)
            viewController.presentViewController(controller, animated = true, completion = null)
        }
    }
}

private class Delegate(
    private val onResult: (PickedDocument) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        try {
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
            // Read bytes inside the delegate while the security-scoped resource
            // is valid; balance start/stop in this same call (see agent picker).
            val accessing = url.startAccessingSecurityScopedResource()
            try {
                val data = NSData.dataWithContentsOfURL(url) ?: return
                val bytes = data.toByteArrayOrNull() ?: return
                val filename = url.lastPathComponent ?: "file"
                val mime = skillFileMimeFromExtension(filename.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                onResult(PickedDocument(bytes = bytes, filename = filename, mimeType = mime))
            } finally {
                if (accessing) url.stopAccessingSecurityScopedResource()
            }
        } finally {
            activeSkillPickerDelegates.remove(this)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activeSkillPickerDelegates.remove(this)
    }
}

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
