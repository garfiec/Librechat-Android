package com.garfiec.librechat.feature.settings.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/**
 * iOS diagnostic-log saver. Writes [content] to a temp file under [NSTemporaryDirectory], then
 * presents a `UIActivityViewController` share sheet from the key window's root view controller so
 * the user can Save to Files / AirDrop / Mail the export.
 *
 * cinterop notes (see project iOS gotchas + `core:logging` LogFile.ios.kt):
 * - `NSData` is built from a Kotlin `String` via `ByteArray.usePinned { NSData.create(...) }`.
 * - No `String as NSString` casts.
 * - The root VC is resolved via `connectedScenes` exactly like the chat share sheet.
 * - iPad requires a popover anchor or `UIActivityViewController` crashes; we anchor to the
 *   root view's center.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun LogFileSaver(
    triggerFileName: String?,
    content: String?,
    onComplete: (success: Boolean, message: String?) -> Unit,
    onReset: () -> Unit,
) {
    LaunchedEffect(triggerFileName) {
        val fileName = triggerFileName
        val text = content
        if (fileName == null || text == null) return@LaunchedEffect

        val rootVc = getRootViewController()
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

        // Report the outcome from the share-sheet result (not from presentation): the temp file is
        // cleaned up and success is only signalled if the user actually completed the share. A cancel
        // resets without claiming success — matching the Android SAF actual.
        activityVc.completionWithItemsHandler = { _, completed, _, _ ->
            runCatching { NSFileManager.defaultManager.removeItemAtPath(tempPath, null) }
            onComplete(completed, null)
            onReset()
        }

        // iPad: anchor the popover to the root view to avoid an unanchored-popover crash.
        activityVc.popoverPresentationController?.let { popover ->
            val view = rootVc.view
            popover.sourceView = view
            view?.bounds?.useContents {
                popover.sourceRect = CGRectMake(
                    x = size.width / 2.0,
                    y = size.height / 2.0,
                    width = 0.0,
                    height = 0.0,
                )
            }
        }

        rootVc.presentViewController(activityVc, animated = true, completion = null)
    }
}

private fun getRootViewController(): UIViewController? {
    val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene
    return scene?.keyWindow?.rootViewController
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toNSData(): NSData {
    val bytes = this.encodeToByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}
