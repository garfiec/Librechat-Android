package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-scoped, authenticated file downloader for tool-call attachments. Provided once at
 * the chat screen root (backed by `ChatViewModel.downloadFileBytes`), so a file chip deep in the
 * message list can fetch a generated file's bytes on tap without threading a lambda through
 * MessageList → MessageBubble → ContentPartRenderer → ToolCallDispatcher.
 *
 * Returns the file's bytes, or `null` on failure / when no host is in scope (previews, tests).
 * Mirrors the [LocalChatMediaViewer] precedent.
 */
val LocalAttachmentDownloader = staticCompositionLocalOf<suspend (fileId: String) -> ByteArray?> {
    { null }
}
