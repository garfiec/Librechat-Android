package com.garfiec.librechat.feature.conversations.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.ui.platform.currentTopmostViewController
import com.garfiec.librechat.core.ui.platform.presentSheet
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIPasteboard

actual fun copyToClipboard(text: String, label: String) {
    UIPasteboard.generalPasteboard.string = text
}

actual fun showToast(message: String) {
    // iOS doesn't have native Toast — show a brief alert-style overlay.
    Logger.i("Toast") { message }
    // Use UIAlertController as a lightweight toast replacement
    val rootVc = currentTopmostViewController() ?: return
    val alert = UIAlertController.alertControllerWithTitle(
        title = null,
        message = message,
        preferredStyle = UIAlertControllerStyleAlert,
    )
    rootVc.presentViewController(alert, animated = true, completion = null)
    // Auto-dismiss after 1.5 seconds
    NSTimer.scheduledTimerWithTimeInterval(
        interval = 1.5,
        repeats = false,
    ) {
        alert.dismissViewControllerAnimated(true, completion = null)
    }
}

/**
 * iOS conversation export. Writes [content] to a temp file under [NSTemporaryDirectory], then
 * presents a `UIActivityViewController` share sheet so the user can Save to Files / AirDrop /
 * Mail the export. Replaces the prior "not available on iOS" placeholder dialog.
 *
 * cinterop notes (see project iOS gotchas + `core:logging` LogFile.ios.kt):
 * - `NSData` is built from a Kotlin `String` via `ByteArray.usePinned { NSData.create(...) }`.
 * - No `String as NSString` casts.
 * - Presentation + iPad popover anchoring go through the shared [presentSheet] helper.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun FileSaver(
    triggerFileName: String?,
    content: String?,
    onComplete: (success: Boolean, message: String?) -> Unit,
    onReset: () -> Unit,
) {
    LaunchedEffect(triggerFileName) {
        val fileName = triggerFileName
        val text = content
        if (fileName == null || text == null) return@LaunchedEffect

        val rootVc = currentTopmostViewController()
        if (rootVc == null) {
            onComplete(false, "Could not present share sheet")
            onReset()
            return@LaunchedEffect
        }

        val tempPath = NSTemporaryDirectory() + fileName
        val data = text.toNSData()
        val wrote = data.writeToFile(tempPath, atomically = true)
        if (!wrote) {
            onComplete(false, "Could not write export file")
            onReset()
            return@LaunchedEffect
        }

        val url = NSURL.fileURLWithPath(tempPath)
        val activityVc = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )

        // Report the outcome from the share-sheet result (not from presentation): clean up the temp
        // file and only signal success if the user actually completed the share; a cancel resets
        // without claiming success — matching the Android SAF actual.
        activityVc.completionWithItemsHandler = { _, completed, _, _ ->
            runCatching { NSFileManager.defaultManager.removeItemAtPath(tempPath, null) }
            onComplete(completed, null)
            onReset()
        }

        presentSheet(activityVc, from = rootVc)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toNSData(): NSData {
    val bytes = this.encodeToByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}
