package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.garfiec.librechat.core.ui.media.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView

/**
 * iOS PDF surface — a PDFKit `PDFView`, which handles continuous scroll, pinch-zoom, and text
 * selection natively. The shared shell (top bar, download/loading states) lives in
 * [PdfPreviewOverlay]; this is just the page surface.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PdfViewer(bytes: ByteArray, onRenderError: () -> Unit, modifier: Modifier) {
    // PDFDocument(data:) is a failable initializer; Kotlin/Native maps that nil to an NPE at the call
    // site (truncated bytes, an HTTP-200 HTML error body, etc.). Catch it and report render failure.
    // A password-protected PDF returns a *non-nil but locked* document (and PDFView would render
    // nothing), and a structurally-empty one has no pages — treat both as render failures too, so the
    // overlay shows its error state instead of a permanently blank surface.
    val document = remember(bytes) {
        runCatching { PDFDocument(bytes.toNSData()) }.getOrNull()
            ?.takeIf { !it.isLocked() && it.pageCount() > 0uL }
    }
    val currentOnRenderError by rememberUpdatedState(onRenderError)
    LaunchedEffect(document) {
        if (document == null) currentOnRenderError()
    }
    if (document == null) return

    UIKitView(
        modifier = modifier.fillMaxSize(),
        factory = {
            PDFView().apply {
                // Continuous vertical scroll with pinch-zoom; autoScales fits pages to width.
                setAutoScales(true)
                setDocument(document)
            }
        },
        update = { view ->
            if (view.document != document) {
                view.setDocument(document)
            }
        },
    )
}
