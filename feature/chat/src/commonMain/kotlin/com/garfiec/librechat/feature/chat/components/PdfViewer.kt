package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a PDF (given its raw [bytes]) as a scrollable, zoomable page view using the platform's
 * native PDF engine — Android [android.graphics.pdf.PdfRenderer] rendering each page to a bitmap,
 * iOS PDFKit `PDFView` via `UIKitView`. No third-party dependency and no network: the bytes are
 * already in hand (downloaded through [LocalAttachmentDownloader]).
 *
 * Hosted full-screen by [PdfPreviewOverlay]; the shared shell (top bar, download/loading states)
 * lives there, this is just the page surface.
 *
 * [onRenderError] fires when the native engine can't open the bytes (corrupt/encrypted PDF, or a
 * non-PDF body that slipped past the magic-byte check) so the overlay can surface an error instead
 * of a permanently blank surface.
 */
@Composable
expect fun PdfViewer(bytes: ByteArray, onRenderError: () -> Unit, modifier: Modifier = Modifier)
