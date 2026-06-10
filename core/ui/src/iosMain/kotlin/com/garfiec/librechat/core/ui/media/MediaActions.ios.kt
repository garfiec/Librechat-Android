package com.garfiec.librechat.core.ui.media

import androidx.compose.runtime.Composable
import com.garfiec.librechat.core.ui.platform.currentTopmostViewController
import com.garfiec.librechat.core.ui.platform.presentSheet
import platform.UIKit.UIActivityViewController

// iOS has no in-app gallery-save today (parity with the previous FullscreenImageViewer, which
// only shared by URL). Surfaces hide their "save" affordance when this returns null.
// Backlog: implement via PHPhotoLibrary/UIImageWriteToSavedPhotosAlbum to match Android's save.
@Composable
actual fun rememberSaveImageToGallery(): ((url: String) -> Unit)? = null

@Composable
actual fun rememberShareImage(): (url: String) -> Unit = { url -> shareUrl(url) }

private fun shareUrl(url: String) {
    val viewController = currentTopmostViewController() ?: return
    val activityVC = UIActivityViewController(
        activityItems = listOf(url),
        applicationActivities = null,
    )
    presentSheet(activityVC, from = viewController)
}
