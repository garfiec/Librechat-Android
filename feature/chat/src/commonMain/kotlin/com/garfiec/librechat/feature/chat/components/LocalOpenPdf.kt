package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger

/**
 * Opener for the full-screen PDF preview, provided by [ChatRoot]. A tool-call PDF attachment card
 * deep in the message list fires this with the file's id + display name; ChatRoot downloads the
 * bytes (via [LocalAttachmentDownloader]) and hosts the native [PdfViewer] overlay at the screen
 * root, so it survives the tapped card scrolling off-screen.
 *
 * Default is a logging no-op (not a silent swallow) so a missing provider surfaces in logs.
 * Mirrors [LocalChatMediaViewer].
 */
val LocalOpenPdf = staticCompositionLocalOf<(fileId: String, filename: String) -> Unit> {
    { fileId, _ -> Logger.w { "LocalOpenPdf not provided; ignoring PDF open for $fileId" } }
}
